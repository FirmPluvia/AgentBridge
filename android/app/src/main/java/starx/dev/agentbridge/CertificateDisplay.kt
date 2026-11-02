package starx.dev.agentbridge

/** Presentation only. Trust checks and clipboard use the complete SHA256 fingerprint. */
fun compactFingerprint(fingerprint: String): String =
  if (fingerprint.length <= 35) fingerprint
  else fingerprint.take(17) + " … " + fingerprint.takeLast(17)
