package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorTextTest {
    @Test
    fun readsSixAndEightDigitColours() {
        assertEquals(0xFF000000.toInt(), ColorText.argb("#000000"))
        assertEquals(0xFFFFFFFF.toInt(), ColorText.argb("ffffff"))
        assertEquals(0x80FF0000.toInt(), ColorText.argb(" #80FF0000 "))
    }

    @Test
    fun refusesWhatIsNotAColour() {
        assertNull(ColorText.argb("red"))
        assertNull(ColorText.argb("#FFF"))
        assertNull(ColorText.argb("#GG0000"))
    }

    @Test
    fun formatsOpaqueColoursWithoutAlpha() {
        assertEquals("#1E88E5", ColorText.format(0xFF1E88E5.toInt()))
        assertEquals("#801E88E5", ColorText.format(0x801E88E5.toInt()))
    }

    @Test
    fun validatorRejectsABadColour() {
        val step = ActionStep(type = "overlay.text", params = mapOf("text" to "Hi", "background" to "black"))
        val rule = Rule(id = "r", name = "R", triggers = listOf(TriggerSpec("manual")), actions = listOf(step))
        assertTrue(RuleValidator.problems(rule).any { "Background colour" in it })
    }
}
