package dev.agentbridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.NetworkInterface

/** The VPN flag comes from Android NetworkCapabilities, never an address/interface-name guess. */
data class NetworkAddressSnapshot(
  val isVpn: Boolean,
  val interfaceName: String? = null,
  val linkAddresses: List<String> = emptyList(),
  val interfaceAddresses: List<String> = emptyList(),
)

data class VpnAddresses(
  val vpnDetected: Boolean = false,
  val addresses: List<String> = emptyList(),
  val error: String? = null,
) {
  val tailscaleCandidates
    get() = addresses.filter { ConnectionSettings.isTailscaleAddress(it) }

  val preferredListenIpv4
    get() = tailscaleCandidates.firstOrNull { ConnectionSettings.validIpv4(it) }

  val explanation
    get() =
      error
        ?: when {
          !vpnDetected -> "未检测到当前应用可见的 VPN。请先开启官方 Tailscale，再刷新。"
          addresses.isEmpty() -> "检测到 VPN，但系统没有公开其接口地址。请以官方 Tailscale App 中显示的 IP 为准。"
          else -> "只显示 Android 标记为 VPN 的接口地址，不包含普通 Wi-Fi 或移动网络地址。"
        }
}

object VpnAddressPolicy {
  fun select(networks: List<NetworkAddressSnapshot>): VpnAddresses {
    val vpns = networks.filter { it.isVpn }
    val addresses =
      vpns
        .flatMap { network ->
          val links = network.linkAddresses.mapNotNull(::usableIp)
          if (links.isNotEmpty()) links else network.interfaceAddresses.mapNotNull(::usableIp)
        }
        .distinct()
        .sortedWith(
          compareByDescending<String> { ConnectionSettings.isTailscaleAddress(it) }.thenBy { it }
        )
    return VpnAddresses(vpns.isNotEmpty(), addresses)
  }

  private fun usableIp(value: String): String? {
    val literal = value.substringBefore('%')
    if (!ConnectionSettings.validIpv4(literal) && !ConnectionSettings.validIpv6(literal))
      return null
    // Input is already validated as a numeric literal: no DNS/network lookup is performed.
    val ip = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return null
    if (
      ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isAnyLocalAddress || ip.isMulticastAddress
    )
      return null
    return ip.hostAddress?.substringBefore('%')
  }
}

object VpnAddressReader {
  fun read(context: Context): VpnAddresses =
    try {
      val manager = context.getSystemService(ConnectivityManager::class.java)
      val snapshots =
        manager.allNetworks.mapNotNull { network ->
          val caps =
            runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
              ?: return@mapNotNull null
          if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
          val link = runCatching { manager.getLinkProperties(network) }.getOrNull()
          val name = link?.interfaceName
          val addresses = link?.linkAddresses?.mapNotNull { it.address.hostAddress } ?: emptyList()
          // Fallback is restricted to this proven VPN's exact interface, never all tun*/100.*
          // interfaces.
          val fallback =
            if (name != null)
              runCatching {
                  val iface = NetworkInterface.getByName(name)
                  if (iface != null && iface.isUp)
                    iface.inetAddresses.toList().mapNotNull { it.hostAddress }
                  else emptyList()
                }
                .getOrDefault(emptyList())
            else emptyList()
          NetworkAddressSnapshot(true, name, addresses, fallback)
        }
      VpnAddressPolicy.select(snapshots)
    } catch (e: Exception) {
      VpnAddresses(error = "系统限制了 VPN 信息读取，请在官方 Tailscale App 中确认 IP。")
    }
}
