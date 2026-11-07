package dev.agentbridge

import org.junit.Assert.*
import org.junit.Test

class DiagnosticChecksTest {
  @Test
  fun realPngDimensionsAreReadWithoutConvertingBinaryToText() {
    val bytes =
      java.util.Base64.getDecoder()
        .decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAcAAAALCAYAAACzkJeoAAAAE0lEQVR4nGNgYGD4jwePSuKVBABaeky0HeHGFwAAAABJRU5ErkJggg=="
        )
    assertEquals(7 to 11, DiagnosticChecks.pngSize(bytes))
    bytes[16] = 1
    try {
      DiagnosticChecks.pngSize(bytes)
      fail()
    } catch (e: BridgeError) {
      assertEquals("PERMISSION_DENIED", e.code)
    }
  }

  @Test
  fun emptyTruncatedAndInvalidScreenshotsNeverPass() {
    listOf(byteArrayOf(), "permission denied".toByteArray(), ByteArray(33)).forEach {
      try {
        DiagnosticChecks.pngSize(it)
        fail()
      } catch (e: BridgeError) {
        assertEquals("PERMISSION_DENIED", e.code)
      }
    }
  }

  @Test
  fun uidCheckRequiresCommandSuccessAndMatchingGrantedBackend() {
    val shell = ExecResult("2000\n".toByteArray(), byteArrayOf(), 0)
    assertEquals(2000, DiagnosticChecks.uid("shizuku", 2000, shell))
    listOf("root" to 2000, "shizuku" to 0, "none" to 2000).forEach { (backend, uid) ->
      try {
        DiagnosticChecks.uid(backend, uid, shell)
        fail()
      } catch (_: BridgeError) {}
    }
    try {
      DiagnosticChecks.uid("shizuku", 2000, ExecResult("2000".toByteArray(), byteArrayOf(), 1))
      fail()
    } catch (_: BridgeError) {}
  }
}
