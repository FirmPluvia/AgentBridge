package starx.dev.agentbridge

import android.app.*
import android.content.*
import android.net.*
import android.os.*
import starx.dev.agentbridge.State as BridgeState
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.*
import javax.net.ssl.*
import okhttp3.*
import org.json.*

class DirectServer(host: String, port: Int) : NanoWSD(host, port) {
  private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32))

  fun tls() {
    val store = BridgeState.vault.identity("tls")
    val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    km.init(store, "local-encrypted-vault".toCharArray())
    val ssl = SSLContext.getInstance("TLS")
    ssl.init(km.keyManagers, null, null)
    makeSecure(ssl.serverSocketFactory, null)
  }

  private fun token(s: IHTTPSession) = s.headers["authorization"]?.removePrefix("Bearer ") ?: ""

  private fun scopes() = buildSet {
    if (BridgeState.pref("view", "true") == "true") add("view")
    if (BridgeState.pref("ui", "true") == "true") add("ui")
    if (BridgeState.pref("shell") == "true") add("shell")
  }

  override fun serveHttp(s: IHTTPSession): Response {
    if (!BridgeState.engine.authenticate(token(s)))
      return newFixedLengthResponse(
        Response.Status.UNAUTHORIZED,
        "application/json",
        BridgeState.engine
          .result("", "none", false, error = BridgeError("UNAUTHORIZED", "无效或已撤销凭证"))
          .toString(),
      )
    try {
      val uri = s.uri
      if (!uri.startsWith("/api/v1/"))
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
      val op = uri.removePrefix("/api/v1/")
      val expected =
        if (
          op in setOf("status", "capabilities", "apps", "foreground") ||
            op.startsWith("requests/") ||
            (op.startsWith("jobs/") && !op.endsWith("/cancel"))
        )
          Method.GET
        else Method.POST
      if (s.method != expected)
        return newFixedLengthResponse(
          Response.Status.METHOD_NOT_ALLOWED,
          "text/plain",
          "Invalid method",
        )
      val length = s.headers["content-length"]?.toLongOrNull() ?: 0
      if (length !in 0..65536 || s.headers.containsKey("transfer-encoding"))
        return newFixedLengthResponse(
          Response.Status.PAYLOAD_TOO_LARGE,
          "text/plain",
          "Max 64 KiB, Content-Length required",
        )
      val body =
        if (length > 0) {
          val data = ByteArray(length.toInt())
          java.io.DataInputStream(s.inputStream).readFully(data)
          JSONObject(data.toString(Charsets.UTF_8))
        } else JSONObject().apply { s.parameters.forEach { (k, v) -> put(k, v.first()) } }
      return newFixedLengthResponse(
        Response.Status.OK,
        "application/json",
        BridgeState.engine.handle(uri, body, scopes = scopes()).toString(),
      )
    } catch (e: Exception) {
      return newFixedLengthResponse(
        Response.Status.BAD_REQUEST,
        "application/json",
        BridgeState.engine
          .result("", "none", false, error = BridgeError("INVALID_ARGUMENT", "无效 JSON 或请求体"))
          .toString(),
      )
    }
  }

  override fun openWebSocket(handshake: IHTTPSession): WebSocket {
    val credential = token(handshake)
    return object : WebSocket(handshake) {
      override fun onOpen() {
        if (!BridgeState.engine.authenticate(credential))
          close(WebSocketFrame.CloseCode.PolicyViolation, "UNAUTHORIZED", false)
      }

      override fun onClose(
        code: WebSocketFrame.CloseCode?,
        reason: String?,
        initiatedByRemote: Boolean,
      ) {}

      override fun onMessage(message: WebSocketFrame) {
        if (message.textPayload.length > 65536) {
          close(WebSocketFrame.CloseCode.MessageTooBig, "64 KiB", false)
          return
        }
        workers.execute {
          if (!BridgeState.engine.authenticate(credential)) {
            close(WebSocketFrame.CloseCode.PolicyViolation, "UNAUTHORIZED", false)
            return@execute
          }
          try {
            val p = JSONObject(message.textPayload)
            send(
              BridgeState.engine
                .handle(p.getString("path"), p.getJSONObject("body"), scopes = scopes())
                .toString()
            )
          } catch (e: Exception) {
            runCatching { send("{\"error\":\"INVALID_ARGUMENT\"}") }
          }
        }
      }

      override fun onPong(pong: WebSocketFrame) {}

      override fun onException(e: IOException) {
        BridgeState.log("直连 WebSocket 已断开")
      }
    }
  }

  override fun stop() {
    super.stop()
    workers.shutdownNow()
  }
}

