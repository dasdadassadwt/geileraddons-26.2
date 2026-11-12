package geiler.addons.client.dungeon

/** One-time persisted defaults for the reference-style room map. */
object DungeonRoomOverlaySettingsMigration {
    const val MIGRATION_ID = "dungeon-room-map-reveal-and-labels-v1"

    /**
     * Migrates the old defaults once. Explicit values already matching the new choices are kept,
     * and the persisted marker prevents any later user choice from being overridden on load.
     */
    @JvmStatic
    fun migrate(
        toggles: MutableMap<String, Boolean>?,
        completedMigrations: MutableSet<String>?,
        moduleConfigName: String?,
    ): Boolean {
        if (toggles == null || completedMigrations == null || moduleConfigName.isNullOrBlank() ||
            completedMigrations.contains(DungeonRoomOverlaySettingsMigration.MIGRATION_ID)
        ) return false

        val revealKey = "$moduleConfigName.Reveal Unopened Rooms"
        val checkmarksKey = "$moduleConfigName.Show Checkmarks"
        if (toggles[revealKey] != true) toggles[revealKey] = true
        if (toggles[checkmarksKey] != false) toggles[checkmarksKey] = false
        completedMigrations.add(DungeonRoomOverlaySettingsMigration.MIGRATION_ID)
        return true
    }
}
