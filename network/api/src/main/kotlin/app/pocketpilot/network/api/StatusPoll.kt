package app.pocketpilot.network.api

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The wait between a provider's status reads. While connecting it reads every [startingMs]. Once connected it starts
 * at [connectedMs] and doubles the wait while nothing changes, up to [idleMaxMs], so a link that stays up is read
 * a few times a minute instead of every few seconds. A change, or a [poke] after the owner changed something, goes
 * back to [connectedMs]. Used by one poller at a time.
 */
class StatusPoll(
    private val startingMs: Long = STARTING_MS,
    private val connectedMs: Long = CONNECTED_MS,
    private val idleMaxMs: Long = IDLE_MAX_MS,
) {
    private val pokes = Channel<Unit>(Channel.CONFLATED)
    private var waitMs = 0L

    /** The wait after a read that found the provider [connected], and whether it [changed] since the read before. */
    fun next(
        connected: Boolean,
        changed: Boolean,
    ): Long {
        waitMs =
            when {
                !connected -> startingMs
                changed || waitMs < connectedMs -> connectedMs
                else -> (waitMs * 2).coerceAtMost(idleMaxMs)
            }
        return waitMs
    }

    /** Waits [next]'s time, or less when [poke] is called meanwhile; a poke also makes the wait after it short again. */
    suspend fun await(
        connected: Boolean,
        changed: Boolean,
    ) {
        if (withTimeoutOrNull(next(connected, changed)) { pokes.receive() } != null) waitMs = 0L
    }

    /** Reads the status again soon, after the owner changed something the status shows. */
    fun poke() {
        pokes.trySend(Unit)
    }

    companion object {
        const val STARTING_MS = 1_000L
        const val CONNECTED_MS = 5_000L
        const val IDLE_MAX_MS = 60_000L
    }
}
