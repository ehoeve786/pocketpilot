package app.pocketpilot.automation

import app.pocketpilot.core.automation.RuleSecrets
import app.pocketpilot.network.api.SecretStore

/** The credentials in rules, kept in the Keystore-backed secret store that holds the other secrets. */
class SecretStoreRuleSecrets(
    private val store: SecretStore,
) : RuleSecrets {
    override fun get(name: String): String? = store.get(name)

    override fun put(
        name: String,
        value: String,
    ) {
        store.put(name, value)
    }

    override fun remove(name: String) {
        store.remove(name)
    }
}
