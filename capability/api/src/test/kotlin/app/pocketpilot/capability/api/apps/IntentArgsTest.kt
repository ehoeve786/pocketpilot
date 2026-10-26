package app.pocketpilot.capability.api.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IntentArgsTest {
    @Test
    fun `builds am arguments for a component with extras`() {
        val args =
            IntentArgs.build(
                component = "com.example.clock/.StopwatchActivity",
                packageName = null,
                action = "com.example.action.STOPWATCH",
                data = null,
                mimeType = null,
                categories = listOf("android.intent.category.DEFAULT"),
                extras = listOf(IntentExtra("start", "bool", "true"), IntentExtra("label", "string", "Run 5 k")),
            )
        assertEquals(
            listOf(
                "-n",
                "com.example.clock/.StopwatchActivity",
                "-a",
                "com.example.action.STOPWATCH",
                "-c",
                "android.intent.category.DEFAULT",
                "--ez",
                "start",
                "true",
                "--es",
                "label",
                "Run 5 k",
            ),
            args,
        )
        assertEquals("com.example.clock", IntentArgs.targetPackage(args))
    }

    @Test
    fun `a bare class name joins the app given beside it`() {
        val args =
            IntentArgs.build(
                component = "com.tesla.share.ShareActivity",
                packageName = "com.teslamotors.tesla",
                action = "android.intent.action.SEND",
                data = null,
                mimeType = "text/plain",
                categories = emptyList(),
                extras = listOf(IntentExtra("android.intent.extra.TEXT", "string", "22 East Cordova Street, Vancouver")),
            )
        assertEquals(listOf("-n", "com.teslamotors.tesla/com.tesla.share.ShareActivity"), args.take(2))
        assertEquals("com.teslamotors.tesla", IntentArgs.targetPackage(args))
        // A second share replaces the share screen left open by the first, so the new text arrives.
        assertEquals(listOf("-a", "android.intent.action.SEND", "--activity-clear-task", "-t", "text/plain"), args.subList(2, 7))
    }

    @Test
    fun `an extra value cannot smuggle in another target`() {
        val args = IntentArgs.build(null, "com.example.app", null, null, null, emptyList(), listOf(IntentExtra("x", "string", "-n")))
        assertEquals("com.example.app", IntentArgs.targetPackage(args))
    }

    @Test
    fun `intents without an app, to protected apps or with dangerous actions are refused`() {
        assertFailsWith<IllegalArgumentException> { IntentArgs.check(listOf("-a", "android.intent.action.VIEW", "-d", "https://x")) }
        assertFailsWith<IllegalArgumentException> {
            IntentArgs.check(
                listOf("-p", "com.android.phone", "-a", "android.intent.action.CALL"),
            )
        }
        assertFailsWith<IllegalArgumentException> { IntentArgs.check(listOf("-p", "moe.shizuku.privileged.api")) }
        assertFailsWith<IllegalArgumentException> { IntentArgs.check(listOf("-p", "com.example.app", "--user", "10")) }
        assertFailsWith<IllegalArgumentException> { IntentArgs.check(listOf("-p", "com.example.app", "-t", "not a type")) }
    }

    @Test
    fun `data that reaches files or starts components is refused, app links are not`() {
        listOf("file:///data/data/x", "content://com.example/file", "intent://scan#Intent;end", "android-app://x")
            .forEach { data ->
                assertFailsWith<IllegalArgumentException>(data) {
                    IntentArgs.check(listOf("-p", "com.example.app", "-d", data))
                }
            }
        IntentArgs.check(listOf("-p", "com.example.app", "-d", "spotify:track:1"))
        IntentArgs.check(listOf("-p", "com.example.app", "-d", "https://example.com"))
    }
}
