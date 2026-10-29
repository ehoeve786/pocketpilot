package app.pocketpilot.agent.providers.api

import java.net.URI

private val ON_THIS_PHONE = setOf("localhost", "127.0.0.1", "[::1]")

/**
 * A provider key goes only over https, or to a server on this phone. Plain http would send the key in the
 * clear to whatever host the owner typed.
 */
fun keyMayBeSentTo(
    baseUrl: String,
    apiKey: String?,
): Boolean {
    if (apiKey.isNullOrBlank() || baseUrl.startsWith("https://")) return true
    return onThisPhone(baseUrl)
}

/**
 * Plain http sends prompts, screen text and answers in the clear. It is used only for a server on this phone,
 * or for a server on the home network that the owner allowed for its profile.
 */
fun cleartextMayBeUsed(
    baseUrl: String,
    allowedByOwner: Boolean,
): Boolean = baseUrl.startsWith("https://") || allowedByOwner || onThisPhone(baseUrl)

private fun onThisPhone(baseUrl: String): Boolean {
    val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return false
    return host in ON_THIS_PHONE
}
