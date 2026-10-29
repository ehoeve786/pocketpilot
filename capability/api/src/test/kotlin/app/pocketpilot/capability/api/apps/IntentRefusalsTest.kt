package app.pocketpilot.capability.api.apps

import kotlin.test.Test
import kotlin.test.assertFailsWith

class IntentRefusalsTest {
    @Test
    fun `send-to and call actions are refused`() {
        assertFailsWith<IllegalArgumentException> {
            IntentArgs.check(listOf("-p", "com.example.app", "-a", "android.intent.action.SENDTO"))
        }
        assertFailsWith<IllegalArgumentException> {
            IntentArgs.check(listOf("-p", "com.example.app", "-a", "android.intent.action.CALL_BUTTON"))
        }
    }

    @Test
    fun `PocketPilot's own components are refused whatever the flag`() {
        assertFailsWith<IllegalArgumentException> {
            IntentArgs.check(listOf("-n", "app.pocketpilot/.MainActivity"))
        }
        assertFailsWith<IllegalArgumentException> {
            IntentArgs.check(listOf("-p", "app.pocketpilot.oss"))
        }
    }
}
