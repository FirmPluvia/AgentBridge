package dev.agentbridge

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Explicitly exported connection credentials. Never log this object or its encoded contents. */
class ConnectionQrPayload(
  private val deviceId: String,
  private val urls: List<String>,
  private val token: String,
  private val certificatePem: String?,
) {
  override fun toString() = "ConnectionQrPayload([redacted])"

  fun encode(): String {
    require(deviceId.isNotBlank() && token.length >= 32) { "连接凭证尚未就绪" }
    require(urls.isNotEmpty()) { "没有可分享的连接地址；请保存手机实际IP，通配监听时可在高级设置补充实际IP或域名" }
    fun reachableHost(raw: String): Boolean {
      val host = raw.removeSurrounding("[", "]")
      if (host == "localhost" || host.endsWith(".localhost")) return false
      if (ConnectionSettings.validIpv4(host) || ConnectionSettings.validIpv6(host)) {
        val ip = java.net.InetAddress.getByName(host)
        return !ip.isLoopbackAddress && !ip.isAnyLocalAddress && !ip.isMulticastAddress
      }
      return true
    }
    val targets = urls.map { URI(it) }
    require(
      targets.all {
        it.scheme in setOf("https", "http") &&
          it.host != null &&
          it.rawUserInfo == null &&
          reachableHost(it.host) &&
          it.port in 1024..65535 &&
          it.path.orEmpty().isEmpty() &&
          it.query == null &&
          it.fragment == null
      }
    ) {
      "请配置其他设备可访问的真实IP/域名与端口"
    }
    require(targets.map { it.scheme }.distinct().size == 1) { "连接协议不一致" }
    val tls = targets.first().scheme == "https"
    require(!tls || !certificatePem.isNullOrBlank()) { "请先保存设置并准备TLS证书" }
    return JSONObject()
      .put("version", 2)
      .put("deviceId", deviceId)
      .put("urls", JSONArray(urls))
      .put("token", token)
      .put("certificatePem", if (tls) certificatePem else JSONObject.NULL)
      .toString()
  }
}

object ConnectionQrEncoder {
  fun matrix(payload: String, pixels: Int = 1024): BitMatrix =
    try {
      QRCodeWriter()
        .encode(
          payload,
          BarcodeFormat.QR_CODE,
          pixels,
          pixels,
          mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
          ),
        )
    } catch (_: WriterException) {
      throw BridgeError("OUTPUT_LIMIT_EXCEEDED", "连接信息超过单个二维码容量；请精简附加证书名称后重新保存。不会省略凭证或证书")
    }
}

object AgentConnectionPrompt {
  fun text() =
    """读取二维码/JSON为connection.json，勿公开令牌。工具端先接入同一局域网/热点或Tailscale，安装源码SDK后执行：
agentbridge --connection-config connection.json status
再查capabilities、截图后按实际像素操作；保留TLS校验，超时不重放点击。"""
}
