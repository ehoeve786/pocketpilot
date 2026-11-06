package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppsOfTest {
    @Test
    fun collectsTheNamedAppsInLowerCase() {
        assertEquals(
            setOf("com.delco.courier", "com.ubercab.driver"),
            appsOf(listOf("com.delco.courier", " Com.UberCab.Driver , com.delco.courier", "com.delco.courier,")),
        )
    }

    @Test
    fun aTriggerForAnyAppMeansEveryApp() {
        assertNull(appsOf(listOf("com.delco.courier", null)))
        assertNull(appsOf(listOf("com.delco.courier", "")))
        assertNull(appsOf(listOf(" , ")))
    }

    @Test
    fun agreesWithHowTriggersMatchApps() {
        val apps = appsOf(listOf("com.delco.courier"))!!
        listOf("com.delco.courier", "COM.DELCO.COURIER", "com.ubercab.driver").forEach { pkg ->
            assertEquals(anyOf("com.delco.courier", pkg), pkg.lowercase() in apps)
        }
        assertTrue(appsOf(emptyList())!!.isEmpty())
    }
}
