package starx.dev.agentbridge

import org.junit.Assert.*
import org.junit.Test

class ConnectionPresentationTest {
  @Test
  fun autoSelectSkipsIpv6AndOtherVpnAddresses() {
    val vpn = VpnAddresses(true, listOf("fd7a:115c:a1e0::1", "10.0.0.1", "100.91.0.15"))
    assertEquals("100.91.0.15", vpn.preferredListenIpv4)
  }

  @Test
  fun noIpv4DoesNotFallBackToIpv6OrWildcard() {
    assertNull(VpnAddresses(true, listOf("fd7a:115c:a1e0::1", "10.0.0.1")).preferredListenIpv4)
    assertNull(VpnAddresses().preferredListenIpv4)
  }

  @Test
  fun hashDisplayKeepsBothEndsWithoutChangingOriginal() {
    val full = (1..32).joinToString(":") { "%02X".format(it) }
    assertEquals(full.take(17) + " … " + full.takeLast(17), compactFingerprint(full))
    assertEquals(95, full.length)
    assertEquals("", compactFingerprint(""))
  }
}
