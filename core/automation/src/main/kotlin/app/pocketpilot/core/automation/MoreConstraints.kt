package app.pocketpilot.core.automation

import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** The conditions added to match MacroDroid's list: device, connectivity, sensors, volume and date checks. */
internal object MoreConstraints {
    /** A state that is on or off, read from [key]. */
    private fun onOffState(
        type: String,
        category: Category,
        title: String,
        key: String,
        noun: String,
        description: String = "$noun is on or off.",
        source: Source = Source.SYSTEM,
    ) = ConstraintDef(
        type = type,
        category = category,
        title = title,
        description = description,
        fields = listOf(choiceField("state", "Is", ON_OFF)),
        source = source,
        sentence = { p -> "$noun ${p.p("state").ifBlank { "on" }}" },
        check = { p -> onOff(state(key)) == p.p("state").ifBlank { "on" } },
    )

    /** A number read from [key] that must be within the range the owner sets. */
    private fun inRange(
        type: String,
        category: Category,
        title: String,
        description: String,
        noun: String,
        unit: String,
        min: String,
        max: String,
        key: (Map<String, String>) -> String,
        extra: List<FieldDef> = emptyList(),
        source: Source = Source.SYSTEM,
    ) = ConstraintDef(
        type = type,
        category = category,
        title = title,
        description = description,
        fields =
            extra +
                listOf(
                    numberField("min", "At least ($unit)", default = min.ifBlank { null }, required = false),
                    numberField("max", "At most ($unit)", default = max.ifBlank { null }, required = false),
                ),
        source = source,
        sentence = { p ->
            val low = p.p("min").ifBlank { min }
            val high = p.p("max").ifBlank { max }
            when {
                high.isBlank() -> "$noun at least $low $unit"
                low.isBlank() || low == "0" -> "$noun at most $high $unit"
                else -> "$noun $low to $high $unit"
            }
        },
        check = { p ->
            val value = state(key(p))?.trim()?.toDoubleOrNull()
            value != null && within(value, p.p("min"), p.p("max"))
        },
    )

    private val MONTHS = Month.entries.map { Choice(it.name.lowercase().take(3), it.getDisplayName(TextStyle.FULL, Locale.ENGLISH)) }

    private val STREAMS =
        listOf(
            Choice("ring", "Ringer"),
            Choice("notification", "Notification"),
            Choice("media", "Media"),
            Choice("alarm", "Alarm"),
            Choice("call", "In call"),
            Choice("system", "System"),
        )

