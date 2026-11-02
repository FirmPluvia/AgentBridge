package starx.dev.agentbridge

import org.junit.Assert.*
import org.junit.Test

class VpnAddressPolicyTest {
  @Test
  fun cellularCgnatAndWifiNeverBecomeVpnCandidates() {
    val result =
      VpnAddressPolicy.select(
        listOf(
          NetworkAddressSnapshot(false, "rmnet0", listOf("100.91.0.15")),
          NetworkAddressSnapshot(false, "wlan0", listOf("192.168.1.2")),
        )
      )
    assertFalse(result.vpnDetected)
    assertTrue(result.addresses.isEmpty())
    assertTrue(result.tailscaleCandidates.isEmpty())
  }

  @Test
  fun interfaceNameAloneDoesNotProveVpn() {
    val result =
      VpnAddressPolicy.select(
        listOf(NetworkAddressSnapshot(false, "tun0", emptyList(), listOf("100.91.0.15")))
      )
    assertTrue(result.addresses.isEmpty())
  }

  @Test
  fun provenVpnIncludesOnlyUsableDualStackAddresses() {
    val result =
      VpnAddressPolicy.select(
        listOf(
          NetworkAddressSnapshot(
            true,
            "tun0",
            listOf(
              "100.91.0.15",
              "fd7a:115c:a1e0::15",
              "fe80::1%tun0",
              "127.0.0.1",
              "::1",
              "0.0.0.0",
              "::",
              "224.1.2.3",
              "100.91.0.15",
            ),
          )
        )
      )
    assertTrue(result.vpnDetected)
    assertEquals(2, result.addresses.size)
    assertEquals(2, result.tailscaleCandidates.size)
  }

  @Test
  fun fallbackUsesOnlyExactProvenVpnInterface() {
    val result =
      VpnAddressPolicy.select(
        listOf(
          NetworkAddressSnapshot(true, "tun0", emptyList(), listOf("100.91.0.15")),
          NetworkAddressSnapshot(false, "rmnet0", emptyList(), listOf("100.99.0.4")),
        )
      )
    assertEquals(listOf("100.91.0.15"), result.addresses)
  }

  @Test
  fun genericVpnIsNotClaimedAsTailscale() {
    val result =
      VpnAddressPolicy.select(listOf(NetworkAddressSnapshot(true, "tun0", listOf("10.8.0.2"))))
    assertTrue(result.vpnDetected)
    assertEquals(listOf("10.8.0.2"), result.addresses)
    assertTrue(result.tailscaleCandidates.isEmpty())
  }

  @Test
  fun hiddenVpnAddressesDoNotFallBackToPhysicalNetworks() {
    val result =
      VpnAddressPolicy.select(
        listOf(
          NetworkAddressSnapshot(true),
          NetworkAddressSnapshot(false, "wlan0", listOf("192.168.1.2")),
        )
      )
    assertTrue(result.vpnDetected)
    assertTrue(result.addresses.isEmpty())
    assertTrue(result.explanation.contains("没有公开"))
  }
}
