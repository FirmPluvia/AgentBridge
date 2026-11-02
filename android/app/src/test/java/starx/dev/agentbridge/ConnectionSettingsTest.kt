package starx.dev.agentbridge

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ConnectionSettingsTest {
  @Test
  fun tailscaleIpKeepsTlsAndSan() {
    val s = ConnectionSettings("100.88.12.34", 8443)
    s.validate()
    assertEquals(listOf("https://100.88.12.34:8443"), s.controllerUrls())
    assertTrue(s.names().containsAll(listOf("100.88.12.34", "127.0.0.1")))
    assertFalse(s.wildcard)
  }

  @Test
  fun wildcardNeedsExplicitConfigAndIsNotCertificateName() {
    val s =
      ConnectionSettings(
        "0.0.0.0",
        8443,
        extraNames = "phone.tail.ts.net",
        detectedAddresses = listOf("100.91.0.15", "192.168.1.2"),
      )
    s.validate()
    assertTrue(s.wildcard)
    assertFalse(s.names().contains("0.0.0.0"))
    assertTrue(s.names().contains("phone.tail.ts.net"))
    assertTrue(s.names().contains("100.91.0.15"))
    assertFalse(s.names().contains("192.168.1.2"))
  }

  @Test
  fun ipv6UrlBracketsAreSeparateFromCertificate() {
    val s = ConnectionSettings("::", 8443, detectedAddresses = listOf("fd7a:115c:a1e0::1234"))
    s.validate()
    assertEquals(listOf("https://[fd7a:115c:a1e0::1234]:8443"), s.controllerUrls())
    assertTrue(s.names().contains("fd7a:115c:a1e0::1234"))
  }

  @Test
  fun badAddressesAndPortsAreRejected() {
    for (host in
      listOf(
        "http://100.1.2.3",
        "phone/path",
        "name@host",
        "100.999.0.1",
        "fd7a:::1",
        "2001:db8:1",
        "",
      )) {
      try {
        ConnectionSettings(host, 8443).validate()
        fail(host)
      } catch (expected: IllegalArgumentException) {}
    }
    for (port in listOf(0, 80, 65536)) {
      try {
        ConnectionSettings("0.0.0.0", port).validate()
        fail()
      } catch (expected: IllegalArgumentException) {}
    }
  }

  @Test
  fun noAccessAddressIsNeededForWildcardOrHttp() {
    val https = ConnectionSettings("0.0.0.0", 8443)
    https.validate()
    assertTrue(https.controllerUrls().isEmpty())
    assertFalse(https.names().contains("0.0.0.0"))
    val http = ConnectionSettings("0.0.0.0", 8443, plain = true)
    http.validate()
  }

  @Test
  fun onlyTailnetRangesAreAutoAdded() {
    assertTrue(ConnectionSettings.isTailscaleAddress("100.64.0.1"))
    assertTrue(ConnectionSettings.isTailscaleAddress("100.127.255.254"))
    assertFalse(ConnectionSettings.isTailscaleAddress("100.63.0.1"))
    assertFalse(ConnectionSettings.isTailscaleAddress("100.128.0.1"))
    assertFalse(ConnectionSettings.isTailscaleAddress("192.168.1.2"))
  }

  @Test
  fun identityIsLoadedOnceAcrossConcurrentConsumers() {
    val calls = AtomicInteger()
    val cache = IdentityCache<Any>()
    val pool = Executors.newFixedThreadPool(8)
    val reads =
      (1..40).map {
        pool.submit<Any> {
          cache.get("tls") {
            calls.incrementAndGet()
            Any()
          }
        }
      }
    val identity = reads.first().get()
    reads.forEach { assertSame(identity, it.get()) }
    assertEquals(1, calls.get())
    cache.invalidate("tls")
    assertNotSame(
      identity,
      cache.get("tls") {
        calls.incrementAndGet()
        Any()
      },
    )
    assertEquals(2, calls.get())
    pool.shutdownNow()
  }
}
