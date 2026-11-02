package starx.dev.agentbridge

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
    _connection.update {
      when (field) {
        "bind" -> it.copy(bind = value, saved = false)
        "port" -> it.copy(port = value, saved = false)
        else -> it.copy(names = value, saved = false)
      }
    }
  }

  fun setPlain(value: Boolean) {
    _connection.update { it.copy(plain = value, saved = false) }
  }

  fun setBackend(name: String) {
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

  private suspend fun loadCertificate(force: Boolean) {
    _connection.update { it.copy(certificateBusy = true) }
    try {
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
    } catch (e: Exception) {
      certificateRequested = false
      _message.value = "证书准备失败：${e.message}"
    } finally {
      _connection.update { it.copy(certificateBusy = false) }
    }
  }

  fun saveConnection() {
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
          loadCertificate(regenerate)
        }
        _connection.update { it.copy(saved = true) }
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

  fun selfTest() {
    if (_connection.value.busy || !State.running) return
    _connection.update { it.copy(busy = true) }
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) {
          val s = settings(_connection.value)
          val host = if (s.wildcard) "127.0.0.1" else s.bindHost
          val builder =
            OkHttpClient.Builder()
              .connectTimeout(3, TimeUnit.SECONDS)
              .readTimeout(5, TimeUnit.SECONDS)
              .callTimeout(7, TimeUnit.SECONDS)
          if (!s.plain) {
            val trust =
              KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry(
                  "agentbridge",
                  State.vault.identity("tls").getCertificate("tls"),
                )
              }
            val tm =
              TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(trust)
              }
            val manager =
              tm.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            builder.sslSocketFactory(ssl.socketFactory, manager)
          }
          val url =
            "${if(s.plain)"http"else "https"}://${ConnectionSettings.urlHost(host)}:${s.port}/api/v1/status"
          builder
            .build()
            .newCall(
              Request.Builder().url(url).header("Authorization", "Bearer ${State.token()}").build()
            )
            .execute()
            .use { response ->
              if (
                !response.isSuccessful ||
                  !JSONObject(response.body!!.string()).optBoolean("success")
              )
                throw Exception("本机 API 校验失败（HTTP ${response.code}）")
            }
        }
        _message.value = "本机 API 自检通过。请再用另一台 Tailscale 设备执行 agentbridge status，确认跨设备可达。"
      } catch (e: Exception) {
        _message.value = "自检失败：${e.message}"
      } finally {
        _connection.update { it.copy(busy = false) }
      }
    }
  }

  fun setSecurity(key: String, value: Boolean) {
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
