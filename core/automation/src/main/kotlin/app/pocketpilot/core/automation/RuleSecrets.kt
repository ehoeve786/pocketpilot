package app.pocketpilot.core.automation

/**
 * Where the values of credential-looking settings are kept, so the rules file holds only references to them. The
 * app keeps these in its Keystore-backed secret store.
 */
interface RuleSecrets {
    fun get(name: String): String?

    fun put(
        name: String,
        value: String,
    )

    fun remove(name: String)
}

/** The setting names that hold a credential: HTTP headers, tokens, passwords, cookies and API keys. */
internal val CREDENTIAL_WORDS = listOf("header", "auth", "token", "secret", "password", "cookie", "apikey", "api_key")

internal fun isCredentialKey(key: String): Boolean = CREDENTIAL_WORDS.any { key.lowercase().contains(it) }
