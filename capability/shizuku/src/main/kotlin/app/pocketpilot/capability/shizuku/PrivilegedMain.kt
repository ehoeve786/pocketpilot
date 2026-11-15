package app.pocketpilot.capability.shizuku

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.system.Os
import android.util.Log
import java.io.File
import kotlin.system.exitProcess

/**
 * The entry point of PocketPilot's own privileged process. PrivilegedStarter runs it through adb as
 * `app_process` with PocketPilot's APK on the class path, so it runs as the shell user like a Shizuku user
 * service, without Shizuku. It hands a [PrivilegedService] binder to the app through [PrivilegedBinderProvider]
 * and hands it again whenever the app restarts. It exits when PocketPilot is updated or removed, since it would
 * otherwise keep running the old code.
 */
object PrivilegedMain {
    private const val TAG = "PocketPilotPrivileged"
    private const val APK_CHECK_MS = 10_000L
    private const val FIRST_RETRY_MS = 1_000L
    private const val MAX_RETRY_MS = 60_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val options =
            args
                .map { it.removePrefix("--").split('=', limit = 2) }
                .filter { it.size == 2 }
                .associate { it[0] to it[1] }
        val packageName = requireNotNull(options["package"]) { "--package missing" }
        val uid = requireNotNull(options["uid"]?.toIntOrNull()) { "--uid missing" }
        val token = requireNotNull(options["token"]) { "--token missing" }
        val apk = File(requireNotNull(options["apk"]) { "--apk missing" })

        // Out of adb's session, so the process outlives the adb connection that started it.
        runCatching { Os.setsid() }
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        val handler = Handler(Looper.getMainLooper())

        val service = PrivilegedService().apply { allowedUid = uid }
        val sender = BinderSender(handler, packageName, token, service)
        sender.send()

        val watchApk =
            object : Runnable {
                override fun run() {
                    if (!apk.exists()) {
                        Log.i(TAG, "PocketPilot was updated or removed; exiting")
                        exitProcess(0)
                    }
                    handler.postDelayed(this, APK_CHECK_MS)
                }
            }
        handler.post(watchApk)
        Log.i(TAG, "Started as uid ${Process.myUid()} for $packageName")
        Looper.loop()
    }

    /** Sends the binder to the app, again when the app's process dies, retrying with a growing wait. */
    private class BinderSender(
        private val handler: Handler,
        private val packageName: String,
        private val token: String,
        private val service: IBinder,
    ) {
        private var wait = FIRST_RETRY_MS

        /** Held so the death link stays registered. */
        private var appToken: IBinder? = null

        fun send() {
            val reply = runCatching { ProviderCall.call("$packageName.privileged", PrivilegedBinderProvider.METHOD_SEND, bundle()) }
            val app = reply.getOrNull()?.getBinder(PrivilegedBinderProvider.EXTRA_APP)
            if (app == null) {
                Log.w(TAG, "Could not reach PocketPilot; retrying in $wait ms", reply.exceptionOrNull())
                handler.postDelayed(::send, wait)
                wait = minOf(wait * 2, MAX_RETRY_MS)
                return
            }
            wait = FIRST_RETRY_MS
            appToken = app
            val linked =
                runCatching {
                    app.linkToDeath({ handler.postDelayed(::send, FIRST_RETRY_MS) }, 0)
                }.isSuccess
            if (!linked) handler.postDelayed(::send, FIRST_RETRY_MS)
        }

        private fun bundle() =
            Bundle().apply {
                putBinder(PrivilegedBinderProvider.EXTRA_BINDER, service)
                putString(PrivilegedBinderProvider.EXTRA_TOKEN, token)
                putInt(PrivilegedBinderProvider.EXTRA_VERSION, PrivilegedService.VERSION)
            }
    }

    /**
     * Calls a content provider from a process that has no Context, as the `content` shell command does:
     * IActivityManager.getContentProviderExternal, then IContentProvider.call. Both are hidden, which app_process
     * does not enforce; their parameter lists differ between Android versions, so they are matched by shape.
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private object ProviderCall {
        fun call(
            authority: String,
            method: String,
            extras: Bundle,
        ): Bundle? {
            val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
            val token = Binder()
            val get = activityManager.javaClass.methods.first { it.name == "getContentProviderExternal" }
            // (name, userId, token, tag) since Android 10; a later release may drop the tag.
            val holder =
                if (get.parameterTypes.size == 4) {
                    get.invoke(activityManager, authority, 0, token, authority)
                } else {
                    get.invoke(activityManager, authority, 0, token)
                } ?: return null
            val provider = holder.javaClass.getField("provider").get(holder) ?: return null
            try {
                return invokeCall(provider, authority, method, extras)
            } finally {
                runCatching {
                    activityManager.javaClass.methods
                        .first { it.name == "removeContentProviderExternalAsUser" && it.parameterTypes.size == 3 }
                        .invoke(activityManager, authority, token, 0)
                }
            }
        }

        private fun invokeCall(
            provider: Any,
            authority: String,
            method: String,
            extras: Bundle,
        ): Bundle? {
            val calls = provider.javaClass.methods.filter { it.name == "call" }
            // Android 12 and later: call(AttributionSource, authority, method, arg, extras).
            calls.firstOrNull { it.parameterTypes.size == 5 && it.parameterTypes[0].name == "android.content.AttributionSource" }?.let {
                val source =
                    Class
                        .forName("android.content.AttributionSource\$Builder")
                        .getConstructor(Int::class.javaPrimitiveType)
                        .newInstance(Process.myUid())
                        .let { builder -> builder.javaClass.getMethod("build").invoke(builder) }
                return it.invoke(provider, source, authority, method, null, extras) as Bundle?
            }
            // Android 11: call(callingPackage, attributionTag, authority, method, arg, extras).
            val call = calls.first { it.parameterTypes.size == 6 }
            return call.invoke(provider, null, null, authority, method, null, extras) as Bundle?
        }
    }
}
