package app.pocketpilot.core.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Replaces sensitive argument values (text typed, clipboard, file contents, secrets) with their length
 * and a SHA-256 prefix, so the audit log shows that something was sent without storing it.
 */
class Redactor(
    private val sensitiveKeys: Set<String> = DEFAULT_SENSITIVE_KEYS,
    private val sensitiveWords: List<String> = DEFAULT_SENSITIVE_WORDS,
) {
    fun redact(arguments: JsonObject): JsonObject = JsonObject(arguments.mapValues { (key, value) -> redactValue(key, value) })

    private fun redactValue(
        key: String,
        value: JsonElement,
    ): JsonElement =
        when {
            isSensitive(key) -> summary((value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString())
            value is JsonObject -> redact(value)
            value is JsonArray -> JsonArray(value.map { redactValue(key, it) })
            else -> value
        }

    /** A key is sensitive when it is one of [sensitiveKeys] or contains one of [sensitiveWords], in any case. */
    private fun isSensitive(key: String): Boolean {
        val lower = key.lowercase()
        return lower in sensitiveKeys || sensitiveWords.any { lower.contains(it) }
    }

    /**
     * The length, and a hash prefix for values long enough that the hash cannot be reversed by guessing. A short
     * value, such as a PIN, is shown by its length alone, since its hash would reveal it.
     */
    private fun summary(raw: String): JsonObject =
        buildJsonObject {
            put("redacted", true)
            put("length", raw.length)
            if (raw.length >= MIN_HASHED_LENGTH) put("sha256", sha256(raw).take(HASH_PREFIX))
        }

    private fun sha256(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val HASH_PREFIX = 16
        private const val MIN_HASHED_LENGTH = 20

        /**
         * Keys whose whole value is sensitive: typed text, clipboard, file contents, replies, HTTP headers and
         * bodies, and notification extras.
         */
        val DEFAULT_SENSITIVE_KEYS: Set<String> =
            setOf(
                "text",
                "clipboard",
                "content",
                "contents",
                "reply",
                "pin",
                "header",
                "headers",
                "body",
                "data",
                "extras",
            )

        /** Any key containing one of these words is sensitive, so `accessToken` and `x-api-key` are covered too. */
        val DEFAULT_SENSITIVE_WORDS: List<String> =
            listOf("password", "secret", "token", "auth", "cookie", "credential", "apikey", "api_key", "api-key")

        /** True when [value] is a summary produced by [redact]. */
        fun isRedacted(value: JsonElement): Boolean = (value as? JsonObject)?.get("redacted") == JsonPrimitive(true)
    }
}
