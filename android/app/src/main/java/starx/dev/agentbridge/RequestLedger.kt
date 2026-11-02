package starx.dev.agentbridge

/**
 * Durable at-most-once admission. A claimed operation is never automatically replayed, including
 * after a crash between admission and execution. Results can remain unknown.
 */
class RequestLedger(
  private val contains: (String) -> Boolean,
  private val persist: (String) -> Unit,
) {
  @Synchronized
  fun claim(owner: String, requestId: String): Boolean {
    val key = "$owner:$requestId"
    if (contains(key)) return false
    persist(key)
    return true
  }
}
