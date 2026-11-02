package starx.dev.agentbridge

data class RuntimeSnapshot(
  val running: Boolean = false,
  val phase: String = "stopped",
  val listener: String = "未启动",
  val backend: String = "root",
  val backendStatus: String = "尚未授权",
  val rootStatus: String = "尚未授权",
  val shizukuStatus: String = "尚未连接",
  val session: String? = null,
  val error: String = "",
  val backendUid: Int = -1,
  val backendConnected: Boolean = false,
)

class IdentityCache<T> {
  private val entries = mutableMapOf<String, T>()

  @Synchronized fun get(key: String, create: () -> T): T = entries.getOrPut(key, create)

  @Synchronized
  fun invalidate(key: String, after: () -> Unit = {}) {
    entries.remove(key)
    after()
  }
}

data class ConnectionSettings(
  val bindHost: String,
  val port: Int,
  val plain: Boolean = false,
  val extraNames: String = "",
  val detectedAddresses: List<String> = emptyList(),
) {
  fun validate() {
    require(port in 1024..65535) { "端口需在 1024–65535 之间" }
    require(bindHost == "::" || bindHost.matches(Regex("[0-9a-fA-F:.]+"))) {
      "监听地址需填写本机 IP；域名可在高级证书设置中补充"
    }
    require(if (bindHost.contains(':')) validIpv6(bindHost) else validIpv4(bindHost)) {
      "监听 IPv4 地址无效"
    }
    require(plain || names().all { validHost(it) }) { "证书附加名称无效，多个名称使用英文逗号分隔" }
  }

  fun names(): List<String> =
    (listOf("localhost", "127.0.0.1") +
        detectedAddresses.filter { isTailscaleAddress(it) } +
        listOf(bindHost).filter { it != "0.0.0.0" && it != "::" } +
        extraNames.split(','))
      .map { it.trim().removeSurrounding("[", "]") }
      .filter { it.isNotEmpty() }
      .distinct()
      .sorted()

  fun controllerUrls(): List<String> {
    val hosts =
      if (wildcard)
        (detectedAddresses.filter { isTailscaleAddress(it) } +
          extraNames
            .split(',')
            .map { it.trim() }
            .filter { validHost(it) && it !in setOf("localhost", "127.0.0.1", "::1") })
      else listOf(bindHost)
    return hosts.distinct().map { "${if(plain)"http"else "https"}://${urlHost(it)}:$port" }
  }

  val wildcard
    get() = bindHost == "0.0.0.0" || bindHost == "::"

  companion object {
    fun isTailscaleAddress(value: String): Boolean {
      if (value.startsWith("fd7a:115c:a1e0:", ignoreCase = true)) return validIpv6(value)
      val octets = value.split('.')
      return validIpv4(value) && octets[0] == "100" && (octets[1].toInt() in 64..127)
    }

    fun urlHost(host: String) =
      if (host.contains(':')) "[${host.removeSurrounding("[","]")}]" else host

    fun validIpv4(value: String): Boolean {
      val p = value.split('.')
      return p.size == 4 &&
        p.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 }
    }

    fun validIpv6(value: String): Boolean {
      if (!value.matches(Regex("[0-9a-fA-F:.]+")) || value.count { it == ':' } < 2) return false
      val compressed = value.contains("::")
      if (compressed && value.indexOf("::") != value.lastIndexOf("::")) return false
      if (
        (value.startsWith(":") && !value.startsWith("::")) ||
          (value.endsWith(":") && !value.endsWith("::"))
      )
        return false
      val groups = value.split(':').filter { it.isNotEmpty() }
      var width = 0
      for ((index, g) in groups.withIndex()) {
        if (g.contains('.')) {
          if (index != groups.lastIndex || !validIpv4(g)) return false
          width += 2
        } else {
          if (!g.matches(Regex("[0-9a-fA-F]{1,4}"))) return false
          width++
        }
      }
      return if (compressed) width < 8 else width == 8
    }

    fun validHost(value: String): Boolean {
      if (
        value.isEmpty() ||
          value.length > 253 ||
          value.any { it.isWhitespace() } ||
          value.contains('/') ||
          value.contains('@')
      )
        return false
      val h = value.removeSurrounding("[", "]")
      if (h.contains(':')) return validIpv6(h)
      if (h.all { it.isDigit() || it == '.' }) return validIpv4(h)
      return h.split('.').all {
        it.isNotEmpty() &&
          it.length <= 63 &&
          it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
      }
    }
  }
}
