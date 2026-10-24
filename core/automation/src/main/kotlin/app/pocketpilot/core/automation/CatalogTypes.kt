package app.pocketpilot.core.automation

import app.pocketpilot.core.model.RiskTier
import kotlinx.serialization.json.JsonObject
import java.time.ZonedDateTime

/** How the builder edits a setting. */
enum class FieldKind {
    TEXT,
    MULTILINE,
    NUMBER,
    BOOLEAN,

    /** One of [FieldDef.choices]. */
    CHOICE,

    /** One of [FieldDef.choices], shown as a grid of icons in the tabs named by [Choice.tab]. */
    ICON,

    /** "HH:mm", 24-hour. */
    TIME,

    /** A number from 0 to 100, set with a slider. */
    PERCENT,

    /** Weekdays as "mon,tue,wed"; empty means every day. */
    DAYS,

    /** Package names, comma separated; empty means any app. */
    APP,

    /** Another rule's id. */
    RULE,

    /** A tool name from the server's registry. */
    TOOL,

    /** A JSON object, such as a tool's arguments. */
    JSON,
}

data class Choice(
    val value: String,
    val label: String,
    /** The tab an icon choice is listed under; null for the first tab. */
    val tab: String? = null,
)

/** One setting of a trigger, constraint or action. */
data class FieldDef(
    val key: String,
    val label: String,
    val kind: FieldKind = FieldKind.TEXT,
    val required: Boolean = true,
    val default: String? = null,
    val choices: List<Choice> = emptyList(),
    val hint: String? = null,
    /** For an APP field: pick one app, stored as its package name, not a list. */
    val single: Boolean = false,
    /** For a text field: offer a list of values from this source, and still accept typing. */
    val source: PickSource? = null,
    /** For a text field: offer a file browser to choose a path, and still accept typing. */
    val browse: Boolean = false,
)

/** Where a text field's list of suggested values comes from. */
enum class PickSource {
    VARIABLE,
    BLUETOOTH_DEVICE,
    WIFI_NETWORK,
    HA_ENTITY,
    HA_SERVICE,
    CALENDAR,
    INTENT_COMPONENT,
    INTENT_ACTION,

    /** The cell towers the phone sees now. */
    CELL_TOWER,

    /** The triggers of the rule being edited. */
    RULE_TRIGGER,
    ;

    /** Bluetooth devices, Wi-Fi networks and cell towers can be several, stored comma-separated. */
    val multiple: Boolean get() = this == BLUETOOTH_DEVICE || this == WIFI_NETWORK || this == CELL_TOWER
}

/** One suggested value: [value] is stored, [label] and [detail] are shown in the list. */
data class PickOption(
    val value: String,
    val label: String,
    val detail: String? = null,
)

/** Groups for the builder's pickers. */
enum class Category(
    val title: String,
) {
    TIME("Time"),
    DEVICE("Device"),
    BATTERY("Battery and power"),
    CONNECTIVITY("Connectivity"),
    APPS("Apps"),
    NOTIFICATIONS("Notifications"),
    SCREEN("Screen and UI"),
    SETTINGS("Settings"),
    SOUND("Sound"),
    MEDIA("Media"),
    LOCATION("Location"),
    CALLS("Calls and messages"),
    SENSORS("Sensors"),
    CALENDAR("Calendar"),
    HOME("Home Assistant"),
    ALARMS("Alarms and timers"),
    FILES("Files and data"),
    FLOW("Flow"),
    VARIABLES("Variables"),
    AI("AI"),
    POCKETPILOT("PocketPilot"),
    MANUAL("Manual and other rules"),
}

/** How PocketPilot learns about a trigger or checks a constraint, shown in the pickers. */
enum class Source(
    val label: String,
) {
    SYSTEM("System"),
    SHIZUKU("Shizuku"),
    ACCESSIBILITY("Accessibility"),
    NOTIFICATIONS("Notification access"),
    POCKETPILOT("PocketPilot"),
    NET("Internet"),
}

/** Something that starts a rule. */
class TriggerDef(
    val type: String,
    val category: Category,
    val title: String,
    val description: String,
    val fields: List<FieldDef> = emptyList(),
    val source: Source = Source.SYSTEM,
    /** Variables the trigger sets, without the `trigger.` prefix. */
    val variables: List<String> = emptyList(),
    /** Fired by its own schedule for one rule, never matched against events. */
    val scheduled: Boolean = false,
    /** Sideload builds only. */
    val sideloadOnly: Boolean = false,
    val sentence: (Map<String, String>) -> String = { title },
    /** True when [event] should start a rule with these settings; [previous] is the same type's last event. */
    val match: (params: Map<String, String>, event: Map<String, String>, previous: Map<String, String>?) -> Boolean =
        { _, _, _ -> true },
)

