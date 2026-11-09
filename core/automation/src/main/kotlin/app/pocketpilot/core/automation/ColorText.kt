package app.pocketpilot.core.automation

/** Colours written as text in a rule: "#RRGGBB", or "#AARRGGBB" with transparency; the "#" may be left out. */
object ColorText {
    private val HEX = Regex("#?([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})")

    /** The colour as an ARGB int, opaque when no alpha is given; null when [text] is not a colour. */
    fun argb(text: String): Int? {
        val digits = HEX.matchEntire(text.trim())?.groupValues?.get(1) ?: return null
        val value = digits.toLong(16)
        return (if (digits.length == 6) value or 0xFF000000L else value).toInt()
    }

    /** [argb] as "#RRGGBB" when opaque, else "#AARRGGBB", in capitals. */
    fun format(argb: Int): String {
        val opaque = (argb ushr 24) == 0xFF
        return if (opaque) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb)
    }
}
