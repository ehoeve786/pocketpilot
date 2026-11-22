package app.pocketpilot.automation

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import app.pocketpilot.core.automation.AutomationFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What a Termux command printed and how it ended. */
data class TermuxResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

/**
 * "Run a Termux command": starts bash in Termux through its RUN_COMMAND service, the interface Tasker's Termux
 * plugin uses, and in the background waits for the result Termux sends back to [TermuxResultReceiver]. Termux
 * accepts it only with its RUN_COMMAND permission, which rules grant through privileged access, and with
 * allow-external-apps = true in ~/.termux/termux.properties.
 */
class TermuxRunner(
    private val context: Context,
) {
    /**
     * Runs [command] with `bash -c`. In the background it waits up to [timeoutMs] and returns the output; in a
     * terminal ([terminal]) it opens a Termux session to watch and returns null at once.
     */
    suspend fun run(
        command: String,
        terminal: Boolean,
        timeoutMs: Long,
    ): TermuxResult? {
        if (runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isFailure) {
            throw AutomationFailure("Termux is not installed")
        }
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw AutomationFailure(
                "PocketPilot may not run Termux commands yet: turn on privileged access, or allow " +
                    "\"Run commands in Termux environment\" in PocketPilot's app permissions",
            )
        }
        val intent =
            Intent(ACTION)
                .setClassName(PACKAGE, SERVICE)
                .putExtra(EXTRA_PATH, BASH)
                .putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                .putExtra(EXTRA_WORKDIR, HOME)
                .putExtra(EXTRA_BACKGROUND, !terminal)
        if (terminal) {
            start(intent.putExtra(EXTRA_SESSION_ACTION, SESSION_SWITCH_TO_NEW))
            return null
        }
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<Bundle>()
        pending[id] = answer
        try {
            // Termux fills in the result, so the intent it is sent back with must be mutable.
            val reply =
                PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, TermuxResultReceiver::class.java).putExtra(EXTRA_ID, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or MUTABLE,
                )
            start(intent.putExtra(EXTRA_PENDING_INTENT, reply))
            val result =
                withTimeoutOrNull(timeoutMs) { answer.await() }
                    ?: throw AutomationFailure("Termux did not answer within ${timeoutMs / 1_000} s")
            // Termux's own error, such as allow-external-apps being off, comes as err and errmsg.
            val error = result.getInt(RESULT_ERR, RESULT_OK)
            if (error != RESULT_OK) {
                throw AutomationFailure("Termux refused the command: ${result.getString(RESULT_ERRMSG).orEmpty().trim()}")
            }
            return TermuxResult(
                stdout = result.getString(RESULT_STDOUT).orEmpty(),
                stderr = result.getString(RESULT_STDERR).orEmpty(),
                exitCode = result.getInt(RESULT_EXIT_CODE, -1),
            )
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Termux's service goes to the foreground itself. Android can refuse a foreground start from the background, as
     * it does Tasker's, and then a plain start, which Termux also takes, usually works.
     */
    private fun start(intent: Intent) {
        try {
            context.startForegroundService(intent)
        } catch (e: IllegalStateException) {
            try {
                context.startService(intent)
            } catch (again: Exception) {
                throw AutomationFailure("Termux could not be started: ${again.message ?: e.message}")
            }
        } catch (e: SecurityException) {
            throw AutomationFailure("Termux could not be started: ${e.message}")
        }
    }

    companion object {
        const val PACKAGE = "com.termux"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION = "com.termux.RUN_COMMAND"
        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val SESSION_SWITCH_TO_NEW = "0"
        private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val HOME = "/data/data/com.termux/files/home"

        /** The bundle Termux adds to the reply, and its keys. */
        private const val RESULT_BUNDLE = "result"
        private const val RESULT_STDOUT = "stdout"
        private const val RESULT_STDERR = "stderr"
        private const val RESULT_EXIT_CODE = "exitCode"
        private const val RESULT_ERR = "err"
        private const val RESULT_ERRMSG = "errmsg"

        /** Termux's err for a command it ran; anything else is its own failure. */
        private const val RESULT_OK = -1

        /** Before Android 12 a PendingIntent is mutable without a flag; the flag itself is from Android 12. */
        private val MUTABLE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

        internal const val EXTRA_ID = "app.pocketpilot.automation.TERMUX_ID"
        private val ids = AtomicInteger()
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()

        /** Hands Termux's reply to the run waiting for it. */
        internal fun deliver(intent: Intent) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            val result = intent.getBundleExtra(RESULT_BUNDLE) ?: Bundle()
            pending[id]?.complete(result)
        }
    }
}

/** Receives the result of a background Termux command through the PendingIntent [TermuxRunner] gave Termux. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) = TermuxRunner.deliver(intent)
}
