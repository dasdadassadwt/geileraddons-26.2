package geiler.addons.client.dungeon

import java.util.Locale

/** The five Catacombs classes, as written by Party Finder and profile data. */
enum class DungeonClass(private val displayLabel: String) {
    TANK("Tank"),
    HEALER("Healer"),
    MAGE("Mage"),
    BERSERK("Berserk"),
    ARCHER("Archer");

    fun displayName(): String = displayLabel

    companion object {
        @JvmStatic
        fun parse(value: String?): DungeonClass? {
            if (value == null) return null
            val normalized = value.trim { it <= '\u0020' }.lowercase(Locale.ROOT)
            return values().firstOrNull { dungeonClass ->
                dungeonClass.name.lowercase(Locale.ROOT) == normalized ||
                    dungeonClass.displayLabel.lowercase(Locale.ROOT) == normalized
            }
        }
    }
}
