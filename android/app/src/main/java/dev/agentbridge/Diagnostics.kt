package dev.agentbridge

import java.util.zip.CRC32

/** Local read-only diagnosis: no taps, input, authorization requests, or network replay. */
data class DiagnosticItem(
  val id: String,
  val title: String,
  val state: String = "pending",
  val message: String = "",
)

data class DiagnosticUi(
  val busy: Boolean = false,
  val items: List<DiagnosticItem> = emptyList(),
  val finishedAt: Long = 0,
  val backend: String = "",
)

object DiagnosticChecks {
  fun uid(backend: String, expected: Int, result: ExecResult): Int {
    if (backend !in setOf("root", "shizuku") || (backend == "root" && expected != 0))
      throw BridgeError("PERMISSION_DENIED", "当前执行后端未获得有效权限")
    BackendProbe.verify(expected, result)
    return expected
  }

  fun pngSize(bytes: ByteArray): Pair<Int, Int> {
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    if (bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(signature))
      throw BridgeError("PERMISSION_DENIED", "未获得有效PNG截图，可能受权限或页面保护限制")
    fun number(offset: Int): Int =
      (0..3).fold(0) { v, i -> (v shl 8) or (bytes[offset + i].toInt() and 255) }
    if (number(8) != 13 || bytes.copyOfRange(12, 16).toString(Charsets.US_ASCII) != "IHDR")
      throw BridgeError("PERMISSION_DENIED", "截图PNG头无效")
    val width = number(16)
    val height = number(20)
    if (
      width !in 1..32768 ||
        height !in 1..32768 ||
        CRC32().apply { update(bytes, 12, 17) }.value != (number(29).toLong() and 0xffffffffL)
    )
      throw BridgeError("PERMISSION_DENIED", "截图尺寸或PNG校验无效")
    return width to height
  }
}