class BridgeService : Service() {
  private var direct: DirectServer? = null
  private val worker = Executors.newSingleThreadExecutor()
  private val gate = Any()
  @Volatile private var destroyed = false

  override fun onBind(intent: Intent?) = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == "STOP") {
      BridgeState.running = false
      BridgeState.servicePhase = "stopping"
      BridgeState.engine.stopAll()
      BridgeState.publish()
      stopSelf()
      return START_NOT_STICKY
    }
    if (BridgeState.running || BridgeState.servicePhase == "starting") return START_NOT_STICKY
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel("control", "远程控制", NotificationManager.IMPORTANCE_LOW)
    )
    val stop =
      PendingIntent.getService(
        this,
        0,
        Intent(this, BridgeService::class.java).setAction("STOP"),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val open =
      PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
      )
    startForeground(
      1,
      Notification.Builder(this, "control")
        .setContentTitle("AgentBridge · Tailscale 直连")
        .setContentText("远程控制服务正在准备；点击停止可终止控制")
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(open)
        .addAction(Notification.Action.Builder(null, "停止控制", stop).build())
        .setOngoing(true)
        .build(),
    )
    BridgeState.servicePhase = "starting"
    BridgeState.lastError = ""
    BridgeState.publish()
    worker.execute {
      var server: DirectServer? = null
      try {
        val host = BridgeState.pref("bind", "127.0.0.1")
        val port = BridgeState.pref("port", "8443").toInt()
        val plain = BridgeState.pref("plain") == "true"
        server = DirectServer(host, port)
        if (!plain) server.tls()
        synchronized(gate) {
          if (destroyed) {
            server.stop()
            return@execute
          }
          server.start(30000, false)
          direct = server
          BridgeState.running = true
          BridgeState.servicePhase = "running"
          BridgeState.listener =
            "${if(plain)"http" else "https"}://${ConnectionSettings.urlHost(host)}:$port"
        }
        BridgeState.log(
          "实际监听：${BridgeState.listener}${if(host=="0.0.0.0" || host=="::")"；已明确启用所有接口，局域网也可能可达" else ""}"
        )
        BridgeState.publish()
        manager.notify(
          1,
          Notification.Builder(this, "control")
            .setContentTitle("AgentBridge 控制已启用")
            .setContentText("${BridgeState.engine.selected.name} · ${BridgeState.listener}")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止控制", stop).build())
            .setOngoing(true)
            .build(),
        )
      } catch (e: Exception) {
        server?.stop()
        BridgeState.lastError = "监听失败：${e.message}。若 Tailscale IP 无法绑定，可在连接页明确选择所有接口。"
        BridgeState.log(BridgeState.lastError)
        BridgeState.publish()
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    synchronized(gate) {
      destroyed = true
      BridgeState.running = false
      direct?.stop()
      direct = null
    }
    worker.shutdownNow()
    BridgeState.engine.stopAll()
    BridgeState.servicePhase = "stopped"
    BridgeState.listener = "未启动"
    BridgeState.publish()
    super.onDestroy()
  }
}

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, i: Intent) {
    if (i.action == Intent.ACTION_BOOT_COMPLETED && BridgeState.pref("boot") == "true")
      runCatching { c.startForegroundService(Intent(c, BridgeService::class.java)) }
        .onFailure { BridgeState.log("系统阻止后台启动，请打开应用手动启动") }
  }
}
