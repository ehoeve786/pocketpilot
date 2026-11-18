package app.pocketpilot.network.ssh

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class SshSecretNamesTest {
    // KeystoreSecretStore refuses any other name.
    private val allowed = Regex("[a-z0-9_]+")

    @Test
    fun `secret names are ones the secret store accepts`() {
        assertTrue(allowed.matches(SshServers.KEY))
        assertTrue(allowed.matches(SshServers.passwordKey(UUID.randomUUID().toString())))
    }
}
