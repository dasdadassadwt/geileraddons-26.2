package geiler.addons.client.sim

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * Client-side blocks that can be taken back out. [set] only ever covers air; [replace] covers what is there and puts it
 * back. A position the server has changed since is left alone.
 */
class FakeBlocks(private val world: World) {
    interface World {
        fun get(pos: BlockPos): BlockState
        fun set(pos: BlockPos, state: BlockState)
    }

    private class Placed(val ours: BlockState, val original: BlockState)

    private val placed = LinkedHashMap<BlockPos, Placed>()

    fun free(positions: List<BlockPos>): Boolean = positions.all { world.get(it).isAir }

    /** Whether our block is at [pos] now. Positions we only hold as air do not count. */
    fun owns(pos: BlockPos): Boolean {
        val ours = placed[pos]?.ours ?: return false
        return !ours.isAir && world.get(pos) == ours
    }

    fun set(pos: BlockPos, state: BlockState) = write(pos, state, false)

    /** For a position the caller has checked holds no block entity: its data would not come back. */
    fun replace(pos: BlockPos, state: BlockState) = write(pos, state, true)

    private fun write(pos: BlockPos, state: BlockState, mayCover: Boolean) {
        val before = placed[pos]
        val now = world.get(pos)
        val mayWrite = if (before == null) mayCover || now.isAir else now == before.ours
        if (!mayWrite) return
        if (now != state) world.set(pos, state)
        placed[pos.immutable()] = Placed(state, before?.original ?: now)
    }

    fun restore() {
        for ((pos, block) in placed) {
            if (block.ours != block.original && world.get(pos) == block.ours) world.set(pos, block.original)
        }
        placed.clear()
    }

    /** Drops the record without touching the world, for a world that is already gone. */
    fun forget() = placed.clear()
}
