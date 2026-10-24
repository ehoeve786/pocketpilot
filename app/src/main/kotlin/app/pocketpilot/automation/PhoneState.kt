package app.pocketpilot.automation

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.NetworkStatsManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import app.pocketpilot.capability.accessibility.A11yBridge
import app.pocketpilot.capability.shizuku.ShizukuConnection
import app.pocketpilot.capability.shizuku.ShizukuShellRunner
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.core.automation.StateKeys
import app.pocketpilot.server.http.McpHttpServer
import app.pocketpilot.server.http.ServerState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Answers the [StateKeys] that conditions read, from Android's own APIs where they need no extra
 * permission and from Shizuku where they would (the Wi-Fi name without location access).
 */
class PhoneState(
    private val context: Context,
    private val shizuku: ShizukuConnection,
    private val shell: ShizukuShellRunner,
    private val server: () -> McpHttpServer,
) {
    /** Bluetooth devices connected now, by address; filled from the connection broadcasts. */
    val bluetoothDevices = ConcurrentHashMap<String, String>()

    /** The last Wi-Fi name seen, so "Wi-Fi disconnects" can say which network it left. */
    @Volatile var lastSsid: String? = null

    /** Cameras another app holds now, by id; filled from the camera callbacks. */
    private val camerasInUse = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var torchOn = false

    init {
        // Both callbacks report each camera's current state as soon as they are registered.
        runCatching {
            val cameras = context.getSystemService(CameraManager::class.java)
            val main = Handler(Looper.getMainLooper())
            cameras.registerAvailabilityCallback(
                object : CameraManager.AvailabilityCallback() {
                    override fun onCameraAvailable(cameraId: String) {
                        camerasInUse.remove(cameraId)
                    }

                    override fun onCameraUnavailable(cameraId: String) {
                        camerasInUse.add(cameraId)
                    }
                },
                main,
            )
            cameras.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(
                        cameraId: String,
                        enabled: Boolean,
                    ) {
                        torchOn = enabled
                    }
                },
                main,
            )
        }
    }

    suspend fun read(key: String): String? {
        StateKeys.volumeStream(key)?.let { return volumePercent(it)?.toString() }
        StateKeys.settingOf(key)?.let { (table, name) -> return setting(table, name) }
        StateKeys.appInstalledOf(key)?.let { return onOff(appInstalled(it)) }
        StateKeys.appEnabledOf(key)?.let { return appEnabled(it)?.let(::onOff) }
        return when (key) {
            StateKeys.BATTERY_LEVEL -> batteryLevel()?.toString()
            StateKeys.PLUGGED -> onOff(plugSource() != null)
            StateKeys.BATTERY_SAVER -> onOff(power.isPowerSaveMode)
            StateKeys.SCREEN_ON -> onOff(power.isInteractive)
            StateKeys.LOCKED -> onOff(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            StateKeys.WIFI_ENABLED -> onOff(wifiEnabled())
            StateKeys.WIFI_SSID -> wifiSsid()
            StateKeys.BLUETOOTH_ENABLED -> onOff(globalInt(Settings.Global.BLUETOOTH_ON) == 1)
            StateKeys.BLUETOOTH_DEVICES -> (bluetoothDevices.values + bluetoothAudioNames()).distinct().joinToString("\n")
            StateKeys.INTERNET -> onOff(hasTransportOrCapability(capability = NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            StateKeys.VPN -> onOff(hasTransportOrCapability(transport = NetworkCapabilities.TRANSPORT_VPN))
            StateKeys.AIRPLANE -> onOff(globalInt(Settings.Global.AIRPLANE_MODE_ON) == 1)
            StateKeys.RINGER -> ringerName(audio.ringerMode)
            StateKeys.DND -> onOff(dndOn())
            StateKeys.HEADSET -> onOff(headsetConnected())
            StateKeys.FOREGROUND -> A11yBridge.frontAppPackage()
            StateKeys.DARK_MODE -> onOff(darkMode())
            StateKeys.SHIZUKU -> onOff(shizuku.state.value == ShizukuState.CONNECTED)
            StateKeys.ACCESSIBILITY -> onOff(A11yBridge.connected.value)
            StateKeys.SERVER -> onOff(server().serverState.value is ServerState.Running)
            StateKeys.MUSIC -> onOff(audio.isMusicActive)
            StateKeys.CALL -> callState()
            StateKeys.BATTERY_TEMPERATURE -> batteryTemperature()?.toString()
            StateKeys.LOCATION_ON -> onOff(context.getSystemService(LocationManager::class.java).isLocationEnabled)
            StateKeys.IP_ADDRESSES -> ipAddresses().joinToString("\n")
            StateKeys.ROAMING -> onOff(runCatching { telephony.isNetworkRoaming }.getOrDefault(false))
            StateKeys.DATA_ROAMING -> onOff(globalInt(Settings.Global.DATA_ROAMING) == 1)
            StateKeys.MOBILE_DATA -> onOff(mobileDataOn())
            StateKeys.MOBILE_SERVICE -> mobileService()
            StateKeys.USB_DEVICE -> onOff(context.getSystemService(UsbManager::class.java).deviceList.isNotEmpty())
            StateKeys.HOTSPOT -> onOff(hotspotOn())
            StateKeys.USB_TETHERING -> onOff(interfaceUp(USB_TETHER_INTERFACES))
            StateKeys.DATA_TODAY_MB -> mobileDataMb(LocalDate.now())?.toString()
            StateKeys.DATA_MONTH_MB -> mobileDataMb(LocalDate.now().withDayOfMonth(1))?.toString()
            StateKeys.UPTIME_MINUTES -> (SystemClock.elapsedRealtime() / 60_000).toString()
            StateKeys.AUTO_ROTATE -> onOff(systemInt(Settings.System.ACCELEROMETER_ROTATION) == 1)
            StateKeys.AUTO_SYNC -> runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrNull()?.let(::onOff)
            StateKeys.CAMERA_IN_USE -> onOff(camerasInUse.isNotEmpty())
            StateKeys.MIC_RECORDING -> onOff(audio.activeRecordingConfigurations.isNotEmpty())
            StateKeys.NFC -> onOff(context.getSystemService(NfcManager::class.java)?.defaultAdapter?.isEnabled == true)
            StateKeys.TORCH -> onOff(torchOn)
            StateKeys.WORK_PROFILE -> onOff(workProfileOn())
            StateKeys.HOME_APP -> homePackage()
            StateKeys.CELL_TOWERS -> runCatching { cellTowers() }.getOrNull()?.joinToString("\n")
            StateKeys.BRIGHTNESS -> (systemInt(Settings.System.SCREEN_BRIGHTNESS) * 100 / MAX_BRIGHTNESS).coerceIn(0, 100).toString()
            StateKeys.KEYBOARD -> keyboardShowing()?.let(::onOff)
            StateKeys.ALWAYS_ON_DISPLAY -> onOff(alwaysOnDisplay())
            StateKeys.FACING -> facing()
            StateKeys.ORIENTATION -> orientation()
            StateKeys.LIGHT -> sensorValues(Sensor.TYPE_LIGHT)?.firstOrNull()?.toString()
            StateKeys.PROXIMITY -> proximity()
            StateKeys.SPEAKERPHONE -> onOff(speakerphoneOn())
            else -> null
        }
    }

    private val power get() = context.getSystemService(PowerManager::class.java)
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val telephony get() = context.getSystemService(TelephonyManager::class.java)
    private val sensors get() = context.getSystemService(SensorManager::class.java)

    /** Degrees Celsius, to one decimal. */
    private fun batteryTemperature(): Double? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10.0
    }

    /** The addresses of the network interfaces that are up, without loopback and link-local ones. */
    private fun ipAddresses(): List<String> =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
                .sortedBy { if (it is Inet4Address) 0 else 1 }
                .mapNotNull { it.hostAddress?.substringBefore('%') }
        }.getOrDefault(emptyList())

    /** True when a network interface whose name starts with one of [prefixes] is up with an address. */
    private fun interfaceUp(prefixes: List<String>): Boolean =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { nic ->
                nic.isUp && prefixes.any { nic.name.startsWith(it) } && nic.inetAddresses.hasMoreElements()
            }
        }.getOrDefault(false)

    /** WifiManager knows, but hides it from apps; when it cannot be asked, the hotspot's network interface is looked for. */
    private fun hotspotOn(): Boolean =
        runCatching {
            val wifi = context.getSystemService(WifiManager::class.java)
            wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
        }.getOrElse { interfaceUp(HOTSPOT_INTERFACES) }

    /** Needs phone access, which may not be granted; the setting is read instead then. */
    @SuppressLint("MissingPermission")
    private fun mobileDataOn(): Boolean = runCatching { telephony.isDataEnabled }.getOrElse { globalInt("mobile_data") == 1 }

    /** From the service state, which needs phone access; through Shizuku when the app does not have it. */
    @SuppressLint("MissingPermission")
    private suspend fun mobileService(): String? {
        if (globalInt(Settings.Global.AIRPLANE_MODE_ON) == 1) return "off"
        val state =
            runCatching { telephony.serviceState?.state }.getOrNull()
                ?: shellOutput("dumpsys telephony.registry")?.let {
                    VOICE_REG_STATE
                        .find(it)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                }
                ?: return null
        return when (state) {
            ServiceState.STATE_IN_SERVICE -> "in_service"
            ServiceState.STATE_EMERGENCY_ONLY -> "emergency"
            ServiceState.STATE_POWER_OFF -> "off"
            else -> "no_service"
        }
    }

    /** Megabytes of mobile data used from the start of [since] until now; null without usage access. */
    private fun mobileDataMb(since: LocalDate): Long? =
        runCatching {
            val start = since.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val bucket =
                context
                    .getSystemService(NetworkStatsManager::class.java)
                    .querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, System.currentTimeMillis())
            (bucket.rxBytes + bucket.txBytes) / 1_000_000
        }.getOrNull()

    private fun workProfileOn(): Boolean {
        val users = context.getSystemService(UserManager::class.java)
        return users.userProfiles.any { it != Process.myUserHandle() && !users.isQuietModeEnabled(it) }
    }

    private fun homePackage(): String? =
        context.packageManager
            .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            ?.takeIf { it != "android" }

    /** The cell towers the phone sees, as "mcc-mnc-area-cell"; needs location access. */
    @SuppressLint("MissingPermission")
    fun cellTowers(): List<String> =
        telephony.allCellInfo
            .orEmpty()
            .mapNotNull(::towerId)
            .distinct()

    private fun towerId(info: CellInfo): String? {
        val id = info.cellIdentity
        val parts =
            when (id) {
                is CellIdentityLte -> listOf(id.mccString, id.mncString, id.tac, id.ci)
                is CellIdentityNr -> listOf(id.mccString, id.mncString, id.tac, id.nci)
                is CellIdentityWcdma -> listOf(id.mccString, id.mncString, id.lac, id.cid)
                is CellIdentityGsm -> listOf(id.mccString, id.mncString, id.lac, id.cid)
                else -> return null
            }
        if (parts.any { it == null || it == Int.MAX_VALUE || it == Long.MAX_VALUE }) return null
        return parts.joinToString("-")
    }

    /** Through Shizuku, as apps cannot read whether the keyboard shows; null without Shizuku. */
    private suspend fun keyboardShowing(): Boolean? = shellOutput("dumpsys input_method")?.let { INPUT_SHOWN.containsMatchIn(it) }

    private fun alwaysOnDisplay(): Boolean =
        secureInt("doze_always_on") == 1 || Settings.System.getInt(context.contentResolver, "aod_mode", 0) == 1

    private fun speakerphoneOn(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn
        }

    /** The volume of [stream] as a percentage of its maximum. */
    private fun volumePercent(stream: String): Int? {
        val type = STREAMS[stream] ?: return null
        val max = audio.getStreamMaxVolume(type).takeIf { it > 0 } ?: return null
        return (audio.getStreamVolume(type) * 100.0 / max).roundToInt()
    }

    /** A setting's value; through Shizuku when Android keeps the setting from apps. */
    private suspend fun setting(
        table: String,
        name: String,
    ): String? {
        if (table !in setOf("system", "secure", "global") || !SETTING_NAME.matches(name)) return null
        val resolver = context.contentResolver
        val direct =
            runCatching {
                when (table) {
                    "secure" -> Settings.Secure.getString(resolver, name)
                    "global" -> Settings.Global.getString(resolver, name)
                    else -> Settings.System.getString(resolver, name)
                }
            }
        direct.getOrNull()?.let { return it }
        return shellOutput("settings get $table $name")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    /** Whether [packageName] is installed; apps without a launcher icon are hidden from the app, so Shizuku asks for them. */
    private suspend fun appInstalled(packageName: String): Boolean {
        if (!PACKAGE_NAME.matches(packageName)) return false
        if (runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess) return true
        return shellOutput("pm list packages $packageName")?.lines()?.any { it.trim() == "package:$packageName" } == true
    }

    /** Whether [packageName] is enabled, or null when it is not installed. */
    private suspend fun appEnabled(packageName: String): Boolean? {
        if (!PACKAGE_NAME.matches(packageName)) return null
        runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()?.let { return it.enabled }
        if (!appInstalled(packageName)) return null
        return shellOutput("pm list packages -e $packageName")?.lines()?.any { it.trim() == "package:$packageName" }
    }

    /** "up" or "down" when the phone lies flat, else "other". */
    private suspend fun facing(): String? {
        val (_, _, z) = sensorValues(Sensor.TYPE_ACCELEROMETER)?.takeIf { it.size >= 3 } ?: return null
        return when {
            z > FLAT -> "up"
            z < -FLAT -> "down"
            else -> "other"
        }
    }

    /** "landscape" when gravity runs across the phone more than along it, else "portrait". */
    private suspend fun orientation(): String? {
        val (x, y) = sensorValues(Sensor.TYPE_ACCELEROMETER)?.takeIf { it.size >= 2 } ?: return null
        return if (abs(x) > abs(y)) "landscape" else "portrait"
    }

    private suspend fun proximity(): String? {
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return null
        val distance = sensorValues(Sensor.TYPE_PROXIMITY)?.firstOrNull() ?: return null
        return if (distance < sensor.maximumRange) "near" else "far"
    }

    /** The first reading of the sensor of [type], or null when there is none or it does not answer in time. */
    private suspend fun sensorValues(type: Int): FloatArray? {
        val sensor = sensors.getDefaultSensor(type) ?: return null
        return withTimeoutOrNull(SENSOR_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener =
                    object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            sensors.unregisterListener(this)
                            if (continuation.isActive) continuation.resume(event.values.copyOf())
                        }

                        override fun onAccuracyChanged(
                            sensor: Sensor?,
                            accuracy: Int,
                        ) = Unit
                    }
                sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                continuation.invokeOnCancellation { sensors.unregisterListener(listener) }
            }
        }
    }

    /** The output of a read-only command through Shizuku, or null without it. */
    private suspend fun shellOutput(command: String): String? {
        if (!shell.available.value) return null
        return runCatching { shell.run(command, SHELL_TIMEOUT_MS) }.getOrNull()?.takeIf { it.exitCode == 0 }?.stdout
    }

    fun batteryLevel(): Int? {
        val level = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level.takeIf { it in 0..100 }
    }

    /** "ac", "usb" or "wireless" while plugged in, else null. */
    fun plugSource(intent: Intent? = null): String? {
        val battery = intent ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return when (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "ac"
            else -> null
        }
    }

    fun wifiEnabled(): Boolean = context.getSystemService(WifiManager::class.java).isWifiEnabled

    /**
     * The connected Wi-Fi network's name. Android hides it from apps without location access, so it
     * comes from `cmd wifi status` through Shizuku; null when not on Wi-Fi or neither works.
     */
    suspend fun wifiSsid(): String? {
        if (!hasTransportOrCapability(transport = NetworkCapabilities.TRANSPORT_WIFI)) return null
        if (shell.available.value) {
            val out = runCatching { shell.run("cmd wifi status", SHELL_TIMEOUT_MS).stdout }.getOrNull().orEmpty()
            SSID.find(out)?.groupValues?.get(1)?.let {
                lastSsid = it
                return it
            }
        }
        @Suppress("DEPRECATION")
        val ssid =
            context
                .getSystemService(WifiManager::class.java)
                .connectionInfo
                ?.ssid
                ?.trim('"')
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        if (ssid != null) lastSsid = ssid
        return ssid
    }

    fun hasTransportOrCapability(
        transport: Int? = null,
        capability: Int? = null,
    ): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return (transport == null || caps.hasTransport(transport)) && (capability == null || caps.hasCapability(capability))
    }

    fun dndOn(): Boolean =
        context.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }

    /** From the audio mode, which needs no phone permission and also counts calls in calling apps. */
    private fun callState(): String =
        when (audio.mode) {
            AudioManager.MODE_RINGTONE -> "ringing"
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_CALL_SCREENING -> "in_call"
            else -> "idle"
        }

    fun headsetConnected(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }

    /**
     * Names of the Bluetooth outputs Android routes audio to right now. A call moves a car to its call
     * profile, which can drop the audio profile the connection broadcasts were counted on, and a device
     * connected before the app started sends no broadcast at all. The live route is read in both cases.
     */
    private fun bluetoothAudioNames(): List<String> =
        audio
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in BLUETOOTH_OUTPUT_TYPES }
            .mapNotNull { it.productName?.toString()?.takeIf(String::isNotBlank) }

    private fun darkMode(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun globalInt(name: String): Int = Settings.Global.getInt(context.contentResolver, name, 0)

    private fun systemInt(name: String): Int = Settings.System.getInt(context.contentResolver, name, 0)

    private fun secureInt(name: String): Int = runCatching { Settings.Secure.getInt(context.contentResolver, name, 0) }.getOrDefault(0)

    companion object {
        private const val SHELL_TIMEOUT_MS = 5_000L
        private val SSID = Regex("""connected to "([^"]+)"""", RegexOption.IGNORE_CASE)
        private val VOICE_REG_STATE = Regex("""mVoiceRegState\s*=\s*(\d)""")
        private val INPUT_SHOWN = Regex("""mInputShown\s*=\s*true""")
        private val SETTING_NAME = Regex("""[A-Za-z0-9_.\-]{1,100}""")
        private val PACKAGE_NAME = Regex("""[A-Za-z0-9_.]{1,200}""")
        private const val SENSOR_TIMEOUT_MS = 2_000L
        private const val MAX_BRIGHTNESS = 255

        /** Gravity along the screen's axis, in m/s², above which the phone counts as lying flat. */
        private const val FLAT = 7f

        /** Interfaces Android and Samsung use for the Wi-Fi hotspot and for USB tethering. */
        private val HOTSPOT_INTERFACES = listOf("ap", "swlan", "softap")
        private val USB_TETHER_INTERFACES = listOf("rndis", "ncm", "usb")

        private val STREAMS =
            mapOf(
                "ring" to AudioManager.STREAM_RING,
                "notification" to AudioManager.STREAM_NOTIFICATION,
                "media" to AudioManager.STREAM_MUSIC,
                "alarm" to AudioManager.STREAM_ALARM,
                "call" to AudioManager.STREAM_VOICE_CALL,
                "system" to AudioManager.STREAM_SYSTEM,
            )

        val HEADSET_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
            )

        val BLUETOOTH_OUTPUT_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
            )

        fun onOff(value: Boolean): String = if (value) "on" else "off"

        fun ringerName(mode: Int): String =
            when (mode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
    }
}
