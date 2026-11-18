package app.pocketpilot.network.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SshAddressTest {
    private val neighbors =
        """
        192.168.43.1 dev wlan0 lladdr 11:22:33:44:55:66 STALE
        fe80::1c2d:3eff:fe4f:5a6b dev swlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
        2001:db8::20 dev swlan0 lladdr aa:bb:cc:dd:ee:ff STALE
        192.168.130.25 dev swlan0 lladdr aa:bb:cc:dd:ee:ff STALE
        192.168.130.31 dev swlan0 lladdr 99:88:77:66:55:44 FAILED
        192.168.130.40 dev swlan0  FAILED
        """.trimIndent()

    @Test
    fun `finds the IPv4 address of a MAC address`() {
        assertEquals("192.168.130.25", SshAddress.fromNeighbors(neighbors, "AA-BB-CC-DD-EE-FF"))
    }

    @Test
    fun `skips entries that could not be reached`() {
        assertNull(SshAddress.fromNeighbors(neighbors, "99:88:77:66:55:44"))
    }

    @Test
    fun `uses a global IPv6 address when there is no IPv4 one`() {
        val v6 = neighbors.lines().filterNot { it.startsWith("192.168.130.25") }.joinToString("\n")
        assertEquals("2001:db8::20", SshAddress.fromNeighbors(v6, "aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `rejects text that is not a MAC address`() {
        assertNull(SshAddress.normalizeMac("comma"))
        assertEquals("aa:bb:cc:dd:ee:ff", SshAddress.normalizeMac(" AA:BB:CC:DD:EE:FF "))
    }
}