/** What a constraint can look at. The app answers [state] from the phone. */
interface CheckScope {
    val now: ZonedDateTime

    /** Phone state by key, see [StateKeys]; null when unknown. */
    suspend fun state(key: String): String?

    suspend fun notifications(): List<NotificationInfo>

    /** A run variable, then a global one. */
    fun variable(name: String): String?

    /** The rule being checked. */
    val rule: Rule

    fun lastRunAt(ruleId: String): Long?

    fun runsToday(ruleId: String): Int

    fun ruleEnabled(ruleId: String): Boolean?

    /** True while the rule with [ruleId] is running. */
    fun ruleRunning(ruleId: String): Boolean = false

    /** The type of the trigger that started this run, or null when it was started by hand or by another rule. */
    val triggerType: String? get() = null

    /** How long the stopwatch named [name] has run, or null when it was never started. */
    fun stopwatchMillis(name: String): Long? = null
}

/** A notification as constraints see it. */
data class NotificationInfo(
    val packageName: String,
    val title: String?,
    val text: String?,
)

/** A check of state. */
class ConstraintDef(
    val type: String,
    val category: Category,
    val title: String,
    val description: String,
    val fields: List<FieldDef> = emptyList(),
    val source: Source = Source.SYSTEM,
    /** Sideload builds only. */
    val sideloadOnly: Boolean = false,
    val sentence: (Map<String, String>) -> String = { title },
    val check: suspend CheckScope.(Map<String, String>) -> Boolean,
)

/** How an action runs. */
sealed interface ActionKind {
    /** One call of [tool] with arguments built from the action's settings. */
    class Tool(
        val tool: String,
        val arguments: (Map<String, String>) -> JsonObject,
    ) : ActionKind

    /** Done by the app itself, such as speaking text or an HTTP request. */
    data object Native : ActionKind

    /** Handled by the engine: flow, variables, several tool calls. */
    data object Engine : ActionKind
}

/** Something a rule does. */
class ActionDef(
    val type: String,
    val category: Category,
    val title: String,
    val description: String,
    val fields: List<FieldDef> = emptyList(),
    val tier: RiskTier,
    val kind: ActionKind,
    /** Every tool the action may call, for the confirmations an owner-made rule skips. */
    val tools: Set<String> = (kind as? ActionKind.Tool)?.let { setOf(it.tool) } ?: emptySet(),
    /** Holds more actions (If, Repeat, While). */
    val container: Boolean = false,
    /** Uses its constraints as its condition (If, While, Wait until). */
    val conditional: Boolean = false,
    /** Sideload builds only. */
    val sideloadOnly: Boolean = false,
    val sentence: (Map<String, String>) -> String = { title },
)

/** Keys of [CheckScope.state] the app answers. */
object StateKeys {
    const val BATTERY_LEVEL = "battery.level"
    const val PLUGGED = "power.plugged"
    const val BATTERY_SAVER = "battery.saver"
    const val SCREEN_ON = "screen.on"
    const val LOCKED = "screen.locked"
    const val WIFI_ENABLED = "wifi.enabled"
    const val WIFI_SSID = "wifi.ssid"
    const val BLUETOOTH_ENABLED = "bluetooth.enabled"

    /** Names of connected Bluetooth devices, one per line. */
    const val BLUETOOTH_DEVICES = "bluetooth.devices"
    const val INTERNET = "internet"
    const val VPN = "vpn"
    const val AIRPLANE = "airplane"
    const val RINGER = "ringer"
    const val DND = "dnd"
    const val HEADSET = "headset"
    const val FOREGROUND = "foreground.package"
    const val DARK_MODE = "dark_mode"
    const val SHIZUKU = "shizuku"
    const val ACCESSIBILITY = "accessibility"
    const val SERVER = "server"
    const val MUSIC = "music.active"

    /** Degrees Celsius, such as "31.5". */
    const val BATTERY_TEMPERATURE = "battery.temperature"
    const val LOCATION_ON = "location.on"

    /** The phone's IP addresses, one per line. */
    const val IP_ADDRESSES = "ip.addresses"
    const val ROAMING = "roaming"
    const val DATA_ROAMING = "data_roaming"
    const val MOBILE_DATA = "mobile_data"

    /** "in_service", "no_service", "emergency" or "off". */
    const val MOBILE_SERVICE = "mobile.service"
    const val USB_DEVICE = "usb.device"
    const val HOTSPOT = "hotspot"
    const val USB_TETHERING = "usb.tethering"

    /** Megabytes of mobile data used today. */
    const val DATA_TODAY_MB = "data.today"

