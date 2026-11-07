package dev.agentbridge

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.security.KeyPairGenerator
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionQrTest {
  private fun configuration(
    urls: List<String> = listOf("https://100.91.0.15:8443"),
    pem: String? = null,
  ): ConnectionQrPayload = ConnectionQrPayload("test-device", urls, "T".repeat(43), pem)

  @Test
  fun actualQrRoundTripKeepsOnlyFiveFieldsAndCompleteCredentials() {
    val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val cert = Certificates.create(key, listOf("100.91.0.15", "phone.example"))
    val pem =
      "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(cert.encoded) +
        "\n-----END CERTIFICATE-----\n"
    val data = configuration(pem = pem).encode()
    val matrix = ConnectionQrEncoder.matrix(data)
    val pixels =
      IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) -0x1000000 else -1
      }
    val decoded =
      QRCodeReader()
        .decode(
          BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        )
        .text
    assertEquals(data, decoded)
    val json = JSONObject(decoded)
    assertEquals(pem, json.getString("certificatePem"))
    assertEquals("T".repeat(43), json.getString("token"))
    assertEquals(2, json.getInt("version"))
    assertEquals(
      setOf("version", "deviceId", "urls", "token", "certificatePem"),
      json.keys().asSequence().toSet(),
    )
    assertFalse(configuration().toString().contains("T".repeat(43)))
  }

  @Test
  fun missingTlsMaterialAndNonRoutableTargetsAreRejected() {
    listOf(
        listOf("https://100.91.0.15:8443"),
        emptyList(),
        listOf("http://0.0.0.0:8443"),
        listOf("http://127.0.0.1:8443"),
      )
      .forEach {
        try {
          configuration(it).encode()
          fail()
        } catch (_: IllegalArgumentException) {}
      }
  }

  @Test
  fun httpExportIsExplicitAndDoesNotEmbedAnUnusedCertificate() {
    val value = JSONObject(configuration(listOf("http://192.168.1.25:8443")).encode())
    assertEquals(2, value.getInt("version"))
    assertTrue(value.getJSONArray("urls").getString(0).startsWith("http://"))
    assertTrue(value.isNull("certificatePem"))
    assertEquals(5, value.length())
  }

  @Test
  fun oversizeQrFailsInsteadOfOmittingSecretsOrCertificate() {
    try {
      ConnectionQrEncoder.matrix("X".repeat(10000))
      fail()
    } catch (e: BridgeError) {
      assertEquals("OUTPUT_LIMIT_EXCEEDED", e.code)
    }
  }
}
