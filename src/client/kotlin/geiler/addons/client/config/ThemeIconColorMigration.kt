package geiler.addons.client.config

/** Moves the module icon tint from General into Theme without overwriting a Theme value. */
object ThemeIconColorMigration {
    /**
     * Copies the legacy RGBA entry only when the Theme key has never been saved.
     *
     * A present Theme key always wins, even if malformed and later replaced by the setting's
     * declared default. The array is copied so later edits to the parsed legacy entry cannot alter
     * the migrated value.
     */
    @JvmStatic
    fun migrate(colors: MutableMap<String, IntArray?>?, themeKey: String?, legacyKey: String?): Boolean {
        if (colors == null || themeKey == null || legacyKey == null || colors.containsKey(themeKey)) {
            return false
        }
        val legacy = colors[legacyKey] ?: return false
        if (legacy.size != 4) return false
        colors[themeKey] = legacy.copyOf()
        return true
    }
}