    /** Megabytes of mobile data used this month. */
    const val DATA_MONTH_MB = "data.month"

    /** Whole minutes since the phone started. */
    const val UPTIME_MINUTES = "uptime.minutes"
    const val AUTO_ROTATE = "auto_rotate"
    const val AUTO_SYNC = "auto_sync"
    const val CAMERA_IN_USE = "camera.in_use"
    const val MIC_RECORDING = "mic.recording"
    const val NFC = "nfc"
    const val SPEAKING = "speaking"
    const val TORCH = "torch"
    const val WORK_PROFILE = "work_profile"

    /** The package of the home screen app. */
    const val HOME_APP = "home.package"

    /** The cell towers the phone sees now, one per line, as "mcc-mnc-area-cell". */
    const val CELL_TOWERS = "cell.towers"

    /** Screen brightness as a percentage. */
    const val BRIGHTNESS = "brightness"
    const val KEYBOARD = "keyboard"
    const val ALWAYS_ON_DISPLAY = "aod"

    /** "up", "down" or "other". */
    const val FACING = "facing"

    /** "portrait" or "landscape". */
    const val ORIENTATION = "orientation"

    /** Light in lux. */
    const val LIGHT = "light"

    /** "near" or "far". */
    const val PROXIMITY = "proximity"
    const val SPEAKERPHONE = "speakerphone"
    const val FLOATING_BUTTON = "floating_button"

    private const val VOLUME = "volume:"

    /** The volume of [stream] (ring, notification, media, alarm, call, system) as a percentage. */
    fun volume(stream: String) = VOLUME + stream

    /** The stream in a [volume] key, or null for other keys. */
    fun volumeStream(key: String): String? = key.takeIf { it.startsWith(VOLUME) }?.removePrefix(VOLUME)

    private const val SETTING = "setting:"

    /** The value of a system setting, [table] being system, secure or global. */
    fun setting(
        table: String,
        name: String,
    ) = "$SETTING$table:$name"

    /** The table and name in a [setting] key, or null for other keys. */
    fun settingOf(key: String): Pair<String, String>? =
        key
            .takeIf { it.startsWith(SETTING) }
            ?.removePrefix(SETTING)
            ?.split(':', limit = 2)
            ?.takeIf { it.size == 2 }
            ?.let { it[0] to it[1] }

    private const val APP_INSTALLED = "app.installed:"

    /** "on" when the app with [packageName] is installed. */
    fun appInstalled(packageName: String) = APP_INSTALLED + packageName

    /** The package in an [appInstalled] key, or null for other keys. */
    fun appInstalledOf(key: String): String? = key.takeIf { it.startsWith(APP_INSTALLED) }?.removePrefix(APP_INSTALLED)

    private const val APP_ENABLED = "app.enabled:"

    /** "on" when the app with [packageName] is installed and enabled. */
    fun appEnabled(packageName: String) = APP_ENABLED + packageName

    /** The package in an [appEnabled] key, or null for other keys. */
    fun appEnabledOf(key: String): String? = key.takeIf { it.startsWith(APP_ENABLED) }?.removePrefix(APP_ENABLED)

    /** "latitude,longitude" of the phone's last known location. */
    const val LOCATION = "location"

    /** "idle", "ringing" or "in_call". */
    const val CALL = "call.state"

    /** Titles of the calendar events happening now, one per line. */
    const val CALENDAR_NOW = "calendar.now"

    private const val HOME_ASSISTANT = "ha.state:"

    /** The state of Home Assistant entity [entity], such as "on". */
    fun homeAssistant(entity: String) = HOME_ASSISTANT + entity

    /** The entity in a [homeAssistant] key, or null for other keys. */
    fun homeAssistantEntity(key: String): String? = key.takeIf { it.startsWith(HOME_ASSISTANT) }?.removePrefix(HOME_ASSISTANT)

    private const val SCREEN_TEXT = "screen.text:"

    /** Whether [text] is on the screen now, as "on" or "off". */
    fun screenText(text: String) = SCREEN_TEXT + text

    /** The text in a [screenText] key, or null for other keys. */
    fun screenTextOf(key: String): String? = key.takeIf { it.startsWith(SCREEN_TEXT) }?.removePrefix(SCREEN_TEXT)

    private const val SCREEN_TAB = "screen.tab:"

    /** Whether the tab labelled [label] has its selection bar on the screen now, as "on" or "off". */
    fun screenTab(label: String) = SCREEN_TAB + label

    /** The label in a [screenTab] key, or null for other keys. */
    fun screenTabOf(key: String): String? = key.takeIf { it.startsWith(SCREEN_TAB) }?.removePrefix(SCREEN_TAB)
}
