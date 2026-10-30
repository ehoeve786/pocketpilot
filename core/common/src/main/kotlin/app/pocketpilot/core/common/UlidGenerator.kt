package app.pocketpilot.core.common

import java.security.SecureRandom

/**
 * Generates ULIDs: 48-bit millisecond timestamp plus 80 random bits, Crockford base32, 26 chars.
 * Every persisted record has one (spec conventions). Lexicographic order follows creation time.
 */
class UlidGenerator(
    private val clock: Clock = Clock.System,
    private val random: java.util.Random = SecureRandom(),
) {
    private var lastTime = -1L
    private var lastDigits = IntArray(RANDOM_CHARS)

    /**
     * Within one millisecond the random part counts up from the last id, so ids from this generator sort in the
     * order they were made, as the ULID spec asks for. A new millisecond starts again from random digits.
     */
    @Synchronized
    fun next(): String {
        val time = clock.nowMillis()
        require(time in 0..MAX_TIME) { "Timestamp out of ULID range: $time" }
        val digits = if (time == lastTime) incremented(lastDigits) else IntArray(RANDOM_CHARS) { random.nextInt(32) }
        lastTime = time
        lastDigits = digits
        val chars = CharArray(LENGTH)
        var t = time
        for (i in TIME_CHARS - 1 downTo 0) {
            chars[i] = ALPHABET[(t and 31).toInt()]
            t = t shr 5
        }
        for (i in TIME_CHARS until LENGTH) {
            chars[i] = ALPHABET[digits[i - TIME_CHARS]]
        }
        return String(chars)
    }

    private fun incremented(digits: IntArray): IntArray {
        val next = digits.copyOf()
        var i = next.size - 1
        while (i >= 0 && next[i] == 31) {
            next[i] = 0
            i--
        }
        check(i >= 0) { "The random part of the ids is used up for this millisecond" }
        next[i]++
        return next
    }

    companion object {
        const val LENGTH = 26
        private const val TIME_CHARS = 10
        private const val RANDOM_CHARS = LENGTH - TIME_CHARS
        private const val MAX_TIME = (1L shl 48) - 1
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** Milliseconds since the epoch encoded in [ulid]. */
        fun timestampOf(ulid: String): Long {
            require(ulid.length == LENGTH) { "ULID must be $LENGTH characters" }
            return ulid.take(TIME_CHARS).fold(0L) { acc, c ->
                val digit = ALPHABET.indexOf(c)
                require(digit >= 0) { "Invalid ULID character '$c'" }
                (acc shl 5) or digit.toLong()
            }
        }
    }
}
