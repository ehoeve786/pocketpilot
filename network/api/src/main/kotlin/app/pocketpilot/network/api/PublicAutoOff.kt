package app.pocketpilot.network.api

/**
 * Decides when public access turns itself off: after [minutes] without requests (spec section 7,
 * default 60). The clock starts when public access is turned on or the provider starts, and every
 * request starts it again. Zero minutes keeps public access on.
 */
class PublicAutoOff(
    minutes: Int = DEFAULT_MINUTES,
) {
    /** Changed by the owner in Settings; read on every status poll. */
    @Volatile var minutes: Int = minutes

    @Volatile private var idleSinceMs = 0L

    /** Starts the clock again, such as when public access is turned on. */
    fun restart(nowMs: Long) {
        idleSinceMs = nowMs
    }

    /** True once [minutes] have passed since the later of [restart] and [lastRequestMs] (0 when there was none). */
    fun expired(
        nowMs: Long,
        lastRequestMs: Long,
    ): Boolean = minutes > 0 && nowMs - maxOf(idleSinceMs, lastRequestMs) >= minutes * MS_PER_MINUTE

    companion object {
        const val DEFAULT_MINUTES = 60

        /** The choices Settings offers; 0 keeps public access on. */
        val CHOICES = listOf(60, 8 * 60, 24 * 60, 0)
        private const val MS_PER_MINUTE = 60_000L
    }
}
