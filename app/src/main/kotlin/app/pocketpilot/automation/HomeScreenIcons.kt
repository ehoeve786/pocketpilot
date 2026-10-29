package app.pocketpilot.automation

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.pocketpilot.core.automation.TriggerEvent
import java.util.UUID
import app.pocketpilot.core.designsystem.R as DsR

/**
 * Runs the rule whose home screen icon was tapped. It shows nothing: the icon is the only UI. It is exported so the
 * launcher can start it, which means any app can start it too, so it fires only with the token that was saved
 * when the icon was pinned. A start without that token does nothing.
 */
class HomeScreenIconActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_NAME)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val expected = name?.let { getSharedPreferences(ICON_PREFS, MODE_PRIVATE).getString(it, null) }
        if (name != null && token != null && token == expected) {
            automations().onEvent(TriggerEvent("shortcut", mapOf("name" to name)))
        }
        finish()
    }

    companion object {
        const val EXTRA_NAME = "app.pocketpilot.automation.ICON_NAME"
        const val EXTRA_TOKEN = "app.pocketpilot.automation.ICON_TOKEN"
        const val ICON_PREFS = "home_screen_icons"
    }
}

/**
 * Asks the launcher to put an icon named [name] on the home screen. Tapping it fires the "Home screen
 * icon" trigger with that name. Returns false when the launcher cannot pin icons.
 */
fun Context.requestHomeScreenIcon(name: String): Boolean {
    val manager = getSystemService(ShortcutManager::class.java)
    if (!manager.isRequestPinShortcutSupported) return false
    val token = UUID.randomUUID().toString()
    getSharedPreferences(HomeScreenIconActivity.ICON_PREFS, Context.MODE_PRIVATE).edit().putString(name, token).apply()
    val info =
        ShortcutInfo
            .Builder(this, "icon:$name")
            .setShortLabel(name)
            .setIcon(Icon.createWithResource(this, DsR.drawable.core_designsystem_ic_bolt))
            .setIntent(
                Intent(this, HomeScreenIconActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(HomeScreenIconActivity.EXTRA_NAME, name)
                    .putExtra(HomeScreenIconActivity.EXTRA_TOKEN, token),
            ).build()
    return manager.requestPinShortcut(info, null)
}
