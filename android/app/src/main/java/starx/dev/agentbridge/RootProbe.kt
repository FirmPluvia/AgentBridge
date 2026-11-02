package starx.dev.agentbridge

import com.topjohnwu.superuser.Shell

object RootProbe {
  @Throws(BridgeError::class)
  fun verify(job: Shell.Job, isRootShell: Boolean): Int {
    val stdout = mutableListOf<String>()
    val stderr = mutableListOf<String>()
    // libsu newJob() discards output unless to(...) is explicitly supplied.
    val result = job.add("/system/bin/id -u").to(stdout, stderr).exec()
    if (result.code != 0)
      throw BridgeError(
        "BACKEND_UNAVAILABLE",
        "UID 检测命令失败（exit=${result.code}）：${stderr.joinToString(" ").take(200)}",
      )
    val uid = stdout.joinToString("").trim().toIntOrNull()
    if (uid == null || uid < 0)
      throw BridgeError(
        "BACKEND_UNAVAILABLE",
        "未能读取有效 UID（检测失败，不是系统 UID=-1）。请检查 KSU / ReKSU 授权并重试。",
      )
    if (!isRootShell || uid != 0)
      throw BridgeError(
        "PERMISSION_DENIED",
        "未获得 Root（读取到的 UID=$uid）。请在 KSU / ReKSU、Magisk 或 APatch 中授权 AgentBridge，再点重试。",
      )
    return uid
  }
}
