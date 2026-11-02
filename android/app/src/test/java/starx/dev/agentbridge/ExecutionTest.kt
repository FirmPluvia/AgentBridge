package starx.dev.agentbridge

import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

class ExecutionTest {
  @Test
  fun specialCharactersRemainArguments() {
    val original = "中文\n'\"; echo injected; $(id) & \\ %s"
    val r = Processes.run(listOf("sh", "-c", "printf %s ${quote(original)}"), "quote", 1000, 8192)
    assertEquals(original, r.stdout.toString(Charsets.UTF_8))
    assertEquals(0, r.exitCode)
  }

  @Test
  fun timeoutIsStructured() {
    try {
      Processes.run(listOf("sh", "-c", "exec sleep 5"), "timeout", 50, 8192)
      fail()
    } catch (e: BridgeError) {
      assertEquals("TIMEOUT", e.code)
    }
  }

  @Test
  fun outputIsBounded() {
    try {
      Processes.run(listOf("sh", "-c", "while true; do echo 1234567890; done"), "limit", 1000, 1024)
      fail()
    } catch (e: BridgeError) {
      assertEquals("OUTPUT_LIMIT_EXCEEDED", e.code)
    }
  }

  @Test
  fun cancellationTerminatesProcess() {
    val pool = Executors.newSingleThreadExecutor()
    val f =
      pool.submit<ExecResult> {
        Processes.run(listOf("sh", "-c", "exec sleep 8"), "cancel", 10000, 8192)
      }
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (!Processes.active.containsKey("cancel") && System.nanoTime() < until) Thread.yield()
    Processes.cancel("cancel")
    try {
      f.get(2, TimeUnit.SECONDS)
      fail()
    } catch (e: ExecutionException) {
      assertEquals("CANCELLED", (e.cause as BridgeError).code)
    } finally {
      pool.shutdownNow()
    }
  }
}
