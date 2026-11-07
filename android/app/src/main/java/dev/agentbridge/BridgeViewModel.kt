package dev.agentbridge

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class ConnectionUi(
  val loaded: Boolean = false,
  val bind: String = "127.0.0.1",
  val port: String = "8443",
  val names: String = "",
  val plain: Boolean = false,
  val addresses: List<String> = emptyList(),
  val vpnDetected: Boolean = false,
  val vpnStatus: String = "正在读取 VPN 地址…",
  val certificate: String = "",
  val fingerprint: String = "",
  val busy: Boolean = false,
  val certificateBusy: Boolean = false,
  val saved: Boolean = true,
) {
  val controllerUrls
    get() =
      ConnectionSettings(bind.trim(), port.toIntOrNull() ?: 8443, plain, names, addresses)
        .controllerUrls()
}

data class SecurityUi(
  val view: Boolean = true,
  val ui: Boolean = true,
  val shell: Boolean = false,
  val ime: Boolean = false,
  val boot: Boolean = false,
  val loaded: Boolean = false,
)

class BridgeViewModel(application: Application) : AndroidViewModel(application) {
  private val _uiTheme =
    MutableStateFlow(
      State.pref("uiTheme", "material3").takeIf { it in setOf("miuix", "material3") } ?: "material3"
    )
  val uiTheme = _uiTheme.asStateFlow()

  fun setUiTheme(theme: String) {
    if (theme !in setOf("miuix", "material3")) return
    _uiTheme.value = theme
    State.put("uiTheme", theme)
  }

  val deviceId = MutableStateFlow("")
  val runtime = State.runtime
  val audit = State.audit
  private val _connection = MutableStateFlow(ConnectionUi())
  val connection = _connection.asStateFlow()
  private val _security = MutableStateFlow(SecurityUi())
  val security = _security.asStateFlow()
  private val _message = MutableStateFlow("")
  val message = _message.asStateFlow()
  private val _rootBusy = MutableStateFlow(false)
  val rootBusy = _rootBusy.asStateFlow()
  private val _token = MutableStateFlow("")
  val token = _token.asStateFlow()
  private var certificateRequested = false

