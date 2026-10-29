package app.pocketpilot.core.automation

/**
 * Finds a delivery address on a screen that an app has read, by the resource ids each app gives its address
 * views. The keys of a screen read are full resource ids, so no package check is needed.
 */
object ScreenAddress {
    /**
     * One app's address views. [street] is required. [locality] is a second line, such as the city, and
     * [prefix] is a name shown before the street when it starts with a number, such as a restaurant's number.
     */
    internal data class Recipe(
        val street: String,
        val locality: String? = null,
        val prefix: String? = null,
    )

    internal val RECIPES =
        listOf(
            // DoorDash Dasher: the street line can carry the unit, and the second line has the city.
            Recipe(
                street = "com.doordash.driverapp:id/address_line_1",
                locality = "com.doordash.driverapp:id/address_line_2",
            ),
            // Uber Driver: the street view, with the point of interest first when it is a number.
            Recipe(
                street = "com.ubercab.driver:id/ub__nav_address_view_address_text",
                prefix = "com.ubercab.driver:id/ub__nav_address_view_poi_text",
            ),
        )

    /**
     * The first address the [lookup] finds, or null. [lookup] gives the value of a resource id, or null when the
     * screen has no such view. Values with a placeholder left in them count as missing.
     */
    fun find(lookup: (String) -> String?): String? {
        RECIPES.forEach { recipe ->
            val street = value(lookup, recipe.street) ?: return@forEach
            val locality = recipe.locality?.let { value(lookup, it) }
            val prefix = recipe.prefix?.let { value(lookup, it) }?.takeIf { it.first().isDigit() }
            val line = clean(street)
            return when {
                prefix != null -> "$prefix, $line"
                locality != null -> "$line, $locality"
                else -> line
            }
        }
        return null
    }

    /**
     * Some apps put the unit before the street number, such as "666 1300- Burrard St" for unit 1300 at 666
     * Burrard. That becomes "666 Burrard St", since the unit is already on its own line.
     */
    fun clean(street: String): String = UNIT_IN_STREET.replace(street.trim()) { match -> "${match.groupValues[1]} ${match.groupValues[3]}" }

    private fun value(
        lookup: (String) -> String?,
        id: String,
    ): String? = lookup(id)?.trim()?.takeIf { it.isNotEmpty() && Placeholders.names(it).isEmpty() }

    private val UNIT_IN_STREET = Regex("""^(\d+)\s+(\d+)-\s*(.+)$""")
}
