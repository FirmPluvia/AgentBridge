package starx.dev.agentbridge

import org.junit.Assert.*
import org.junit.Test

class ShellAccessTest {
  @Test
  fun disabledForEveryBackend() {
    listOf("root", "shizuku", "none").forEach { assertFalse(ShellAccess.permits(false, it)) }
  }

  @Test
  fun followsCurrentBackend() {
    assertTrue(ShellAccess.permits(true, "root"))
    assertTrue(ShellAccess.permits(true, "shizuku"))
  }

  @Test
  fun unavailableAndRemovedBackendsAreDenied() {
    listOf("", "none", "adb", "unknown").forEach { assertFalse(ShellAccess.permits(true, it)) }
  }
}
