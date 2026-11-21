package app.pocketpilot.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.pocketpilot.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** A question a rule asks the owner: text to type, or one of [options] to pick when it has any. */
data class Prompt(
    val id: String,
    val title: String,
    val message: String = "",
    val default: String = "",
    val options: List<String> = emptyList(),
)

/**
 * The "Ask for text" and "Choose from a list" steps: shows [PromptActivity] and waits for the answer. The activity
 * opens at once when Android lets PocketPilot start it, as after a tap on a home screen icon or while its
 * Accessibility service is on; a notification opens it otherwise.
 */
class Prompts(
    private val context: Context,
) {
    private val mutablePending = MutableStateFlow<List<Prompt>>(emptyList())
    val pending: StateFlow<List<Prompt>> = mutablePending.asStateFlow()
    private val answers = HashMap<String, CompletableDeferred<String?>>()

    suspend fun askText(
        title: String,
        message: String,
        default: String,
    ): String? = ask(Prompt(UUID.randomUUID().toString(), title, message, default))

    suspend fun choose(
        title: String,
        options: List<String>,
    ): String? = ask(Prompt(UUID.randomUUID().toString(), title, options = options))

    /** The owner's answer to [id]; null is Cancel. */
    fun answer(
        id: String,
        value: String?,
    ) {
        synchronized(answers) { answers[id] }?.complete(value)
    }

    private suspend fun ask(prompt: Prompt): String? {
        val answer = CompletableDeferred<String?>()
        synchronized(answers) { answers[prompt.id] = answer }
        mutablePending.update { it + prompt }
        open(prompt)
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { answer.await() }
        } finally {
            synchronized(answers) { answers.remove(prompt.id) }
            mutablePending.update { list -> list.filterNot { it.id == prompt.id } }
            if (mutablePending.value.isEmpty()) context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    private fun open(prompt: Prompt) {
        val intent = Intent(context, PromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        // Android may block the start without saying so, so the notification is always there to open it.
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.prompts_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val tap = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(
            NOTIFICATION_ID,
            Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(prompt.title)
                .setContentText(context.getString(R.string.prompts_text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "prompts"
        const val NOTIFICATION_ID = 8
        const val TIMEOUT_MS = 5 * 60_000L
    }
}
