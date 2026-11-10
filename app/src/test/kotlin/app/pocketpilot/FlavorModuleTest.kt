package app.pocketpilot

import app.pocketpilot.core.model.PolicyProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class FlavorModuleTest {
    @Test
    fun `the app binds the oss policy profile`() {
        assertEquals(PolicyProfile.OSS, FlavorModule.policyProfile())
    }
}
