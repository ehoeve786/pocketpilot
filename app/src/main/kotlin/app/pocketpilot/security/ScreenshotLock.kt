package app.pocketpilot.security

import android.content.Context
import app.pocketpilot.network.api.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the screens that show the local token and keys stay out of screenshots and the recents preview.
 * On by default. The owner can turn it off in Settings, which asks for their fingerprint or screen lock first.
 */
class ScreenshotLock(
    context: Context,
    private val secrets: SecretStore,
) {
    private val _enabled = MutableStateFlow(readOrMigrate(context))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        secrets.put(NAME, on.toString())
        _enabled.value = on
    }

    /** Kept in the Keystore-backed store, so editing a shared-preferences file cannot switch the lock off. */
    private fun readOrMigrate(context: Context): Boolean {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.contains(LEGACY_KEY)) {
            if (secrets.get(NAME) == null) secrets.put(NAME, legacy.getBoolean(LEGACY_KEY, true).toString())
            legacy.edit().remove(LEGACY_KEY).apply()
        }
        return secrets.get(NAME)?.toBooleanStrictOrNull() ?: true
    }

    private companion object {
        const val LEGACY_PREFS = "pocketpilot_privacy"
        const val LEGACY_KEY = "lock_secrets"
        const val NAME = "screenshot_lock"
    }
}
