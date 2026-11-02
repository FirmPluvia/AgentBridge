package starx.dev.agentbridge

/** Shell has a separate local opt-in but always follows the selected execution backend. */
object ShellAccess {
  fun permits(enabled: Boolean, executionBackend: String) =
    enabled && executionBackend in setOf("root", "shizuku")
}
