package starx.dev.agentbridge

import org.junit.Assert.*
import org.junit.Test

class BackendProbeTest {
  private fun result(text: String, exit: Int = 0) =
    ExecResult(text.toByteArray(), byteArrayOf(), exit)

  @Test
  fun actualRootAndShellIdentitiesPass() {
    BackendProbe.verify(0, result("0\n"))
    BackendProbe.verify(2000, result("2000\n"))
  }

  @Test
  fun reportedAndExecutedIdentityMismatchIsDenied() {
    try {
      BackendProbe.verify(0, result("2000"))
      fail()
    } catch (e: BridgeError) {
      assertEquals("PERMISSION_DENIED", e.code)
    }
  }

  @Test
  fun normalAppUidDoesNotCountAsAuthorized() {
    try {
      BackendProbe.verify(10001, result("10001"))
      fail()
    } catch (e: BridgeError) {
      assertEquals("PERMISSION_DENIED", e.code)
    }
  }

  @Test
  fun failedOrMissingOutputCannotBeReady() {
    listOf(result("", 1), result("garbage"), result("")).forEach {
      try {
        BackendProbe.verify(2000, it)
        fail()
      } catch (e: BridgeError) {
        assertFalse(e.code.isEmpty())
      }
    }
  }
}
