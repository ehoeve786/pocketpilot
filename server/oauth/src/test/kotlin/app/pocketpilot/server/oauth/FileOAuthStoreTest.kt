package app.pocketpilot.server.oauth

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileOAuthStoreTest {
    @Test
    fun `a saved state loads back, and a second save replaces the first without leaving the temp file`() {
        val dir = Files.createTempDirectory("oauth-store").toFile()
        val file = dir.resolve("oauth.json")
        val store = FileOAuthStore(file)

        store.save(OAuthState(clients = listOf(OAuthClient("c1", "Claude", listOf("https://example.com/cb"), 0))))
        store.save(OAuthState(clients = listOf(OAuthClient("c2", "Other", listOf("https://example.com/cb"), 0))))

        assertEquals(listOf("c2"), FileOAuthStore(file).load().clients.map { it.id })
        assertTrue(!dir.resolve("oauth.json.tmp").exists())
    }
}
