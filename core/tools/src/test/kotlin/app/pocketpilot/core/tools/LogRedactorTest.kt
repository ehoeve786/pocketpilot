package app.pocketpilot.core.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class LogRedactorTest {
    @Test
    fun `a Wi-Fi network name is masked in each form dumps print it`() {
        assertEquals("SSID: [network],", LogRedactor.redact("SSID: \"Home Net\","))
        assertEquals("\"SSID\":[network]", LogRedactor.redact("\"SSID\":\"Home\""))
        assertEquals("SSID [network]", LogRedactor.redact("SSID Home"))
        assertEquals("ssid=[network]", LogRedactor.redact("ssid=Cafe"))
    }

    @Test
    fun `a JSON web token is masked`() {
        assertEquals("token [masked]", LogRedactor.redact("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln"))
    }
}