    val all: List<ConstraintDef> =
        listOf(
            // Time
            ConstraintDef(
                type = "time.month_day",
                category = Category.TIME,
                title = "Day of the month",
                description = "The date is between two days of the month. Use 31 for the last day of any month.",
                fields =
                    listOf(
                        numberField("from", "From day", default = "1"),
                        numberField("to", "To day", default = "31"),
                    ),
                sentence = { p -> "Day ${p.p("from")} to ${p.p("to")} of the month" },
                check = { p ->
                    val last = now.toLocalDate().lengthOfMonth()
                    val from = (p.p("from").toIntOrNull() ?: 1).coerceIn(1, last)
                    val to = (p.p("to").toIntOrNull() ?: last).coerceIn(1, last)
                    val day = now.dayOfMonth
                    if (from <= to) day in from..to else day >= from || day <= to
                },
            ),
            ConstraintDef(
                type = "time.month",
                category = Category.TIME,
                title = "Month of the year",
                description = "The month is between two months; December to February wraps around the new year.",
                fields = listOf(choiceField("from", "From", MONTHS), choiceField("to", "To", MONTHS, default = "dec")),
                sentence = { p -> "${monthName(p.p("from"))} to ${monthName(p.p("to"))}" },
                check = { p ->
                    val from = monthNumber(p.p("from")) ?: 1
                    val to = monthNumber(p.p("to")) ?: 12
                    val month = now.monthValue
                    if (from <= to) month in from..to else month >= from || month <= to
                },
            ),
            ConstraintDef(
                type = "time.sun",
                category = Category.TIME,
                title = "Sunrise/Sunset",
                description = "It is day (between sunrise and sunset) or night where the phone is. Needs location access.",
                fields =
                    listOf(
                        choiceField("state", "It is", listOf(Choice("day", "Day"), Choice("night", "Night"))),
                        numberField("offset", "Shift by minutes", default = "0", required = false, hint = "Negative for earlier"),
                    ),
                source = Source.SYSTEM,
                sentence = { p -> if (p.p("state") == "night") "Night time" else "Day time" },
                check = { p ->
                    val here = state(StateKeys.LOCATION)?.let(Place::coordinates)
                    val times = here?.let { SunTimes.of(now.toLocalDate(), it.first, it.second, now.zone) }
                    if (here == null) {
                        false
                    } else {
                        val shift = p.p("offset").toLongOrNull() ?: 0
                        // No sunrise or sunset today: day all day in summer, night in winter, by the sun's side of the equator.
                        val day =
                            if (times == null) {
                                (here.first >= 0) == (now.monthValue in 4..9)
                            } else {
                                !now.isBefore(times.first.plusMinutes(shift)) && now.isBefore(times.second.plusMinutes(shift))
                            }
                        day == (p.p("state") != "night")
                    }
                },
            ),
            ConstraintDef(
                type = "stopwatch.time",
                category = Category.TIME,
                title = "Stopwatch",
                description = "A stopwatch, started with the Stopwatch action, has run more or less than a time.",
                fields =
                    listOf(
                        textField("name", "Stopwatch name"),
                        choiceField("op", "Has run", listOf(Choice("more", "More than"), Choice("less", "Less than"))),
                        numberField("seconds", "Seconds", default = "60"),
                    ),
                source = Source.POCKETPILOT,
                sentence = { p -> "Stopwatch ${p.p("name")} ${if (p.p("op") == "less") "under" else "over"} ${p.p("seconds")} s" },
                check = { p ->
                    val ran = (stopwatchMillis(p.p("name")) ?: 0) / 1000.0
                    val limit = p.p("seconds").toDoubleOrNull() ?: 0.0
                    if (p.p("op") == "less") ran < limit else ran > limit
                },
            ),
            inRange(
                "time.since_boot",
                Category.TIME,
                "Time since boot",
                "The phone started a number of minutes ago.",
                "Up",
                "minutes",
                "0",
                "",
                { StateKeys.UPTIME_MINUTES },
            ),
            // Battery
            inRange(
                "battery.temperature",
                Category.BATTERY,
                "Battery temperature",
                "The battery is within a temperature range.",
                "Battery",
                "°C",
                "0",
                "45",
                { StateKeys.BATTERY_TEMPERATURE },
            ),
            // Connectivity
            onOffState(
                "location.on",
                Category.CONNECTIVITY,
                "Location (GPS) on",
                StateKeys.LOCATION_ON,
                "Location",
                "Location services, which include GPS, are on or off.",
            ),
            ConstraintDef(
                type = "network.ip",
                category = Category.CONNECTIVITY,
                title = "IP address",
                description = "One of the phone's IP addresses starts with, or matches, what you type.",
                fields =
                    listOf(
                        textField("ip", "Address starts with", hint = "For example 192.168.8. or 10.1.0.2"),
                        choiceField("state", "Has it", YES_NO),
                    ),
                sentence = { p -> if (p.p("state") == "no") "IP not ${p.p("ip")}" else "IP ${p.p("ip")}" },
                check = { p ->
                    val wanted = p.p("ip")
                    val found =
                        state(StateKeys.IP_ADDRESSES).orEmpty().lines().any { address ->
                            address.isNotBlank() && wanted.split(',').map(String::trim).any { it.isNotEmpty() && address.startsWith(it) }
                        }
                    found == (p.p("state") != "no")
                },
            ),
            onOffState(
                "roaming.now",
                Category.CONNECTIVITY,
                "Is roaming",
                StateKeys.ROAMING,
                "Roaming",
                "The phone is or is not roaming now.",
            ),
            onOffState(
                "data_roaming.on",
                Category.CONNECTIVITY,
                "Data roaming allowed",
                StateKeys.DATA_ROAMING,
                "Data roaming",
                "The setting that allows mobile data while roaming is on or off.",
            ),
            onOffState("mobile_data.on", Category.CONNECTIVITY, "Mobile data on", StateKeys.MOBILE_DATA, "Mobile data"),
            ConstraintDef(
                type = "mobile.service",
                category = Category.CONNECTIVITY,
                title = "Mobile service",
                description = "The phone has mobile service, no service, emergency calls only, or the radio is off.",
                fields =
                    listOf(
                        choiceField(
                            "state",
                            "Is",
                            listOf(
                                Choice("in_service", "In service"),
                                Choice("no_service", "No service"),
                                Choice("emergency", "Emergency only"),
                                Choice("off", "Radio off"),
                            ),
                        ),
                    ),
                sentence = { p -> "Mobile ${p.p("state").ifBlank { "in_service" }.replace('_', ' ')}" },
                check = { p -> state(StateKeys.MOBILE_SERVICE) == p.p("state").ifBlank { "in_service" } },
            ),
            onOffState(
                "usb.device",
                Category.CONNECTIVITY,
                "USB device connected",
                StateKeys.USB_DEVICE,
                "USB device",
                "A USB device, such as a drive or keyboard, is or is not plugged into the phone.",
            ),
            onOffState("hotspot.on", Category.CONNECTIVITY, "Wi-Fi hotspot on", StateKeys.HOTSPOT, "Hotspot"),
            onOffState("usb_tethering.on", Category.CONNECTIVITY, "USB tethering on", StateKeys.USB_TETHERING, "USB tethering"),
            inRange(
                "data.used",
                Category.CONNECTIVITY,
                "Mobile data used",
                "Mobile data used today or this month is within a range. Needs usage access.",
                "Data used",
                "MB",
                "0",
                "",
                { p -> if (p.p("period") == "today") StateKeys.DATA_TODAY_MB else StateKeys.DATA_MONTH_MB },
                extra = listOf(choiceField("period", "Used", listOf(Choice("month", "This month"), Choice("today", "Today")))),
            ),
            // Device
            onOffState("auto_rotate.on", Category.DEVICE, "Auto rotate", StateKeys.AUTO_ROTATE, "Auto rotate"),
            onOffState("auto_sync.on", Category.DEVICE, "Auto sync", StateKeys.AUTO_SYNC, "Auto sync"),
            onOffState(
                "camera.in_use",
                Category.DEVICE,
                "Camera in use",
                StateKeys.CAMERA_IN_USE,
                "Camera",
                "An app is or is not using a camera.",
            ),
            onOffState(
                "mic.recording",
                Category.DEVICE,
                "Microphone recording",
                StateKeys.MIC_RECORDING,
                "Microphone",
                "An app is or is not recording from the microphone.",
            ),
            onOffState("nfc.on", Category.DEVICE, "NFC on", StateKeys.NFC, "NFC"),
            onOffState(
                "speaking.now",
                Category.DEVICE,
                "Speaking text",
                StateKeys.SPEAKING,
                "Speaking",
                "PocketPilot is or is not speaking text aloud.",
                Source.POCKETPILOT,
            ),
            onOffState("torch.on", Category.DEVICE, "Flashlight on", StateKeys.TORCH, "Flashlight"),
            onOffState(
                "work_profile.on",
                Category.DEVICE,
                "Work profile on",
                StateKeys.WORK_PROFILE,
                "Work profile",
                "The work profile is on, or is paused or missing.",
            ),
            ConstraintDef(
                type = "setting.value",
                category = Category.SETTINGS,
                title = "System setting",
                description = "An Android setting has a value, read from the system, secure or global table.",
                fields =
                    listOf(
                        choiceField(
                            "table",
                            "Table",
                            listOf(Choice("system", "System"), Choice("secure", "Secure"), Choice("global", "Global")),
                        ),
                        textField("name", "Setting", hint = "For example screen_off_timeout"),
                        choiceField(
                            "op",
                            "Is",
                            listOf(
                                Choice("equals", "Equal to"),
                                Choice("not_equals", "Not equal to"),
                                Choice("contains", "Contains"),
                                Choice("greater", "Greater than"),
                                Choice("less", "Less than"),
                                Choice("set", "Set"),
                                Choice("not_set", "Not set"),
                            ),
                        ),
                        textField("value", "Value", required = false),
                    ),
                sentence = { p -> "${p.p("name")} ${p.p("op").replace('_', ' ')} ${p.p("value")}".trim() },
                check = { p ->
                    Constraints.compare(
                        state(StateKeys.setting(p.p("table").ifBlank { "system" }, p.p("name"))),
                        p.p("op"),
                        p["value"].orEmpty(),
                    )
                },
            ),
            ConstraintDef(
                type = "app.installed",
                category = Category.APPS,
                title = "App installed",
                description = "An app is, or is not, installed.",
                fields = listOf(singleAppField("app", "App"), choiceField("state", "Is installed", YES_NO)),
                sentence = { p -> if (p.p("state") == "no") "${p.p("app")} not installed" else "${p.p("app")} installed" },
                check = { p -> (onOff(state(StateKeys.appInstalled(p.p("app")))) == "on") == (p.p("state") != "no") },
            ),
            ConstraintDef(
                type = "app.enabled",
                category = Category.APPS,
                title = "App enabled/disabled",
                description = "An installed app is enabled or disabled.",
                fields =
                    listOf(
                        singleAppField("app", "App"),
                        choiceField("state", "Is", listOf(Choice("on", "Enabled"), Choice("off", "Disabled"))),
                    ),
                sentence = { p -> "${p.p("app")} ${if (p.p("state") == "off") "disabled" else "enabled"}" },
                check = { p ->
                    val enabled = onOff(state(StateKeys.appEnabled(p.p("app"))))
                    enabled != null && enabled == p.p("state").ifBlank { "on" }
                },
            ),
            ConstraintDef(
                type = "launcher.front",
                category = Category.APPS,
                title = "Home screen in front",
                description = "The home screen (launcher) is, or is not, on screen.",
                fields = listOf(choiceField("state", "Is in front", YES_NO)),
                source = Source.ACCESSIBILITY,
                sentence = { p -> if (p.p("state") == "no") "Home screen not in front" else "Home screen in front" },
                check = { p ->
                    val home = state(StateKeys.HOME_APP)
                    val front = state(StateKeys.FOREGROUND)
                    (home != null && home == front) == (p.p("state") != "no")
                },
            ),
            // Location
            ConstraintDef(
                type = "cell.tower",
                category = Category.LOCATION,
                title = "Cell towers",
                description = "The phone sees, or does not see, one of these cell towers. Needs location access.",
                fields =
                    listOf(
                        pickField("towers", "Towers", PickSource.CELL_TOWER, hint = "Pick the towers seen now; several with commas"),
                        choiceField("state", "Phone is", listOf(Choice("yes", "Near them"), Choice("no", "Away from them"))),
                    ),
                sentence = { p -> if (p.p("state") == "no") "Away from the picked cell towers" else "Near the picked cell towers" },
                check = { p ->
                    val seen = state(StateKeys.CELL_TOWERS)
                    if (seen == null) {
                        false
                    } else {
                        val near = p.p("towers").isNotBlank() && seen.lines().filter(String::isNotBlank).any { anyOf(p.p("towers"), it) }
                        near == (p.p("state") != "no")
                    }
                },
            ),
            // Screen
            inRange(
                "screen.brightness",
                Category.SCREEN,
                "Brightness",
                "The screen brightness is within a range.",
                "Brightness",
                "%",
                "0",
                "100",
                { StateKeys.BRIGHTNESS },
            ),
            onOffState(
                "keyboard.showing",
                Category.SCREEN,
                "Keyboard showing",
                StateKeys.KEYBOARD,
                "Keyboard",
                "The on-screen keyboard is or is not showing.",
            ),
            onOffState("aod.on", Category.SCREEN, "Always On Display", StateKeys.ALWAYS_ON_DISPLAY, "Always On Display"),
            // Sensors
            ConstraintDef(
                type = "device.facing",
                category = Category.SENSORS,
                title = "Device facing",
                description = "The phone lies face up or face down.",
                fields = listOf(choiceField("state", "Is", listOf(Choice("up", "Face up"), Choice("down", "Face down")))),
                sentence = { p -> "Face ${p.p("state").ifBlank { "up" }}" },
                check = { p -> state(StateKeys.FACING) == p.p("state").ifBlank { "up" } },
            ),
            ConstraintDef(
                type = "device.orientation",
                category = Category.SENSORS,
                title = "Device orientation",
                description = "The phone is held upright (portrait) or sideways (landscape).",
                fields = listOf(choiceField("state", "Is", listOf(Choice("portrait", "Portrait"), Choice("landscape", "Landscape")))),
                sentence = { p -> p.p("state").ifBlank { "portrait" }.replaceFirstChar(Char::uppercase) },
                check = { p -> state(StateKeys.ORIENTATION) == p.p("state").ifBlank { "portrait" } },
            ),
            inRange("sensor.light", Category.SENSORS, "Light sensor", "The light level is within a range.", "Light", "lux", "0", "", {
                StateKeys.LIGHT
            }),
            ConstraintDef(
                type = "sensor.proximity",
                category = Category.SENSORS,
                title = "Proximity sensor",
                description = "Something is near the top of the screen, such as a pocket or an ear, or nothing is.",
                fields = listOf(choiceField("state", "Is", listOf(Choice("near", "Near"), Choice("far", "Far")))),
                sentence = { p -> "Proximity ${p.p("state").ifBlank { "near" }}" },
                check = { p -> state(StateKeys.PROXIMITY) == p.p("state").ifBlank { "near" } },
            ),
            // Sound
            inRange(
                "volume.level",
                Category.SOUND,
                "Volume level",
                "A volume, such as ringer, notification or media, is within a range.",
                "Volume",
                "%",
                "0",
                "100",
                { p -> StateKeys.volume(p.p("stream").ifBlank { "media" }) },
                extra = listOf(choiceField("stream", "Volume", STREAMS, default = "media")),
            ),
            onOffState("speakerphone.on", Category.SOUND, "Speakerphone", StateKeys.SPEAKERPHONE, "Speakerphone"),
            // PocketPilot
            onOffState(
                "floating_button.showing",
                Category.POCKETPILOT,
                "Floating button showing",
                StateKeys.FLOATING_BUTTON,
                "Floating button",
                "A floating button is, or is not, on screen.",
                Source.POCKETPILOT,
            ),
            ConstraintDef(
                type = "rule.running",
                category = Category.MANUAL,
                title = "Rule running",
                description = "Another rule is running now, or is not.",
                fields = listOf(FieldDef("rule", "Rule", FieldKind.RULE), choiceField("state", "Is running", YES_NO)),
                source = Source.POCKETPILOT,
                sentence = { p -> if (p.p("state") == "no") "Rule not running" else "Rule running" },
                check = { p -> ruleRunning(p.p("rule")) == (p.p("state") != "no") },
            ),
            ConstraintDef(
                type = "trigger.fired",
                category = Category.MANUAL,
                title = "Trigger fired",
                description = "This run was, or was not, started by one of the rule's triggers. Use it in an If to act per trigger.",
                fields =
                    listOf(
                        pickField("trigger", "Trigger", PickSource.RULE_TRIGGER, hint = "manual for a run started by hand"),
                        choiceField("state", "Started it", YES_NO),
                    ),
                source = Source.POCKETPILOT,
                sentence = { p -> if (p.p("state") == "no") "Not started by ${p.p("trigger")}" else "Started by ${p.p("trigger")}" },
                check = { p ->
                    val fired = triggerType ?: "manual"
                    anyOf(p.p("trigger"), fired) == (p.p("state") != "no")
                },
            ),
        )

    /** True when [value] is at least [min] and at most [max]; a blank bound is open. */
    fun within(
        value: Double,
        min: String,
        max: String,
    ): Boolean {
        val low = min.toDoubleOrNull()
        val high = max.toDoubleOrNull()
        return (low == null || value >= low) && (high == null || value <= high)
    }

    private fun monthNumber(text: String): Int? = MONTHS.indexOfFirst { it.value == text.lowercase().take(3) }.takeIf { it >= 0 }?.plus(1)

    private fun monthName(text: String): String = MONTHS.firstOrNull { it.value == text.lowercase().take(3) }?.label ?: text
}
