package app.pocketpilot.privileged

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.pocketpilot.PocketPilotApplication
import app.pocketpilot.R
import kotlinx.coroutines.launch
import app.pocketpilot.core.designsystem.R as DsR

/**
 * Pairing with wireless debugging: the code is shown in a Settings dialog that closes when the owner switches
 * apps, so PocketPilot asks for it in a notification the owner can answer while the dialog stays open.
 */
internal object PairingNotifier {
    private const val CHANNEL = "privileged_pairing"
    private const val ID = 4_301
    internal const val KEY_CODE = "code"

    /** Shows the code prompt and opens Developer options at Wireless debugging. */
    fun begin(context: Context) {
        ask(context, context.getString(R.string.builtin_pair_notification_text))
        val settings =
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(settings)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Asks for the code, with [text] saying what to do (or what went wrong last time). */
    fun ask(
        context: Context,
        text: String,
    ) {
        val input =
            RemoteInput
                .Builder(KEY_CODE)
                .setLabel(context.getString(R.string.builtin_pair_code_label))
                .build()
        // RemoteInput fills in the reply, so this one PendingIntent has to be mutable; it is explicit.
        val reply =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, PairingReplyReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        val action =
            NotificationCompat.Action
                .Builder(0, context.getString(R.string.builtin_pair_enter_code), reply)
                .addRemoteInput(input)
                .build()
        post(context, text, action)
    }

    fun done(
        context: Context,
        text: String,
    ) = post(context, text, null)

    private fun post(
        context: Context,
        text: String,
        action: NotificationCompat.Action?,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.builtin_pair_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(DsR.drawable.core_designsystem_ic_bolt)
                .setContentTitle(context.getString(R.string.builtin_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setAutoCancel(action == null)
        if (action != null) builder.addAction(action)
        val notifications = NotificationManagerCompat.from(context)
        if (notifications.areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            notifications.notify(ID, builder.build())
        }
    }
}

/** Takes the code typed into the pairing notification and pairs in the background. */
class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val code =
            RemoteInput
                .getResultsFromIntent(intent)
                ?.getCharSequence(PairingNotifier.KEY_CODE)
                ?.toString()
                .orEmpty()
        val app = context.applicationContext as PocketPilotApplication
        PairingNotifier.done(context, context.getString(R.string.builtin_pairing))
        val pending = goAsync()
        app.appScope.launch {
            try {
                val result = app.privileged.pair(code)
                if (result.isSuccess) {
                    PairingNotifier.done(context, context.getString(R.string.builtin_paired))
                } else {
                    val reason = result.exceptionOrNull()?.message.orEmpty()
                    PairingNotifier.ask(context, context.getString(R.string.builtin_pair_failed, reason))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
