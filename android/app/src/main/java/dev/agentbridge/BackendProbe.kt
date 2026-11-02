package dev.agentbridge

/**
 * Compare the IPC-reported identity with an actually executed command before declaring readiness.
 */
object BackendProbe {
  fun verify(uid: Int, result: ExecResult) {
    if (uid !in setOf(0, 2000))
      throw BridgeError("PERMISSION_DENIED", "执行服务 UID=$uid，需已授权的 root 或 shell 服务")
    if (result.exitCode != 0)
      throw BridgeError("BACKEND_UNAVAILABLE", "执行自检失败（exit=${result.exitCode}）")
    if (result.stdout.toString(Charsets.UTF_8).trim().toIntOrNull() != uid)
      throw BridgeError("PERMISSION_DENIED", "执行自检 UID 与服务 UID 不一致")
  }
}
