package geiler.addons.client.module.impl

import geiler.addons.client.render.EspRenderer
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import it.unimi.dsi.fastutil.longs.LongSets
import net.minecraft.core.BlockPos
import java.util.concurrent.CancellationException

/** Immutable, worker-built geometry indexes for one completed connected Block ESP result. */
internal class BlockEspRenderCache private constructor(
    private val blockMembership: LongSet,
    outlineSegments: List<EspRenderer.OutlineSegment>,
) {
    private val outlineSegments = java.util.List.copyOf(outlineSegments)

    fun membership(): LongSet = blockMembership
    fun outline(): List<EspRenderer.OutlineSegment> = outlineSegments

    companion object {
        fun prepare(blocks: Set<BlockPos>): BlockEspRenderCache {
            val membership = LongOpenHashSet(blocks.size)
            blocks.forEachIndexed { index, block ->
                if (index and 0x3ff == 0 && Thread.currentThread().isInterrupted) throw CancellationException()
                membership.add(block.asLong())
            }
            return BlockEspRenderCache(LongSets.unmodifiable(membership), EspRenderer.blockOutlineSegments(blocks))
        }
    }
}
