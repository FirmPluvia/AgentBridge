package starx.dev.agentbridge

import java.io.DataInputStream
import java.io.DataOutputStream

object ExecWire {
  fun write(out: DataOutputStream, r: ExecResult) {
    out.writeUTF("")
    out.writeInt(r.exitCode)
    out.writeInt(r.stdout.size)
    out.write(r.stdout)
    out.writeInt(r.stderr.size)
    out.write(r.stderr)
  }

  fun read(input: DataInputStream, limit: Int): ExecResult {
    val error = input.readUTF()
    if (error.isNotEmpty()) throw BridgeError(error, input.readUTF())
    val exit = input.readInt()
    var total = 0
    fun bytes(): ByteArray {
      val n = input.readInt()
      if (n < 0 || n > limit || total.toLong() + n > limit)
        throw BridgeError("OUTPUT_LIMIT_EXCEEDED", "IPC 输出超过限制")
      total += n
      return ByteArray(n).also { input.readFully(it) }
    }
    val stdout = bytes()
    val stderr = bytes()
    return ExecResult(stdout, stderr, exit)
  }
}
