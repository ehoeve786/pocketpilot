package app.pocketpilot.capability.shizuku

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * Receives the binder of PocketPilot's own privileged process ([PrivilegedMain]). Only the shell and root users
 * hold INTERACT_ACROSS_USERS_FULL, which the manifest requires, and the caller must also present the token
 * PrivilegedStarter gave the process, so no other app can hand PocketPilot a fake service.
 */
class PrivilegedBinderProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (method != METHOD_SEND || extras == null) return null
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) return null
        val context = context ?: return null
        val token = extras.getString(EXTRA_TOKEN) ?: return null
        val expected = PrivilegedToken.current(context) ?: return null
        if (!MessageDigest.isEqual(token.toByteArray(), expected.toByteArray())) return null
        val binder = extras.getBinder(EXTRA_BINDER) ?: return null
        PrivilegedBinderHub.receive(binder, extras.getInt(EXTRA_VERSION))
        return Bundle().apply { putBinder(EXTRA_APP, PrivilegedBinderHub.appToken) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val METHOD_SEND = "sendBinder"
        const val EXTRA_BINDER = "binder"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_VERSION = "version"
        const val EXTRA_APP = "app"
    }
}

/** A binder that arrived from PocketPilot's own privileged process, and the contract version it reported. */
class ReceivedBinder(
    val binder: IBinder,
    val version: Int,
)

/** Where [PrivilegedBinderProvider] leaves the binder for [ShizukuConnection]; the provider starts before DI. */
object PrivilegedBinderHub {
    private val mutableLatest = MutableStateFlow<ReceivedBinder?>(null)
    val latest: StateFlow<ReceivedBinder?> = mutableLatest.asStateFlow()

    /** Lives as long as the app's process; the privileged process watches it to know when to send again. */
    internal val appToken: IBinder = Binder()

    /** Called on a binder thread with each binder that arrives. */
    @Volatile
    internal var onReceived: ((ReceivedBinder) -> Unit)? = null

    internal fun receive(
        binder: IBinder,
        version: Int,
    ) {
        val received = ReceivedBinder(binder, version)
        mutableLatest.value = received
        onReceived?.invoke(received)
    }
}

/** The secret a started privileged process must present, stored so it survives the app's own restarts. */
object PrivilegedToken {
    private const val PREFS = "pocketpilot_privileged"
    private const val KEY = "token"

    fun current(context: Context): String? = prefs(context).getString(KEY, null)

    /** A fresh token for the next start; the old process can no longer hand over its binder. */
    fun renew(context: Context): String {
        val bytes = ByteArray(TOKEN_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY, token).commit()
        return token
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TOKEN_BYTES = 24
}
