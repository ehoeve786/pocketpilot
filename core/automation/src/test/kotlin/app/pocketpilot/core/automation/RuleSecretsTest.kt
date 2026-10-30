package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleSecretsTest {
    private class Vault : RuleSecrets {
        val values = mutableMapOf<String, String>()

        override fun get(name: String): String? = values[name]

        override fun put(
            name: String,
            value: String,
        ) {
            values[name] = value
        }

        override fun remove(name: String) {
            values.remove(name)
        }
    }

    private class Disk : Persistence {
        var text: String? = null

        override fun load(): String? = text

        override fun save(text: String) {
            this.text = text
        }
    }

    private fun rule(headers: String) =
        Rule(
            id = "r1",
            name = "Send",
            triggers = listOf(TriggerSpec("manual")),
            actions = listOf(ActionStep("http.request", mapOf("url" to "https://example.com", "headers" to headers))),
        )

    @Test
    fun `the rules file holds no credential, but the rule still has it`() {
        val disk = Disk()
        val store = RuleStore(disk, secrets = Vault())
        store.upsert(rule("Authorization: Bearer abc123"))
        assertFalse(disk.text!!.contains("abc123"))
        assertEquals(
            "Authorization: Bearer abc123",
            store
                .rule("r1")!!
                .actions
                .single()
                .params["headers"],
        )
    }

    @Test
    fun `a rule loaded from the file gets its credential back`() {
        val disk = Disk()
        val vault = Vault()
        RuleStore(disk, secrets = vault).upsert(rule("Authorization: Bearer abc123"))
        val reloaded = RuleStore(disk, secrets = vault)
        assertEquals(
            "Authorization: Bearer abc123",
            reloaded
                .rule("r1")!!
                .actions
                .single()
                .params["headers"],
        )
    }

    @Test
    fun `deleting a rule removes its credential from the vault`() {
        val disk = Disk()
        val vault = Vault()
        val store = RuleStore(disk, secrets = vault)
        store.upsert(rule("Authorization: Bearer abc123"))
        store.delete("r1")
        assertTrue(vault.values.isEmpty())
        assertNull(store.rule("r1"))
    }

    @Test
    fun `a secret-named variable is kept in the vault, not the rules file`() {
        val disk = Disk()
        val vault = Vault()
        val store = RuleStore(disk, secrets = vault)
        store.setVariable("apiToken", "tok-987")
        assertFalse(disk.text!!.contains("tok-987"))
        assertEquals("tok-987", store.data.value.variables["apiToken"])
        assertEquals("tok-987", RuleStore(disk, secrets = vault).data.value.variables["apiToken"])
        store.setVariable("apiToken", null)
        assertTrue(vault.values.isEmpty())
    }

    @Test
    fun `a secret-named variable of a rule is kept in the vault too`() {
        val disk = Disk()
        val store = RuleStore(disk, secrets = Vault())
        store.upsert(rule("Accept: text/plain").copy(variables = mapOf("password" to "pw-555", "city" to "Oslo")))
        assertFalse(disk.text!!.contains("pw-555"))
        assertTrue(disk.text!!.contains("Oslo"))
        assertEquals("pw-555", store.rule("r1")!!.variables["password"])
    }

    @Test
    fun `run history keeps no secret-named variable`() {
        val disk = Disk()
        val store = RuleStore(disk, secrets = Vault())
        store.addRun(
            RunRecord(
                id = "run1",
                ruleId = "r1",
                ruleName = "Send",
                reason = "Test run",
                startedAtMillis = 1L,
                endedAtMillis = 2L,
                outcome = RunOutcome.OK,
                variables = mapOf("authToken" to "tok-111", "count" to "3"),
            ),
        )
        assertEquals(
            mapOf("count" to "3"),
            store.data.value.runs
                .single()
                .variables,
        )
        assertFalse(disk.text!!.contains("tok-111"))
    }

    /** A vault that takes only the names the Keystore-backed store accepts. */
    private class StrictVault : RuleSecrets {
        private val inner = Vault()
        private val name = Regex("[a-z0-9_]+")

        override fun get(name: String): String? = inner.get(name)

        override fun put(
            name: String,
            value: String,
        ) {
            require(this.name.matches(name)) { "Bad secret name: $name" }
            inner.put(name, value)
        }

        override fun remove(name: String) = inner.remove(name)
    }

    @Test
    fun `secret names of rules with uppercase ULID ids are accepted by a strict vault`() {
        val store = RuleStore(Disk(), secrets = StrictVault())
        store.upsert(
            Rule(
                id = "01HXK3M9Q2ABCDEFGHJKMNPQRS",
                name = "Send",
                triggers = listOf(TriggerSpec("manual")),
                actions = listOf(ActionStep("http.request", mapOf("headers" to "Authorization: Bearer abc123"))),
            ).copy(variables = mapOf("password" to "pw-555")),
        )
        store.setVariable("apiToken", "tok-987")
        assertEquals("pw-555", store.rule("01HXK3M9Q2ABCDEFGHJKMNPQRS")!!.variables["password"])
        assertEquals("tok-987", store.data.value.variables["apiToken"])
    }

    @Test
    fun `without a vault the value stays in the file, as a plain store keeps it`() {
        val disk = Disk()
        RuleStore(disk).upsert(rule("Authorization: Bearer abc123"))
        assertTrue(disk.text!!.contains("abc123"))
    }
}
