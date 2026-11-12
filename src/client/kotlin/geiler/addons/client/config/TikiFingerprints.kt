package geiler.addons.client.config

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block
import java.util.Collections
import java.util.HashMap
import java.util.HashSet

/**
 * The last confirmed ground block for each Tiki waypoint. Records remain readable for config
 * compatibility; current Torrhus presence is decided by the official Hypixel Mod API.
 */
object TikiFingerprints {
    private val fingerprints = HashMap<BlockPos, Block>()

    @JvmStatic
    fun get(coord: BlockPos): Block? = fingerprints[coord]

    /** Records a waypoint fingerprint once; player changes never replace learned terrain data. */
    @JvmStatic
    fun learn(coord: BlockPos, block: Block) {
        fingerprints.putIfAbsent(coord.immutable(), block)
    }

    @JvmStatic
    fun all(): Map<BlockPos, Block> = Collections.unmodifiableMap(fingerprints)

    @JvmStatic
    fun replaceAll(data: Map<BlockPos, Block>) {
        fingerprints.clear()
        fingerprints.putAll(data)
    }

    /** Drops fingerprints whose waypoint has been removed from the saved coordinate list. */
    @JvmStatic
    fun pruneTo(liveCoords: Iterable<BlockPos>) {
        if (fingerprints.isEmpty()) return
        val keep = HashSet<BlockPos>()
        liveCoords.forEach { keep.add(it) }
        fingerprints.keys.retainAll(keep)
    }

    @JvmStatic
    fun idOf(block: Block): String = BuiltInRegistries.BLOCK.getKey(block).toString()

    /** DefaultedRegistry returns air for unknown ids; malformed identifiers retain null behavior. */
    @JvmStatic
    fun byId(id: String): Block? {
        val parsed = Identifier.tryParse(id) ?: return null
        return BuiltInRegistries.BLOCK.getValue(parsed)
    }
}
