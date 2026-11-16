package app.pocketpilot.capability.shizuku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PairingInputTest {
    @Test
    fun `a code alone has no port`() {
        assertEquals("246994" to null, PairingInput.parse(" 246994 "))
    }

    @Test
    fun `a port typed after or before the code is taken`() {
        assertEquals("246994" to 37611, PairingInput.parse("246994 37611"))
        assertEquals("246994" to 37611, PairingInput.parse("37611 246994"))
    }

    @Test
    fun `the address and port as shown in the dialog work`() {
        assertEquals("246994" to 37611, PairingInput.parse("246994 192.168.8.100:37611"))
    }

    @Test
    fun `no six-digit code is refused`() {
        assertFailsWith<IllegalArgumentException> { PairingInput.parse("37611") }
    }
}
