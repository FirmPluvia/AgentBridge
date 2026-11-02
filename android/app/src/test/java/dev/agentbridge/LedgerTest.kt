package dev.agentbridge

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LedgerTest {
  @Test
  fun duplicatesNeverExecuteTwice() {
    val store = mutableSetOf<String>()
    val ledger = RequestLedger(store::contains, { store.add(it) })
    val admissions = AtomicInteger()
    val pool = Executors.newFixedThreadPool(8)
    val futures =
      (1..80).map {
        pool.submit { if (ledger.claim("controller-a", "request")) admissions.incrementAndGet() }
      }
    futures.forEach { it.get() }
    pool.shutdown()
    assertEquals(1, admissions.get())
    assertTrue(ledger.claim("controller-b", "request"))
  }

  @Test
  fun crashAfterAdmissionKeepsUnknownInsteadOfReplay() {
    val durable = mutableSetOf<String>()
    val first = RequestLedger(durable::contains, { durable.add(it) })
    assertTrue(first.claim("owner", "request"))
    val recovered = RequestLedger(durable::contains, { durable.add(it) })
    assertFalse(recovered.claim("owner", "request"))
  }
}
