package geiler.addons.client.macros.sim

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.BubbleColumnBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.piston.PistonHeadBlock
import net.minecraft.world.level.block.RedstoneTorchBlock
import net.minecraft.world.level.block.RepeaterBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * The game side of the Etherwarp sim: reads the world for [EtherwarpMath], and moves the integrated server's player the
 * way Hypixel's server answers an etherwarp - an absolute position with the view kept (the Y_ROT and X_ROT relative flags
 * with zero values, as the recorded `/etw` packets show) and no movement.
 */
object EtherwarpSim {
    /** What one use did, for the module's diagnostics. */
    class Outcome(@JvmField val line: String, @JvmField val teleported: Boolean)

    /** Whether the held item is the etherwarp item of the sim: a diamond shovel, which is what the Aspect of the Void is. */
    @JvmStatic
    fun holdsItem(player: LocalPlayer): Boolean = player.mainHandItem.`is`(Items.DIAMOND_SHOVEL)

    /** Traces from the sneaking eye along the look direction; client thread. */
    @JvmStatic
    fun trace(player: LocalPlayer, level: ClientLevel, tuners: Int): EtherwarpMath.Result {
        val look = player.lookAngle
        val position = EtherwarpMath.Probe(Probes(level)::flags)
        return EtherwarpMath.trace(
            position, player.x, player.y + EtherwarpMath.SNEAK_EYE_HEIGHT, player.z,
            look.x, look.y, look.z, EtherwarpMath.range(tuners),
        )
    }

    /** Traces from a given foot position and eye height: the Etherwarp module's preview and click use it. */
    @JvmStatic
    fun traceFrom(level: ClientLevel, x: Double, y: Double, z: Double, eyeHeight: Double, look: Vec3, distance: Double): EtherwarpMath.Result =
        EtherwarpMath.trace(EtherwarpMath.Probe(Probes(level)::flags), x, y + eyeHeight, z, look.x, look.y, look.z, distance)

    /** The world as [EtherwarpMath] reads it, for the planners of hops. */
    @JvmStatic
    fun probe(level: ClientLevel): EtherwarpMath.Probe = EtherwarpMath.Probe(Probes(level)::flags)

    /** The collision box of a block, for the preview; null when the block has none. */
    @JvmStatic
    fun boundsAt(level: ClientLevel, x: Int, y: Int, z: Int): net.minecraft.world.phys.AABB? {
        val pos = BlockPos(x, y, z)
        val shape = level.getBlockState(pos).getCollisionShape(level, pos)
        return if (shape.isEmpty) null else shape.bounds().move(pos)
    }

    /**
     * Teleports the integrated server's player to the result; client thread, the move happens on the server thread.
     * With [serverSound] off the ender dragon sound is left out, because the caller plays its own.
     */
    @JvmStatic
    @JvmOverloads
    fun teleport(client: Minecraft, player: LocalPlayer, result: EtherwarpMath.Result, serverSound: Boolean = true): Outcome {
        val server = client.singleplayerServer ?: return Outcome("skipped reason=no_integrated_server", false)
        val uuid = player.uuid
        val x = result.landX
        val y = result.landY
        val z = result.landZ
        server.execute {
            val target = server.playerList.getPlayer(uuid) ?: return@execute
            target.connection.teleport(PositionMoveRotation(Vec3(x, y, z), Vec3.ZERO, 0.0f, 0.0f), EnumSet.of(Relative.Y_ROT, Relative.X_ROT))
            target.resetFallDistance()
            if (serverSound) target.level().playSound(null, x, y, z, SoundEvents.ENDER_DRAGON_HURT, SoundSource.PLAYERS, 1.0f, SOUND_PITCH)
        }
        return Outcome(
            "warp from=${fmt(player.x)},${fmt(player.y)},${fmt(player.z)} to=${fmt(x)},${fmt(y)},${fmt(z)} " +
                "block=${result.blockX},${result.blockY},${result.blockZ} distance=${fmt(result.distance)}",
            true,
        )
    }

