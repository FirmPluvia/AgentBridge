package starx.dev.agentbridge

import org.junit.Assert.*
import org.junit.Test

class PermissionConnectionTest {
  @Test
  fun switchingToUnconnectedBackendIgnoresOtherCachedBackend() {
    val result =
      permissionConnection(
        RuntimeSnapshot(
          backend = "shizuku",
          backendStatus = "请启动 Shizuku 并授权连接",
          rootStatus = "Root 已就绪 · UID=0 · 执行自检通过",
        )
      )
    assertEquals("Shizuku 未授权", result.headline)
    assertEquals("", result.detail)
    assertFalse(result.connected)
    assertEquals("Root 未授权", permissionConnection(RuntimeSnapshot()).headline)
  }

  @Test
  fun readyLabelRequiresLiveSelectedConnectionAndValidUid() {
    val status = "Shizuku 已就绪 · UID=2000 · 执行自检通过"
    val snapshot =
      RuntimeSnapshot(
        backend = "shizuku",
        backendStatus = status,
        backendUid = 2000,
        backendConnected = true,
      )
    assertEquals("当前执行后端：Shizuku", permissionConnection(snapshot).headline)
    assertEquals(status, permissionConnection(snapshot).detail)
    assertFalse(permissionConnection(snapshot.copy(backendConnected = false)).connected)
    assertFalse(
      permissionConnection(snapshot.copy(backendConnected = false)).detail.contains("已就绪")
    )
    assertFalse(permissionConnection(snapshot.copy(backendUid = -1)).connected)
    assertFalse(permissionConnection(snapshot.copy(backendUid = 10001)).connected)
    assertFalse(permissionConnection(snapshot.copy(backend = "root")).connected)
    assertTrue(permissionConnection(snapshot.copy(backend = "root", backendUid = 0)).connected)
  }

  @Test
  fun unavailableStatusKeepsFailureDetailsAndDoesNotClaimReadiness() {
    val status = "Root 服务已退出，请重新授权连接"
    val result =
      permissionConnection(
        RuntimeSnapshot(
          backend = "root",
          backendStatus = status,
          backendUid = 0,
          backendConnected = false,
        )
      )
    assertEquals("Root 未授权", result.headline)
    assertEquals(status, result.detail)
    assertFalse(result.connected)
  }
}
