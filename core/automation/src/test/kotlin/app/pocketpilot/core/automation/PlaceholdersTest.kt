package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholdersTest {
    @Test
    fun `fills keys that hold a hyphen, such as a React Native test id`() {
        val values = mapOf("screen_read.copy-address-button" to "603 Dunsmuir Street, Vancouver")

        assertEquals(
            "Go to 603 Dunsmuir Street, Vancouver",
            Placeholders.fill("Go to {screen_read.copy-address-button}") { values[it] },
        )
    }

    @Test
    fun `an unknown name with a hyphen stays as written`() {
        assertEquals("{missing-key}", Placeholders.fill("{missing-key}") { null })
    }
}