    /**
     * The Aspect of the Void's Instant Transmission in singleplayer (see [InstantTransmission]): moves the integrated server's player
     * along the view, 8 blocks plus one per tuner, to where the body still fits. Client thread; the move happens on the server thread.
     */
    @JvmStatic
    fun instantTransmission(client: Minecraft, player: LocalPlayer, level: ClientLevel, tuners: Int): Outcome {
        val look = player.lookAngle
        val result = InstantTransmission.trace(
            EtherwarpMath.Probe(Probes(level)::flags), player.x, player.y, player.z, look.x, look.y, look.z, InstantTransmission.distance(tuners),
        )
        if (!result.moved) return Outcome("instant skipped reason='${result.reason}' from=${fmt(player.x)},${fmt(player.y)},${fmt(player.z)}", false)
        val server = client.singleplayerServer ?: return Outcome("skipped reason=no_integrated_server", false)
        val uuid = player.uuid
        val x = result.x
        val y = result.y
        val z = result.z
        server.execute {
            val target = server.playerList.getPlayer(uuid) ?: return@execute
            target.connection.teleport(PositionMoveRotation(Vec3(x, y, z), Vec3.ZERO, 0.0f, 0.0f), EnumSet.of(Relative.Y_ROT, Relative.X_ROT))
            target.resetFallDistance()
            target.level().playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0f, 1.0f)
        }
        return Outcome(
            "instant from=${fmt(player.x)},${fmt(player.y)},${fmt(player.z)} to=${fmt(x)},${fmt(y)},${fmt(z)} " +
                "distance=${fmt(result.distance)} range=${fmt(InstantTransmission.distance(tuners))}",
            true,
        )
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)

    /** Reads cells of a level into [EtherwarpMath] flags. */
    private class Probes(private val level: ClientLevel) {
        private val pos = BlockPos.MutableBlockPos()

        /**
         * What a cell is, read from the world once per game tick: the planners trace hundreds of rays through the same cells, and a
         * block state and its collision shape are not free. A cell that changes within a tick is read again in the next.
         */
        fun flags(x: Int, y: Int, z: Int): Int {
            if (Memo.level !== level || Memo.time != level.gameTime || Memo.cells.size > Memo.LIMIT) {
                Memo.level = level
                Memo.time = level.gameTime
                Memo.cells.clear()
            }
            val key = BlockPos.asLong(x, y, z)
            val known = Memo.cells.get(key)
            if (known != Memo.UNKNOWN) return known
            val flags = read(x, y, z)
            Memo.cells.put(key, flags)
            return flags
        }

        private fun read(x: Int, y: Int, z: Int): Int {
            if (y < level.minY || y >= level.maxY) return EtherwarpMath.PASSABLE
            pos.set(x, y, z)
            val state = level.getBlockState(pos)
            if (state.isAir) return EtherwarpMath.PASSABLE
            val block = state.block
            val shape = state.getCollisionShape(level, pos)
            val empty = shape.isEmpty
            val feetBlocking = block is SkullBlock || block is WallSkullBlock || block is ComparatorBlock ||
                block is RepeaterBlock || block is FlowerPotBlock || block is LadderBlock
            val passable = empty || feetBlocking || block is ButtonBlock || block is BubbleColumnBlock ||
                block is PistonHeadBlock || block is LeverBlock || block is NetherWartBlock || block is RedstoneTorchBlock ||
                block is LadderBlock
            var flags = 0
            if (passable) flags = flags or EtherwarpMath.PASSABLE
            if (!empty || feetBlocking) flags = flags or EtherwarpMath.OBSTRUCTS
            val offset = if (empty) 1 else EtherwarpMath.standOffset(shape.max(Direction.Axis.Y))
            return flags or (offset shl EtherwarpMath.STAND_SHIFT)
        }
    }

    /** The cells read this tick, see [Probes.flags]. */
    private object Memo {
        const val UNKNOWN = Int.MIN_VALUE
        const val LIMIT = 300_000
        var level: ClientLevel? = null
        var time = Long.MIN_VALUE
        val cells = it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap().also { map -> map.defaultReturnValue(UNKNOWN) }
    }

    /** The pitch of the ender dragon hurt sound Hypixel's etherwarp plays. */
    const val SOUND_PITCH = 0.53968257f
}
