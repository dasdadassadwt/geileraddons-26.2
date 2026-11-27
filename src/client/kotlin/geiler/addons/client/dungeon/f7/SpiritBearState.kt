package geiler.addons.client.dungeon.f7

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.util.Locale

/**
 * The Floor 4 boss lamp counter, line for line after Odin's `SpiritBear.kt` (BSD-3-Clause, Copyright (c) odtheking):
 * a lamp turning from coal block to sea lantern is one kill, and the last lamp starts the Spirit Bear spawn.
 */
class SpiritBearState {
    private var spawnTick = -1L

    var kills = 0
        private set

    fun kills(): Int = kills

    fun reset() {
        kills = 0
        spawnTick = -1L
    }

    /** True when the change was a boss-room lamp and was counted. [now] is the server tick. */
    fun onBlockChange(x: Int, y: Int, z: Int, old: Block, updated: Block, master: Boolean, now: Long): Boolean {
        if (y != LAMP_Y || pack(x, z) !in (if (master) M4_LAMPS else F4_LAMPS)) return false
        val last = x == LAST_X && z == LAST_Z
        if (old === Blocks.COAL_BLOCK && updated === Blocks.SEA_LANTERN) {
            if (kills < maxKills(master)) kills++
            if (last) spawnTick = now + SPAWN_TICKS
            return true
        }
        if (old === Blocks.SEA_LANTERN && updated === Blocks.COAL_BLOCK) {
            if (kills > 0) kills--
            if (last) spawnTick = -1L
            return true
        }
        return false
    }

    fun text(master: Boolean, now: Long): String {
        val value = when {
            spawnTick < 0L -> "§d$kills/${maxKills(master)}"
            spawnTick > now -> "§e${String.format(Locale.ROOT, "%.2f", (spawnTick - now) / 20f)}s"
            else -> "§aAlive!"
        }
        return "§6Bear: $value"
    }

    companion object {
        const val SPAWN_TICKS = 68L
        private const val LAMP_Y = 77
        private const val LAST_X = 7
        private const val LAST_Z = 34

        fun maxKills(master: Boolean): Int = if (master) 30 else 25

        private fun pack(x: Int, z: Int): Int = (x shl 16) or (z and 0xFFFF)

        private fun lamps(vararg xz: Int): Set<Int> = (xz.indices step 2).mapTo(HashSet()) { pack(xz[it], xz[it + 1]) }

        private val F4_LAMPS = lamps(
            -3, 33, -9, 31, -16, 26, -20, 20, -23, 13, -24, 6, -24, 0, -22, -7, -18, -13, -12, -19,
            -5, -22, 1, -24, 8, -24, 14, -23, 21, -19, 27, -14, 31, -8, 33, -1, 34, 5, 33, 12,
            31, 19, 27, 25, 20, 30, 14, 33, 7, 34,
        )
        private val M4_LAMPS = lamps(
            -2, 33, -7, 32, -13, 28, -17, 24, -21, 18, -23, 13, -24, 7, -24, 2, -23, -4, -21, -9,
            -17, -14, -12, -19, -6, -22, -1, -23, 5, -24, 10, -24, 16, -22, 21, -19, 27, -15, 30, -10,
            32, -5, 34, 1, 34, 7, 33, 12, 31, 18, 28, 23, 23, 28, 18, 31, 12, 33, 7, 34,
        )
    }
}
