package app.pocketpilot.server.oauth

import app.pocketpilot.core.common.Clock

/**
 * Blocks an address after repeated failed authorizations (spec section 8: five failures within ten
 * minutes block that address for an hour).
 */
class Lockout(
    private val clock: Clock = Clock.System,
    private val maxFailures: Int = 5,
    private val windowMs: Long = 10 * 60 * 1000L,
    private val blockMs: Long = 60 * 60 * 1000L,
) {
    private val failures = HashMap<String, ArrayDeque<Long>>()
    private val blockedUntil = HashMap<String, Long>()

    @Synchronized
    fun isBlocked(address: String): Boolean {
        val until = blockedUntil[address] ?: return false
        if (until > clock.nowMillis()) return true
        blockedUntil.remove(address)
        return false
    }

    @Synchronized
    fun recordFailure(address: String) {
        val now = clock.nowMillis()
        if (failures.size >= MAX_TRACKED && address !in failures) prune(now)
        val recent = failures.getOrPut(address) { ArrayDeque() }
        while (recent.isNotEmpty() && now - recent.first() > windowMs) recent.removeFirst()
        recent.addLast(now)
        if (recent.size >= maxFailures) {
            blockedUntil[address] = now + blockMs
            failures.remove(address)
        }
    }

    /** Forgets addresses whose failures have all aged out or whose block has ended, then the oldest if still full. */
    private fun prune(now: Long) {
        failures.entries.removeAll { (_, recent) -> recent.isEmpty() || now - recent.last() > windowMs }
        blockedUntil.entries.removeAll { (_, until) -> until <= now }
        while (failures.size >= MAX_TRACKED) failures.remove(failures.keys.first())
    }

    private companion object {
        /** Client ids are unauthenticated, so the number of tracked keys is capped to bound memory. */
        const val MAX_TRACKED = 10_000
    }
}
