package geiler.addons.client.config

import net.minecraft.core.BlockPos
import java.util.ArrayList
import java.util.Collections

/**
 * Tiki Helper's waypoint coordinates. The config layer and manager screen use this client-thread
 * owned list directly, so callers receive a read-only view and mutations stay explicit.
 */
object TikiCoords {
    private val defaults = arrayOf(
        intArrayOf(-731, 134, 153), intArrayOf(-731, 130, 172), intArrayOf(-729, 143, 179),
        intArrayOf(-742, 136, 231), intArrayOf(-759, 125, 245), intArrayOf(-735, 128, 268),
        intArrayOf(-714, 135, 290), intArrayOf(-700, 132, 288), intArrayOf(-667, 133, 263),
        intArrayOf(-661, 125, 245), intArrayOf(-625, 133, 283), intArrayOf(-614, 124, 281),
        intArrayOf(-596, 128, 284), intArrayOf(-687, 131, 219), intArrayOf(-637, 142, 161),
        intArrayOf(-615, 143, 187), intArrayOf(-591, 139, 175), intArrayOf(-573, 136, 184),
        intArrayOf(-560, 144, 191), intArrayOf(-544, 139, 208), intArrayOf(-545, 131, 213),
        intArrayOf(-600, 137, 235), intArrayOf(-587, 152, 239), intArrayOf(-603, 172, 231),
        intArrayOf(-674, 143, 146),
    )
    private val coords = ArrayList<BlockPos>()

    @JvmStatic
    fun all(): List<BlockPos> = Collections.unmodifiableList(coords)

    @JvmStatic
    fun size(): Int = coords.size

    /** Returns false if that exact position is already in the list. */
    @JvmStatic
    fun add(pos: BlockPos): Boolean {
        if (coords.contains(pos)) return false
        coords.add(pos)
        return true
    }

    @JvmStatic
    fun remove(index: Int) {
        if (index in coords.indices) coords.removeAt(index)
    }

    @JvmStatic
    fun replaceAll(values: List<BlockPos>) {
        coords.clear()
        coords.addAll(values)
    }

    /** Seed defaults only when config loading found no saved waypoint list. */
    @JvmStatic
    fun seedDefaults() {
        coords.clear()
        defaults.forEach { coords.add(BlockPos(it[0], it[1], it[2])) }
    }
}
