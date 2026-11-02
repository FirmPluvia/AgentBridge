package starx.dev.agentbridge

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class ExecWireTest {
  @Test
  fun privilegedBinaryResultIsNotTextTransformed() {
    val bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, -1, -128)
    val result = ExecResult(bytes, "中文 error\n".toByteArray(), 7)
    val buffer = ByteArrayOutputStream()
    ExecWire.write(DataOutputStream(buffer), result)
    val actual = ExecWire.read(DataInputStream(buffer.toByteArray().inputStream()), 4096)
    assertArrayEquals(bytes, actual.stdout)
    assertArrayEquals(result.stderr, actual.stderr)
    assertEquals(7, actual.exitCode)
  }

  @Test
  fun privilegedErrorsStayStructured() {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).apply {
      writeUTF("TIMEOUT")
      writeUTF("command expired")
    }
    try {
      ExecWire.read(DataInputStream(buffer.toByteArray().inputStream()), 1024)
      fail()
    } catch (e: BridgeError) {
      assertEquals("TIMEOUT", e.code)
    }
  }

  @Test
  fun combinedOutputLimitIsEnforcedBeforeAllocation() {
    val buffer = ByteArrayOutputStream()
    ExecWire.write(DataOutputStream(buffer), ExecResult(ByteArray(700), ByteArray(700), 0))
    try {
      ExecWire.read(DataInputStream(buffer.toByteArray().inputStream()), 1024)
      fail()
    } catch (e: BridgeError) {
      assertEquals("OUTPUT_LIMIT_EXCEEDED", e.code)
    }
  }

  @Test
  fun negativeFrameIsRejected() {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).apply {
      writeUTF("")
      writeInt(0)
      writeInt(-1)
    }
    try {
      ExecWire.read(DataInputStream(buffer.toByteArray().inputStream()), 1024)
      fail()
    } catch (e: BridgeError) {
      assertEquals("OUTPUT_LIMIT_EXCEEDED", e.code)
    }
  }
}
