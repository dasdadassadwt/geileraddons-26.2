package geiler.addons.client.macros.pathing

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.Util

/**
 * What the planner has read from the world, kept from one search to the next.
 *
 * A search used to read every block it came near afresh: the shape of the block, the stands it makes, the walls beside
 * them. The world hardly ever changes between two searches - the next one comes a few ticks later and looks at much the
 * same ground - so that was the same work done again and again. Here it is done once and remembered, in sections of
 * 16 x 16 x 16 blocks, and thrown away again in exactly the places where the world says it changed ([WorldWatch]).
 *
 * A stand is made from the blocks around it: one to each side, two below and two above. Those are the ones to forget
 * when a block changes. And because a server can change a block without the client being told in a way that is noticed,
 * no section is trusted for longer than [TTL_MILLIS]: it is read again after that.
 *
 * What is kept does not depend on where the player may go, only on the body of the player and on whether water and
 * swimming count as places to stand, so that is all the cache is keyed by. The region and the other limits are applied by
 * [Terrain] on every look.
 */
class TerrainCache private constructor(private val level: ClientLevel, private val profile: Profile) {
    /** The player and the rules a stand is made under; a cache is only ever used for one of these. */
    data class Profile(val halfWidth: Double, val height: Double, val eyeHeight: Double, val water: Boolean, val swim: Boolean)

    /**
     * What decides which moves there are between stands, and what they cost: the moves kept in the sections are for one
     * set of these, and are thrown away when a terrain with another asks.
     */
    data class Rules(
        val jumps: Boolean, val diagonals: Boolean, val maxFall: Double, val jumpHeight: Double, val sprint: Boolean, val jumpCost: Int = 100,
    )

    /** One 16 x 16 x 16 piece of the world's worth of blocks and stands, and the moves from those stands. */
    class Section(val bornMillis: Long) {
        val cells = arrayOfNulls<Terrain.Cell>(SECTION_SIZE)
        val stands = arrayOfNulls<Any>(SECTION_SIZE)

        /** The moves from each stand, made when first asked for; null until then. */
        var moves: Array<Terrain.Edges?>? = null

        /** Which rules [moves] were made under. */
        var movesRules = 0
    }

    private val sections = Long2ObjectOpenHashMap<Section>()

    /** Counts up whenever a section is taken away or replaced, so that nobody goes on holding one that is gone. */
    var epoch = 0
    private var rules: Rules? = null
    private var rulesId = 0

    /** The furthest a move has reached down and up under any rules seen, for knowing which moves a changed block is part of. */
    private var widestFall = 4
    private var widestRise = 2
    var now = Util.getMillis()
    private var sweptMillis = Util.getMillis()

    /** The number of this set of rules: the same as last time if they are the same, a new one if not. */
    fun rulesId(wanted: Rules): Int {
        if (wanted != rules) {
            rules = wanted
            rulesId++
            widestFall = Math.max(widestFall, Math.ceil(wanted.maxFall).toInt() + 1)
            widestRise = Math.max(widestRise, Math.ceil(wanted.jumpHeight).toInt() + 1)
        }
        return rulesId
    }

    /** The section holding this block, new and empty if there is none yet or the one there has got old. */
    fun section(x: Int, y: Int, z: Int): Section {
        val key = BlockPos.asLong(x shr 4, y shr 4, z shr 4)
        var section = sections.get(key)
        if (section == null || now - section.bornMillis > TTL_MILLIS) {
            if (section != null) epoch++
            section = Section(now)
            sections.put(key, section)
        }
        return section
    }

    companion object {
        /** The longest a section is kept, whatever it has been told. */
        private const val TTL_MILLIS = 30_000L

        /** How often sections that have got old without being asked for are thrown away. */
        private const val SWEEP_MILLIS = 30_000L

        /** A block change reaches stands this far to each side, and this far below and above. */
        private const val REACH_SIDE = 1
        private const val REACH_DOWN = 2
        private const val REACH_UP = 2
        const val SECTION_SIZE = 4096

        /** The moves of a stand are made from the stands beside it, and those from the blocks beside them. */
        private const val MOVE_REACH_SIDE = 2
        private const val MOVE_REACH_MARGIN = 4

        private var current: TerrainCache? = null

        /** The cache for this world and this kind of player, a new one if the last was for another. */
        @JvmStatic
        fun of(level: ClientLevel, profile: Profile): TerrainCache {
            var cache = current
            if (cache == null || cache.level !== level || cache.profile != profile) {
                cache = TerrainCache(level, profile)
                current = cache
            }
            cache.now = Util.getMillis()
            if (cache.now - cache.sweptMillis > SWEEP_MILLIS) {
                // Sections nobody asked for since they got old are never replaced, only left: a long walk would keep every
                // place it has ever been.
                cache.sweptMillis = cache.now
                val now = cache.now
                cache.sections.values.removeIf { section -> now - section.bornMillis > 2 * TTL_MILLIS }
                cache.epoch++
            }
            return cache
        }

        /** Forgets everything, for tests and for when the world is swapped for another. */
        @JvmStatic
        fun clear() {
            current = null
        }

        @JvmStatic
        fun index(x: Int, y: Int, z: Int): Int = (x and 15) or ((z and 15) shl 4) or ((y and 15) shl 8)

        @JvmStatic
        fun blockChanged(level: ClientLevel, x: Int, y: Int, z: Int) {
            val cache = current
            if (cache == null || cache.level !== level) return
            val own = cache.sections.get(BlockPos.asLong(x shr 4, y shr 4, z shr 4))
            if (own != null) own.cells[index(x, y, z)] = null
            for (dx in -REACH_SIDE..REACH_SIDE) {
                for (dz in -REACH_SIDE..REACH_SIDE) {
                    for (dy in -REACH_UP..REACH_DOWN + 1) {
                        val sx = x + dx
                        val sy = y + dy
                        val sz = z + dz
                        val section = cache.sections.get(BlockPos.asLong(sx shr 4, sy shr 4, sz shr 4))
                        if (section != null) section.stands[index(sx, sy, sz)] = null
                    }
                }
            }
            // The moves: from every stand that has one of those stands as a neighbour, or the block itself in its way. That is
            // two blocks to each side, and as far above the block as a move drops and below it as one climbs.
            val down = cache.widestRise + MOVE_REACH_MARGIN
            val up = cache.widestFall + MOVE_REACH_MARGIN
            for (dx in -MOVE_REACH_SIDE..MOVE_REACH_SIDE) {
                for (dz in -MOVE_REACH_SIDE..MOVE_REACH_SIDE) {
                    var section: Section? = null
                    var sectionY = Int.MIN_VALUE
                    for (sy in y - down..y + up) {
                        if ((sy shr 4) != sectionY) {
                            sectionY = sy shr 4
                            section = cache.sections.get(BlockPos.asLong((x + dx) shr 4, sectionY, (z + dz) shr 4))
                        }
                        val moves = section?.moves
                        if (moves != null) moves[index(x + dx, sy, z + dz)] = null
                    }
                }
            }
        }

        /** A chunk arrived or left: the sections of it and of the eight around it, which have stands that reach across the border. */
        @JvmStatic
        fun chunkChanged(level: ClientLevel, chunkX: Int, chunkZ: Int) {
            val cache = current
            if (cache == null || cache.level !== level) return
            for (cx in chunkX - 1..chunkX + 1) {
                for (cz in chunkZ - 1..chunkZ + 1) {
                    for (sy in level.minSectionY..level.maxSectionY) {
                        cache.sections.remove(BlockPos.asLong(cx, sy, cz))
                    }
                }
            }
            cache.epoch++
        }
    }
}
