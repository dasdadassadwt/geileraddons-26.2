package geiler.addons.client.farming

import java.util.ArrayList
import java.util.Collections

/** Selects which measured Slayer durations are shown in the HUD. */
enum class SlayerTimeDisplay {
    SPAWN,
    KILL,
    BOTH;

    fun lines(spawnDuration: String?, averageKillDuration: String?): List<String> {
        val result = ArrayList<String>(2)
        if (this == SPAWN || this == BOTH) result.add("Boss spawned  $spawnDuration")
        if (this == KILL || this == BOTH) result.add("Average kill time  $averageKillDuration")
        return Collections.unmodifiableList(result)
    }
}
