package app.pocketpilot.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScopeTest {
    @Test
    fun `parses an OAuth scope string`() {
        assertEquals(setOf(Scope.SCREEN_READ, Scope.UI_INTERACT), Scope.parseList("screen:read  ui:interact"))
    }

    @Test
    fun `splits namespace and level`() {
        assertEquals("apps", Scope.APPS_CONTROL.namespace)
        assertEquals("control", Scope.APPS_CONTROL.level)
    }

    @Test
    fun `rejects malformed scopes`() {
        assertFailsWith<IllegalArgumentException> { Scope("screen") }
        assertFailsWith<IllegalArgumentException> { Scope("Screen:Read") }
        assertFailsWith<IllegalArgumentException> { Scope("screen:read:extra") }
    }

    @Test
    fun `known set covers every scope in the spec`() {
        assertEquals(16, Scope.KNOWN.size)
        assertTrue(Scope.HOME_READ in Scope.KNOWN && Scope.HOME_CONTROL in Scope.KNOWN)
    }
}
