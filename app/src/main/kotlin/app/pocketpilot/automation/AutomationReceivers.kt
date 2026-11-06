package app.pocketpilot.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.pocketpilot.R
import app.pocketpilot.core.automation.TriggerEvent
import app.pocketpilot.network.api.SecretStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import app.pocketpilot.core.designsystem.R as DsR

/** Reaches the runtime from receivers and tiles, which Android creates on its own. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutomationEntryPoint {
    fun automationRuntime(): AutomationRuntime

    fun secretStore(): SecretStore

    fun placeAlerts(): PlaceAlerts
}

internal fun Context.automations(): AutomationRuntime =
    EntryPointAccessors.fromApplication(applicationContext, AutomationEntryPoint::class.java).automationRuntime()

internal fun Context.secretStore(): SecretStore =
    EntryPointAccessors.fromApplication(applicationContext, AutomationEntryPoint::class.java).secretStore()

/** A timed rule's alarm. */
class AutomationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val ruleId = intent.getStringExtra(EXTRA_RULE) ?: return
        context.automations().onAlarm(ruleId, intent.getIntExtra(EXTRA_INDEX, 0))
    }

    companion object {
        const val EXTRA_RULE = "rule"
        const val EXTRA_INDEX = "index"
    }
}

/** A place alert from [PlaceWatcher]: the phone entered or left one of the rules' places. */
class PlaceReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val place = intent.getStringExtra(EXTRA_PLACE) ?: return
        val alerts = EntryPointAccessors.fromApplication(context.applicationContext, AutomationEntryPoint::class.java).placeAlerts()
        val entering = alerts.entering(intent) ?: return
        context.automations().onPlace(place, entering)
    }

    companion object {
        const val EXTRA_PLACE = "place"
    }
}

/** The next start or end of a calendar event that a rule waits for. */
class CalendarAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) = context.automations().onCalendarAlarm()
}

/**
 * The "Event received" trigger: a sender (Tasker, MacroDroid, `am broadcast`) sends `app.pocketpilot.action.EVENT`
 * with the extras `key` (the event key from Settings) and `event`; other string extras become the trigger's
 * variables. Without the right key the broadcast is ignored, since any app can send one. It only starts rules
 * the owner made for that event name.
 */
class AutomationEventReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_EVENT) return
        // Another app's bundle can hold classes this app does not have; reading it then throws, which would crash
        // the whole process. size() reads it here, where that can be caught.
        val extras = runCatching { intent.extras?.apply { size() } }.getOrNull() ?: return
        if (!EventKey.matches(EventKey.get(context.secretStore()), extras.getString(KEY_EXTRA))) return
        val event = extras.getString("event")?.takeIf { it.isNotBlank() } ?: return
        val data =
            extras
                .keySet()
                .filterNot { it == KEY_EXTRA }
                .take(MAX_EXTRAS)
                .mapNotNull { key -> extras.getString(key)?.let { key to it.take(MAX_VALUE) } }
                .toMap()
        context.automations().onEvent(TriggerEvent("intent.received", data + ("event" to event)))
    }

    private companion object {
        const val ACTION_EVENT = "app.pocketpilot.action.EVENT"
        const val KEY_EXTRA = "key"
        const val MAX_EXTRAS = 20
        const val MAX_VALUE = 2_000
    }
}

/** A Quick Settings tile that fires the "Quick Settings tile" trigger for its slot, labelled with its rule. */
abstract class RuleTileService(
    private val slot: Int,
) : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val rule = applicationContext.automations().tileRule(slot)
        tile.label = rule?.name ?: getString(R.string.automation_tile, slot)
        tile.icon = Icon.createWithResource(this, DsR.drawable.core_designsystem_ic_bolt)
        tile.state = if (rule?.enabled == true) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        applicationContext.automations().onEvent(TriggerEvent("tile", mapOf("slot" to slot.toString())))
    }
}

class RuleTile1 : RuleTileService(1)

class RuleTile2 : RuleTileService(2)

class RuleTile3 : RuleTileService(3)
