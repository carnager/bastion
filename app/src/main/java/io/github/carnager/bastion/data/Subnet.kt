package io.github.carnager.bastion.data

import java.net.InetAddress
import java.net.NetworkInterface

/** An IPv4 or IPv6 network such as "192.168.1.0/24". */
class Subnet private constructor(private val network: ByteArray, val prefix: Int) {
    operator fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false
        return masked(bytes, prefix).contentEquals(network)
    }

    override fun toString(): String = "${InetAddress.getByAddress(network).hostAddress}/$prefix"

    companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** "192.168.1.0/24", "fd00::/8", or a single address; null for anything else (never a DNS lookup). */
        fun parse(text: String): Subnet? {
            val parts = text.trim().split('/')
            if (parts.size > 2 || parts[0].isEmpty()) return null
            val literal = parts[0]
            if (!IPV4.matches(literal) && ':' !in literal) return null  // a name: getByName would look it up
            if (IPV4.matches(literal) && literal.split('.').any { it.toInt() > 255 }) return null
            val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return null
            val bits = address.address.size * 8
            val prefix = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else bits
            if (prefix !in 0..bits) return null
            return Subnet(masked(address.address, prefix), prefix)
        }

        /** The network [address] belongs to with [prefix] bits, e.g. 192.168.1.23 and 24 → 192.168.1.0/24. */
        fun of(address: InetAddress, prefix: Int): Subnet = Subnet(masked(address.address, prefix), prefix)

        private fun masked(bytes: ByteArray, prefix: Int): ByteArray = ByteArray(bytes.size) { i ->
            val keep = (prefix - i * 8).coerceIn(0, 8)
            (bytes[i].toInt() and (0xFF shl (8 - keep))).toByte()
        }
    }
}

/**
 * The phone's own addresses with their prefix lengths on the networks it is really in (Wi-Fi, also
 * while a VPN is on). VPN tunnels are point-to-point and left out: being in one says nothing about where the phone is.
 */
fun localAddresses(): List<Pair<InetAddress, Int>> = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
        .flatMap { nic -> nic.interfaceAddresses.map { it.address to it.networkPrefixLength.toInt() } }
}.getOrDefault(emptyList())

/** The private IPv4 network the phone is in (home Wi-Fi), to offer as a host's direct network. */
fun currentPrivateSubnet(addresses: List<Pair<InetAddress, Int>> = localAddresses()): Subnet? =
    addresses.firstOrNull { (address, prefix) -> address.address.size == 4 && address.isSiteLocalAddress && prefix in 8..30 }
        ?.let { (address, prefix) -> Subnet.of(address, prefix) }
