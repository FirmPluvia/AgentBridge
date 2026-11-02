package starx.dev.agentbridge

fun permissionSummary(backend: String, status: String): String {
  val label =
    when (backend) {
      "root" -> "Root"
      "shizuku" -> "Shizuku"
      else -> return status
    }
  return if (status.startsWith("$label 已就绪") && status.contains("执行自检通过")) "$label 已就绪，执行自检通过"
  else status
}

/** Display only the selected backend's observed IPC connection, never another backend's cache. */
data class PermissionConnection(val headline: String, val detail: String, val connected: Boolean)

fun permissionConnection(runtime: RuntimeSnapshot): PermissionConnection {
  val label =
    when (runtime.backend) {
      "root" -> "Root"
      "shizuku" -> "Shizuku"
      else -> return PermissionConnection("未选择执行后端", "", false)
    }
  val validUid =
    if (runtime.backend == "root") runtime.backendUid == 0 else runtime.backendUid in setOf(0, 2000)
  val connected = runtime.backendConnected && validUid
  val initial =
    runtime.backendStatus.trim() in setOf("", "尚未授权", "尚未连接", "尚未授权；请在权限页申请连接", "请启动 Shizuku 并授权连接")
  return PermissionConnection(
    if (connected) "当前执行后端：$label" else "$label 未授权",
    when {
      initial -> ""
      !connected && runtime.backendStatus.contains("已就绪") -> "执行服务当前不可用，请申请/重试连接"
      else -> runtime.backendStatus
    },
    connected,
  )
}