  init {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        deviceId.value = State.deviceId
        val vpn = VpnAddressReader.read(getApplication())
        _connection.value =
          ConnectionUi(
            loaded = true,
            bind = State.pref("bind", "127.0.0.1"),
            port = State.pref("port", "8443"),
            names = State.pref("tlsExtraNames"),
            plain = State.pref("plain") == "true",
            addresses = vpn.addresses,
            vpnDetected = vpn.vpnDetected,
            vpnStatus = vpn.explanation,
          )
        val storedNames = State.pref("tlsNames").split(',').filter { it.isNotEmpty() }.sorted()
        _connection.update {
          it.copy(saved = it.saved && (it.plain || settings(it).names() == storedNames))
        }
        _security.value =
          SecurityUi(
            State.pref("view", "true") == "true",
            State.pref("ui", "true") == "true",
            State.pref("shell") == "true",
            State.pref("imeEnabled") == "true",
            State.pref("boot") == "true",
            loaded = true,
          )
      }
    }
  }

  fun edit(field: String, value: String) {
    hideConnectionQr()
    _connection.update {
      when (field) {
        "bind" -> it.copy(bind = value, saved = false)
        "port" -> it.copy(port = value, saved = false)
        else -> it.copy(names = value, saved = false)
      }
    }
  }

  fun setPlain(value: Boolean) {
    hideConnectionQr()
    _connection.update { it.copy(plain = value, saved = false) }
  }

  fun setBackend(name: String) {
    hideConnectionQr()
    if (name !in setOf("root", "shizuku")) return
    State.engine.stopAll()
    State.put("backend", name)
    State.publish()
  }

  fun rootAuthorize() {
    if (_rootBusy.value) return
    _rootBusy.value = true
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { State.engine.root.verify() }
        _message.value = "Root 执行服务已连接，自检 UID=0"
      } catch (e: Exception) {
        _message.value = e.message ?: "Root 连接失败"
      } finally {
        _rootBusy.value = false
        State.publish()
      }
    }
  }

  fun shizukuAuthorize() {
    runCatching { State.engine.shizuku.bind() }
      .onFailure { _message.value = it.message ?: "Shizuku 不可用" }
    State.publish()
  }

  private fun settings(c: ConnectionUi): ConnectionSettings =
    ConnectionSettings(
      c.bind.trim().removeSurrounding("[", "]"),
      c.port.toIntOrNull() ?: 0,
      c.plain,
      c.names,
      c.addresses,
    )

  fun prepareCertificate() {
    if (certificateRequested || !_connection.value.loaded || _connection.value.plain) return
    certificateRequested = true
    viewModelScope.launch { loadCertificate(false) }
  }

  private suspend fun loadCertificate(force: Boolean): Boolean {
    _connection.update { it.copy(certificateBusy = true) }
    return try {
      val pair =
        withContext(Dispatchers.IO) {
          if (force) State.vault.resetIdentity("tls")
          val cert = State.vault.identity("tls").getCertificate("tls")
          val pem =
            "-----BEGIN CERTIFICATE-----\n" +
              Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") +
              "\n-----END CERTIFICATE-----\n"
          pem to
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") {
              "%02X".format(it)
            }
        }
      _connection.update { it.copy(certificate = pair.first, fingerprint = pair.second) }
      true
    } catch (e: Exception) {
      certificateRequested = false
      _message.value = "证书准备失败：${e.message}"
      false
    } finally {
      _connection.update { it.copy(certificateBusy = false) }
    }
  }

  fun saveConnection() {
    hideConnectionQr()
    if (_connection.value.busy || State.servicePhase != "stopped") return
    val candidate = _connection.value
    _connection.update { it.copy(busy = true) }
    viewModelScope.launch {
      try {
        val s = settings(candidate)
        s.validate()
        val regenerate =
          withContext(Dispatchers.IO) {
            val expected = s.names()
            val old = State.pref("tlsNames").split(',').filter { it.isNotEmpty() }.sorted()
            State.put("bind", s.bindHost)
            State.put("port", s.port.toString())
            State.put("plain", s.plain.toString())
            State.put("tlsExtraNames", s.extraNames)
            State.put("tlsNames", expected.joinToString(","))
            State.put("mode", "direct")
            old != expected
          }
        if (!s.plain) {
          certificateRequested = true
          if (!loadCertificate(regenerate)) {
            _connection.update { it.copy(saved = false) }
            return@launch
          }
        }
        _connection.update { current -> current.copy(saved = settings(current) == s) }
        _message.value =
          "监听设置已保存。" +
            (s.controllerUrls()
              .takeIf { it.isNotEmpty() }
              ?.joinToString("\n", prefix = "\n控制端连接地址：\n") ?: "在控制端填写本机 Tailscale IP 和端口。")
      } catch (e: Exception) {
        _message.value = e.message ?: "参数无效"
      } finally {
        _connection.update { it.copy(busy = false) }
      }
    }
  }

  private fun applyVpnAddresses(vpn: VpnAddresses) {
    if (
      _connection.value.addresses.filter { ConnectionSettings.isTailscaleAddress(it) } !=
        vpn.tailscaleCandidates
    )
      hideConnectionQr()
    _connection.update { old ->
      old.copy(
        addresses = vpn.addresses,
        vpnDetected = vpn.vpnDetected,
        vpnStatus = vpn.explanation,
        saved =
          old.saved &&
            (old.plain ||
              old.addresses.filter { ConnectionSettings.isTailscaleAddress(it) } ==
                vpn.tailscaleCandidates),
      )
    }
  }

  fun useDetectedTailscale() {
    viewModelScope.launch {
      val vpn = withContext(Dispatchers.IO) { VpnAddressReader.read(getApplication()) }
      applyVpnAddresses(vpn)
      val ip = vpn.preferredListenIpv4
      if (ip != null) edit("bind", ip)
      else _message.value = "未找到可用的 Tailscale IPv4，请在官方 Tailscale App 中确认后手动填写。"
    }
  }

  fun refreshAddresses() {
    viewModelScope.launch {
      applyVpnAddresses(withContext(Dispatchers.IO) { VpnAddressReader.read(getApplication()) })
    }
  }

  fun start() {
    if (_connection.value.busy || _connection.value.certificateBusy) {
      _message.value = "正在准备连接设置，请稍候再启动"
      return
    }
    if (
      (State.engine.selected.name == "root" && State.engine.root.uid != 0) ||
        (State.engine.selected.name == "shizuku" && State.engine.shizuku.remote == null)
    ) {
      _message.value = "请先在权限页授权并连接当前后端"
      return
    }
    if (!_connection.value.saved) {
      _message.value = "请先在连接页保存设置"
      return
    }
    if (State.engine.selected.name == "none") {
      _message.value = "请先在权限页选择并授权后端"
      return
    }
    runCatching {
        getApplication<Application>()
          .startForegroundService(Intent(getApplication(), BridgeService::class.java))
      }
      .onFailure { _message.value = it.message ?: "启动失败" }
  }

  fun stop() {
    hideConnectionQr()
    cancelSelfTest()
    State.running = false
    State.servicePhase = "stopping"
    State.engine.stopAll()
    State.publish()
    getApplication<Application>().stopService(Intent(getApplication(), BridgeService::class.java))
    if (State.listener == "未启动") {
      State.servicePhase = "stopped"
      State.publish()
    }
  }

  private val _diagnostic = MutableStateFlow(DiagnosticUi())
  val diagnostic = _diagnostic.asStateFlow()
  private var diagnosticJob: kotlinx.coroutines.Job? = null
  @Volatile private var activeProbe: Pair<ExecutionBackend, String>? = null
  @Volatile private var activeHttp: okhttp3.Call? = null
  private val _qr = MutableStateFlow<android.graphics.Bitmap?>(null)
  val qr = _qr.asStateFlow()
  private val _qrBusy = MutableStateFlow(false)
  val qrBusy = _qrBusy.asStateFlow()
  private var qrJob: kotlinx.coroutines.Job? = null
  private val qrGeneration = java.util.concurrent.atomic.AtomicInteger()

  fun hideConnectionQr() {
    qrGeneration.incrementAndGet()
    qrJob?.cancel()
    _qr.value = null
    _qrBusy.value = false
  }

  /** Called only by the explicit local risk-confirmation action. */
  fun showConnectionQr() {
    if (_diagnostic.value.busy) cancelSelfTest()
    hideConnectionQr()
    val c = _connection.value
    if (!c.loaded || !c.saved || c.busy || c.certificateBusy) {
      _message.value = "请先保存连接设置并等待证书准备完成"
      return
    }
    val generation = qrGeneration.get()
    _qrBusy.value = true
    qrJob =
      viewModelScope.launch {
        try {
          val bitmap =
            withContext(Dispatchers.IO) {
              val s = settings(c)
              s.validate()
              val payload =
                ConnectionQrPayload(
                    State.deviceId,
                    s.controllerUrls(),
                    State.token(),
                    if (s.plain) null else c.certificate,
                  )
                  .encode()
              val matrix = ConnectionQrEncoder.matrix(payload)
              val pixels =
                IntArray(matrix.width * matrix.height) { i ->
                  if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK
                  else android.graphics.Color.WHITE
                }
              android.graphics.Bitmap.createBitmap(
                pixels,
                matrix.width,
                matrix.height,
                android.graphics.Bitmap.Config.ARGB_8888,
              )
            }
          if (qrGeneration.get() == generation) _qr.value = bitmap
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          if (qrGeneration.get() == generation)
            _message.value =
              when (e) {
                is BridgeError -> e.message
                is IllegalArgumentException -> e.message ?: "连接配置无效"
                else -> "二维码生成失败，请确认连接设置和证书完整"
              }
        } finally {
          if (qrGeneration.get() == generation) _qrBusy.value = false
        }
      }
  }

  fun cancelSelfTest() {
    diagnosticJob?.cancel()
    activeProbe?.let { (backend, token) -> runCatching { backend.cancel(token) } }
    activeHttp?.cancel()
    _diagnostic.update { state ->
      state.copy(
        items =
          state.items.map {
            if (it.state in setOf("pending", "running"))
              it.copy(state = "cancelled", message = "自检已取消")
            else it
          }
      )
    }
  }

  private fun probe(
    backend: ExecutionBackend,
    argv: List<String>,
    timeout: Int = 5000,
    limit: Int = 4096,
  ): ExecResult {
    if (diagnosticJob?.isActive != true) throw kotlinx.coroutines.CancellationException("检查已取消")
    if (State.engine.selected !== backend) throw BridgeError("CANCELLED", "执行后端已切换，请重新自检")
    val token = "diagnostic-${java.util.UUID.randomUUID()}"
    activeProbe = backend to token
    return try {
      backend.execute(argv, token, timeout, limit)
    } finally {
      activeProbe = null
    }
  }

  fun selfTest() {
    if (_diagnostic.value.busy) return
    hideConnectionQr()
    val backend = State.engine.selected
    val candidate = _connection.value
    val labels =
      listOf(
        "config" to "连接配置与证书",
        "uid" to "执行后端与实际 UID",
        "screen" to "屏幕截图",
        "input" to "点击命令通道",
        "ime" to "中文辅助输入法",
        "api" to "本机 API 与鉴权",
      )
    _diagnostic.value =
      DiagnosticUi(true, labels.map { DiagnosticItem(it.first, it.second) }, backend = backend.name)
    diagnosticJob =
      viewModelScope.launch {
        var uidOk = false
        suspend fun check(id: String, block: suspend () -> Pair<String, String>) {
          kotlinx.coroutines.currentCoroutineContext().ensureActive()
          _diagnostic.update {
            it.copy(
              items = it.items.map { row -> if (row.id == id) row.copy(state = "running") else row }
            )
          }
          val result =
            try {
              withContext(Dispatchers.IO) { block() }
            } catch (e: kotlinx.coroutines.CancellationException) {
              throw e
            } catch (e: Exception) {
              "fail" to
                when (e) {
                  is BridgeError -> "${e.code}：${e.message.take(200)}"
                  is IllegalArgumentException -> e.message?.take(200).orEmpty()
                  else -> "检查失败（${e.javaClass.simpleName}）"
                }
            }
          _diagnostic.update {
            it.copy(
              items =
                it.items.map { row ->
                  if (row.id == id) row.copy(state = result.first, message = result.second) else row
                }
            )
          }
        }
        try {
          check("config") {
            if (!candidate.loaded || !candidate.saved)
              throw BridgeError("INVALID_ARGUMENT", "存在未保存设置，请先保存连接配置")
            val s = settings(candidate)
            s.validate()
            if (!s.plain)
              State.vault.identity("tls").getCertificate("tls").let {
                (it as java.security.cert.X509Certificate).checkValidity()
              }
            when {
              s.plain -> "warn" to "可信网络HTTP模式；未使用应用TLS加密"
              s.bindHost in setOf("127.0.0.1", "::1") -> "warn" to "仅监听本机，其他设备不能连接"
              s.wildcard -> "warn" to "配置与证书有效；监听所有接口，请用手机真实IP访问"
              else -> "pass" to "配置有效，TLS证书在有效期内"
            }
          }
          check("uid") {
            State.publish()
            val snapshot = State.runtime.value
            if (snapshot.backend != backend.name || !snapshot.backendConnected)
              throw BridgeError("BACKEND_UNAVAILABLE", "${backend.name}未连接，请在权限页申请/重试；自检不会自动申请权限")
            val result = probe(backend, listOf("/system/bin/id", "-u"))
            val uid = DiagnosticChecks.uid(backend.name, snapshot.backendUid, result)
            uidOk = true
            "pass" to "${backend.name} · UID=$uid（${if (uid == 0) "root" else "shell"}）"
          }
          check("screen") {
            if (!uidOk) "skip" to "后端未就绪，未执行截图"
            else if (State.pref("view", "true") != "true") "skip" to "查看屏幕权限已关闭"
            else {
              val result =
                probe(backend, listOf("/system/bin/screencap", "-p"), 10000, 16 * 1024 * 1024)
              if (result.exitCode != 0)
                throw BridgeError("PERMISSION_DENIED", "截图命令失败，exit=${result.exitCode}")
              val dimensions = DiagnosticChecks.pngSize(result.stdout)
              val options =
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
              android.graphics.BitmapFactory.decodeByteArray(
                result.stdout,
                0,
                result.stdout.size,
                options,
              )
              if (options.outWidth != dimensions.first || options.outHeight != dimensions.second)
                throw BridgeError("PERMISSION_DENIED", "系统未能解码截图PNG")
              "pass" to "实际截图 ${dimensions.first} × ${dimensions.second}，仅内存检查，不保存图片"
            }
          }
          check("input") {
            if (!uidOk) "skip" to "后端未就绪，未检查输入通道"
            else if (State.pref("ui", "true") != "true") "skip" to "界面操作权限已关闭"
            else {
              val result = probe(backend, listOf("/system/bin/sh", "-c", "command -v input"))
              if (result.exitCode != 0 || result.stdout.isEmpty())
                throw BridgeError("UNSUPPORTED", "系统input命令不可用")
              "warn" to "已找到input命令；未发送实际点击，触控仍需控制端实测"
            }
          }
          check("ime") {
            val manager =
              getApplication<Application>()
                .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            val enabled =
              manager.enabledInputMethodList.any {
                it.packageName == getApplication<Application>().packageName
              }
            "warn" to if (enabled) "辅助输入法已启用；未输入文本或更改当前输入法" else "辅助输入法未启用，中文与多行输入需在系统设置中启用"
          }
          check("api") {
            if (!State.running) "skip" to "控制服务未启动；启动后重新自检，不会自动开放监听"
            else {
              checkLocalApi()
              "pass" to "本机API、当前凭证及错误令牌拒绝检查通过；不代表跨设备可达"
            }
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          _diagnostic.update { state ->
            state.copy(
              items =
                state.items.map {
                  if (it.state in setOf("pending", "running"))
                    it.copy(state = "cancelled", message = "自检已取消")
                  else it
                }
            )
          }
          throw e
        } finally {
          State.publish()
          _diagnostic.update { it.copy(busy = false, finishedAt = System.currentTimeMillis()) }
        }
      }
  }

  private fun checkLocalApi() {
    val s =
      ConnectionSettings(
        State.pref("bind", "127.0.0.1"),
        State.pref("port", "8443").toInt(),
        State.pref("plain") == "true",
      )
    val host =
      if (s.bindHost == "0.0.0.0") "127.0.0.1"
      else if (s.bindHost == "::") "localhost" else s.bindHost
    val builder =
      OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
    if (!s.plain) {
      val trust =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
          load(null)
          setCertificateEntry("agentbridge", State.vault.identity("tls").getCertificate("tls"))
        }
      val tm =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
          init(trust)
        }
      val manager = tm.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single()
      val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
      builder.sslSocketFactory(ssl.socketFactory, manager)
    }
    val client = builder.build()
    val url =
      "${if(s.plain) "http" else "https"}://${ConnectionSettings.urlHost(host)}:${s.port}/api/v1/status"
    fun request(credential: String, valid: Boolean) {
      val call =
        client.newCall(
          Request.Builder().url(url).header("Authorization", "Bearer $credential").build()
        )
      activeHttp = call
      try {
        call.execute().use { response ->
          if (valid) {
            val result = JSONObject(response.body?.string().orEmpty())
            if (!response.isSuccessful || !result.optBoolean("success"))
              throw BridgeError(
                result.optJSONObject("error")?.optString("code")?.takeIf { it.isNotBlank() }
                  ?: if (response.code == 401) "UNAUTHORIZED" else "BACKEND_UNAVAILABLE",
                "本机API拒绝当前凭证或查看权限，HTTP=${response.code}",
              )
          } else if (response.code != 401)
            throw BridgeError("UNAUTHORIZED", "错误令牌未被拒绝，HTTP=${response.code}")
        }
      } finally {
        activeHttp = null
      }
    }
    request(State.token(), true)
    if (diagnosticJob?.isActive != true) throw kotlinx.coroutines.CancellationException("检查已取消")
    request("diagnostic-invalid-${java.util.UUID.randomUUID()}", false)
  }

  override fun onCleared() {
    hideConnectionQr()
    cancelSelfTest()
    super.onCleared()
  }

  fun setSecurity(key: String, value: Boolean) {
    hideConnectionQr()
    _security.update {
      when (key) {
        "view" -> it.copy(view = value)
        "ui" -> it.copy(ui = value)
        "shell" -> it.copy(shell = value)
        "imeEnabled" -> it.copy(ime = value)
        else -> it.copy(boot = value)
      }
    }
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        State.put(key, value.toString())
        if (key in setOf("view", "ui", "shell")) State.engine.stopAll()
      }
    }
  }

  fun showToken(copyToClipboard: Boolean = false) {
    viewModelScope.launch {
      val value = withContext(Dispatchers.IO) { State.token() }
      _token.value = value
      if (copyToClipboard) copy("AgentBridge token", value)
    }
  }

  fun hideToken() {
    _token.value = ""
  }

  fun revoke() {
    hideConnectionQr()
    viewModelScope.launch {
      withContext(Dispatchers.IO) { State.rotate() }
      _token.value = ""
      _message.value = "旧控制令牌及会话已撤销，请重新复制新令牌"
      State.publish()
    }
  }

  fun emergency() {
    stop()
    revoke()
  }

  fun copyCertificate() {
    val value = _connection.value.certificate
    if (value.isNotEmpty()) copy("AgentBridge certificate", value)
  }

  fun copy(label: String, value: String) {
    getApplication<Application>()
      .getSystemService(ClipboardManager::class.java)
      .setPrimaryClip(ClipData.newPlainText(label, value))
  }

  fun dismissMessage() {
    _message.value = ""
  }
}
