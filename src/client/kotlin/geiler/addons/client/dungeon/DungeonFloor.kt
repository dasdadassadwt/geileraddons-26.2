package geiler.addons.client.dungeon

import geiler.addons.client.chat.ChatStringSemantics
import java.util.Locale

/** Catacombs floors shared across Party Finder and dungeon features. */
enum class DungeonFloor(
    private val label: String,
    private val isMaster: Boolean,
    private val floorNumber: Int,
) {
    ENTRANCE("Entrance", false, 0),
    F1("F1", false, 1),
    F2("F2", false, 2),
    F3("F3", false, 3),
    F4("F4", false, 4),
    F5("F5", false, 5),
    F6("F6", false, 6),
    F7("F7", false, 7),
    M1("M1", true, 1),
    M2("M2", true, 2),
    M3("M3", true, 3),
    M4("M4", true, 4),
    M5("M5", true, 5),
    M6("M6", true, 6),
    M7("M7", true, 7),
    ;

    fun displayName(): String = label

    fun master(): Boolean = isMaster

    fun number(): Int = floorNumber

    companion object {
        /** Floor number for a roman or arabic digit 1-7 ("VII", "7"), or 0 when it is neither. */
        @JvmStatic
        fun romanFloor(value: String?): Int = when (value?.uppercase(Locale.ROOT)) {
            "I", "1" -> 1
            "II", "2" -> 2
            "III", "3" -> 3
            "IV", "4" -> 4
            "V", "5" -> 5
            "VI", "6" -> 6
            "VII", "7" -> 7
            else -> 0
        }

        @JvmStatic
        fun parse(value: String?): DungeonFloor? {
            val normalized = value?.let { ChatStringSemantics.trim(it) }?.uppercase(Locale.ROOT) ?: return null
            if (normalized == "ENTRANCE" || normalized == "ENT" || normalized == "F0") return ENTRANCE
            return entries.firstOrNull { it.label == normalized }
        }
    }
}
