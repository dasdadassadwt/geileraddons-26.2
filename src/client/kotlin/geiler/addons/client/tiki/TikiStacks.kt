package geiler.addons.client.tiki

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/** Finding and reading the three-skull columns a tiki is made of. */
object TikiStacks {
    const val STACK_HEIGHT = 3

    @JvmStatic
    fun isSkull(level: ClientLevel, pos: BlockPos): Boolean =
        level.isLoaded(pos) && level.getBlockState(pos).block is AbstractSkullBlock

    /** The lowest skull of a column, i.e. a skull with no skull under it. */
    @JvmStatic
    fun isColumnBase(level: ClientLevel, base: BlockPos): Boolean = isSkull(level, base) && !isSkull(level, base.below())

    /** Returns how many skulls stand on top of each other starting at base. */
    @JvmStatic
    fun columnHeight(level: ClientLevel, base: BlockPos): Int {
        var height = 0
        while (isSkull(level, base.above(height))) height++
        return height
    }

    /** Exactly three skulls tall, so a single nearby player-placed head does not match. */
    @JvmStatic
    fun isStackBase(level: ClientLevel, base: BlockPos): Boolean =
        isColumnBase(level, base) && columnHeight(level, base) == STACK_HEIGHT

    /** Returns the floor skull rotation, or -1 when this is not a readable floor skull. */
    @JvmStatic
    fun readRotation(level: ClientLevel, pos: BlockPos): Int {
        if (!level.isLoaded(pos)) return -1
        val state: BlockState = level.getBlockState(pos)
        if (state.block !is AbstractSkullBlock || !state.hasProperty(SkullBlock.ROTATION)) return -1
        return state.getValue(SkullBlock.ROTATION)
    }

    /** Returns how many of the three slots starting at base hold a readable floor skull. */
    @JvmStatic
    fun readableSlots(level: ClientLevel, base: BlockPos): Int {
        var count = 0
        for (index in 0 until STACK_HEIGHT) if (readRotation(level, base.above(index)) >= 0) count++
        return count
    }

    /** Returns bottom-to-top rotations, or null if any skull is a wall head or missing. */
    @JvmStatic
    fun readRotations(level: ClientLevel, base: BlockPos): IntArray? {
        val rotations = IntArray(STACK_HEIGHT)
        for (index in 0 until STACK_HEIGHT) {
            val pos = base.above(index)
            if (!level.isLoaded(pos)) return null
            val state = level.getBlockState(pos)
            if (state.block !is AbstractSkullBlock || !state.hasProperty(SkullBlock.ROTATION)) return null
            rotations[index] = state.getValue(SkullBlock.ROTATION)
        }
        return rotations
    }

    /**
     * Returns the nearest skull column base in the height range. The scan range is intentionally
     * wider than a valid tiki so malformed columns can be reported rather than silently hidden.
     */
    @JvmStatic
    fun findNearestColumnBase(
        level: ClientLevel,
        from: Vec3,
        center: BlockPos,
        range: Int,
        minHeight: Int,
        maxHeight: Int,
    ): BlockPos? {
        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE
        for (dx in -range..range) {
            for (dy in -range..range) {
                for (dz in -range..range) {
                    val pos = center.offset(dx, dy, dz)
                    if (!isColumnBase(level, pos)) continue
                    val height = columnHeight(level, pos)
                    if (height < minHeight || height > maxHeight) continue
                    val distance = from.distanceToSqr(Vec3.atCenterOf(pos))
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = pos.immutable()
                    }
                }
            }
        }
        return best
    }
}
