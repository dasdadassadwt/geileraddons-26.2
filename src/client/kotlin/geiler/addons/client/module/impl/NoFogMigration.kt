package geiler.addons.client.module.impl

/** No Fog was merged into No Render's Fog group; carries its saved toggles over once. */
object NoFogMigration {
    private const val OLD_MODULE = "No Fog"
    private const val NEW_MODULE = "No Render"

    /** Old setting name to its No Render setting name. */
    private val RENAMED = mapOf(
        "Underwater" to "Underwater Fog", "Lava" to "Lava Fog", "Air" to "Air Fog", "Distance" to "Distance Fog",
    )

    /**
     * New setting name to the value to apply: empty unless No Fog was enabled; absent old toggles count as on (their
     * default); a toggle already saved under No Render is left alone.
     */
    @JvmStatic
    fun toggleValues(toggles: Map<String, Boolean?>, enabled: Map<String, Boolean?>): Map<String, Boolean> {
        if (enabled[OLD_MODULE] != true) return emptyMap()
        val values = LinkedHashMap<String, Boolean>()
        for ((old, new) in RENAMED) {
            if (!toggles.containsKey("$NEW_MODULE.$new")) values[new] = toggles["$OLD_MODULE.$old"] ?: true
        }
        return values
    }

    /** No Render is switched on only when it has no saved state of its own. */
    @JvmStatic
    fun enableNoRender(enabled: Map<String, Boolean?>): Boolean =
        enabled[OLD_MODULE] == true && !enabled.containsKey(NEW_MODULE)
}
