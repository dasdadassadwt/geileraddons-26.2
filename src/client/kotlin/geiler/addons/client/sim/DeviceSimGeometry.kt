package geiler.addons.client.sim

import geiler.addons.client.location.Island
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/**
 * Where the simulator's blocks go, relative to the saved spot and facing. Offsets are the Floor 7 ones seen from where
 * you stand, so on flat ground the bottom row of the i4 wall is in the ground.
 */
object DeviceSimGeometry {
    /** The one move between Floor 7 coordinates, which the solvers use, and the sim's spot and facing. */
    class Frame(
        private val realPos: BlockPos, private val realFacing: Direction,
        private val simPos: BlockPos, private val simFacing: Direction,
    ) {
        fun toSim(point: Vec3): Vec3 = move(point, realPos, realFacing, simPos, simFacing)
        fun toReal(point: Vec3): Vec3 = move(point, simPos, simFacing, realPos, realFacing)
        fun toSim(pos: BlockPos): BlockPos = BlockPos.containing(toSim(Vec3.atCenterOf(pos)))
        fun toReal(pos: BlockPos): BlockPos = BlockPos.containing(toReal(Vec3.atCenterOf(pos)))

        /** Turns [point] about the middle of the stand block, keeping its place ahead, to the right and above. */
        private fun move(point: Vec3, from: BlockPos, fromFacing: Direction, to: BlockPos, toFacing: Direction): Vec3 {
            val x = point.x - from.x - 0.5
            val z = point.z - from.z - 0.5
            val fromSide = fromFacing.clockWise
            val ahead = x * fromFacing.stepX + z * fromFacing.stepZ
            val right = x * fromSide.stepX + z * fromSide.stepZ
            val toSide = toFacing.clockWise
            return Vec3(
                to.x + 0.5 + ahead * toFacing.stepX + right * toSide.stepX,
                point.y - from.y + to.y,
                to.z + 0.5 + ahead * toFacing.stepZ + right * toSide.stepZ,
            )
        }
    }

    /** The i4 plate on Floor 7, where you face south. */
    @JvmStatic
    fun i4Frame(spot: BlockPos, facing: Direction): Frame = Frame(BlockPos(63, 127, 35), Direction.SOUTH, spot, facing)

    /** Where you stand at Simon Says on Floor 7, facing east. */
    @JvmStatic
    fun ssFrame(spot: BlockPos, facing: Direction): Frame = Frame(BlockPos(108, 120, 93), Direction.EAST, spot, facing)

    private fun offset(spot: BlockPos, facing: Direction, right: Int, up: Int, forward: Int): BlockPos {
        val side = facing.clockWise
        return spot.offset(
            facing.stepX * forward + side.stepX * right, up, facing.stepZ * forward + side.stepZ * right,
        )
    }

    /** i4 cell 0 to 8: columns outward to your left, rows upward. */
    @JvmStatic
    fun targetPos(spot: BlockPos, facing: Direction, cell: Int): BlockPos =
        offset(spot, facing, -(1 + 2 * (cell % 3)), 2 * (cell / 3) - 1, TARGET_DISTANCE)

    /** The plate is the saved spot itself. */
    @JvmStatic
    fun targetReserved(spot: BlockPos, facing: Direction): List<BlockPos> =
        List(TargetDevice.CELLS) { targetPos(spot, facing, it) } + spot

    @JvmStatic
    fun ssButton(spot: BlockPos, facing: Direction, cell: Int): BlockPos =
        offset(spot, facing, cell % 4 - 1, 3 - cell / 4, 2)

    @JvmStatic
    fun ssLamp(spot: BlockPos, facing: Direction, cell: Int): BlockPos =
        offset(spot, facing, cell % 4 - 1, 3 - cell / 4, 3)

    @JvmStatic
    fun ssStart(spot: BlockPos, facing: Direction): BlockPos = offset(spot, facing, -2, 1, 2)

    @JvmStatic
    fun ssStartBacking(spot: BlockPos, facing: Direction): BlockPos = offset(spot, facing, -2, 1, 3)

    @JvmStatic
    fun ssReserved(spot: BlockPos, facing: Direction): List<BlockPos> =
        List(SimonDevice.CELLS) { ssButton(spot, facing, it) } + List(SimonDevice.CELLS) { ssLamp(spot, facing, it) } +
            ssStart(spot, facing) + ssStartBacking(spot, facing)

    /**
     * Singleplayer always; on a server only your own Private Island, with the switch on and a known location. Hypixel
     * reports no island for guest islands, so they are refused.
     */
    @JvmStatic
    fun allowed(singleplayer: Boolean, island: Island, hasLocation: Boolean, useOnHypixel: Boolean): Boolean =
        singleplayer || useOnHypixel && hasLocation && island == Island.PRIVATE_ISLAND

    private const val TARGET_DISTANCE = 15
}
