package app.pocketpilot.network.ssh

/** Finding a server's address when the owner gave its MAC address, as for a device on the phone's hotspot. */
object SshAddress {
    private val MAC = Regex("""^[0-9a-f]{2}(:[0-9a-f]{2}){5}$""")
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** States in which `ip neigh` lists an address it could not reach, so it is no use. */
    private val UNREACHABLE = setOf("FAILED", "INCOMPLETE")

    /** [mac] in lower case with colons, or null when it is not a MAC address. `AA-BB-...` is accepted too. */
    fun normalizeMac(mac: String): String? =
        mac
            .trim()
            .lowercase()
            .replace('-', ':')
            .takeIf { MAC.matches(it) }

    /**
     * The address `ip neigh` output lists for [mac], preferring IPv4 and then a reachable entry; null when it has
     * none. Lines look like `192.168.43.20 dev wlan1 lladdr aa:bb:cc:dd:ee:ff REACHABLE`.
     */
    fun fromNeighbors(
        output: String,
        mac: String,
    ): String? {
        val wanted = normalizeMac(mac) ?: return null
        return output
            .lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { fields ->
                val at = fields.indexOf("lladdr")
                at > 0 && fields.getOrNull(at + 1)?.lowercase() == wanted && fields.last() !in UNREACHABLE
            }.map { it.first() }
            // Link-local IPv6 addresses need a zone, which the dial does not carry.
            .filterNot { it.startsWith("fe80:", ignoreCase = true) }
            .sortedBy { if (IPV4.matches(it)) 0 else 1 }
            .firstOrNull()
    }
}
