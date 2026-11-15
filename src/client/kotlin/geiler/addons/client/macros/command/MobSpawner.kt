package geiler.addons.client.macros.command

import geiler.addons.client.macros.MacroRuntime
import geiler.addons.client.macros.area.PaintStore
import geiler.addons.client.macros.killer.AreaKillerMacro
import geiler.addons.client.macros.killer.MobIds
import geiler.addons.client.macros.sim.SimGuard
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `/spawnmob <count> <radius> <mob>`: summons mobs on the ground around the player, in singleplayer only, so a test world
 * does not need `/summon`. The spots are picked here from the loaded world (a free standing place with ground under it);
 * the summoning itself runs as `summon` commands on the integrated server's thread, as the server's owner.
 */
object MobSpawner {
    const val MAX_COUNT = 200
    const val MAX_RADIUS = 64

    /** How far above and below the player's feet a standing place is looked for. */
    private const val SCAN_UP = 8
    private const val SCAN_DOWN = 16

    /** Tries per mob before the command gives up on finding that many places. */
    private const val TRIES_PER_MOB = 20

    @JvmStatic
    fun spawn(count: Int, radius: Int, mob: String, say: (String) -> Unit, fail: (String) -> Unit): Int {
        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        val server = client.singleplayerServer
        if (player == null || level == null || server == null || !SimGuard.allowedNow()) {
            fail("spawnmob works in singleplayer only (and not while the world is open to LAN).")
            return 0
        }
        if (!MobIds.isHuntable(mob)) {
            fail("Unknown mob: $mob")
            return 0
        }
        val id = MobIds.normalize(mob)
        val random = ThreadLocalRandom.current()
        val spots = ArrayList<Vec3>(count)
        var tries = 0
        val areaSpots = areaSpots(player, radius, count, random)
        if (areaSpots != null) {
            for (spot in areaSpots) spots.add(Vec3(spot[0], spot[1], spot[2]))
        } else {
            while (spots.size < count && tries < count * TRIES_PER_MOB) {
                tries++
                val angle = random.nextDouble() * Math.PI * 2.0
                val distance = radius * sqrt(random.nextDouble())
                val x = player.x + cos(angle) * distance
                val z = player.z + sin(angle) * distance
                val y = groundY(level, x, z, player.y) ?: continue
                spots.add(Vec3(x, y, z))
            }
        }
        if (spots.isEmpty()) {
            fail(if (areaSpots != null) "No walkable cell of the area within $radius blocks." else "No free ground found within $radius blocks.")
            AreaKillerMacro.diagnostics("spawnmob refused mob=$id count=$count radius=$radius reason=no_ground tries=$tries")
            return 0
        }
        val uuid = player.uuid
        server.execute {
            val owner = server.playerList.getPlayer(uuid) ?: return@execute
            val source = server.createCommandSourceStack().withLevel(owner.level()).withSuppressedOutput()
            for (spot in spots) {
                val at = String.format(Locale.ROOT, "%.2f %.2f %.2f", spot.x, spot.y, spot.z)
                server.commands.performPrefixedCommand(source, "summon $id $at")
            }
        }
        val spawned = spots.size
        say("Spawning $spawned ${MobIds.label(id)}" + (if (spawned < count) " (only $spawned free places found)" else "") + " within $radius blocks.")
        AreaKillerMacro.diagnostics("spawnmob mob=$id requested=$count spawned=$spawned radius=$radius tries=$tries")
        return 1
    }

    /**
     * Where to put the mobs when the painted area of the macro is in use and the player is in it or within [SpawnPlacement.NEAR_BLOCKS] of its
     * walkable cells: on those cells within [radius] only, however far the radius reaches past the area. Null when no area is in use or
     * the player is not near it (the plain disc applies), empty when it is but no cell is within the radius.
     */
    private fun areaSpots(player: net.minecraft.client.player.LocalPlayer, radius: Int, count: Int, random: java.util.Random): List<DoubleArray>? {
        val name = MacroRuntime.config.paintArea
        if (name.isEmpty()) return null
        val area = PaintStore.get(name) ?: return null
        if (area.walkableCount() == 0) return null
        var near = false
        area.forEachNear(player.x, player.z, SpawnPlacement.NEAR_BLOCKS, Int.MAX_VALUE) { _, y, _, type, _ ->
            if (type.walkable() && abs(y - player.y) <= NEAR_HEIGHT) near = true
        }
        if (!near) return null
        val cells = ArrayList<IntArray>()
        area.forEachNear(player.x, player.z, radius.toDouble(), Int.MAX_VALUE) { x, y, z, type, eighths ->
            if (type.walkable() && abs(y - player.y) <= AREA_HEIGHT) cells.add(intArrayOf(x, y, z, eighths))
        }
        val spots = SpawnPlacement.pick(cells, count, random)
        AreaKillerMacro.diagnostics("spawnmob area=$name cells=${cells.size} requested=$count radius=$radius")
        return spots
    }

    /** How far above or below the player a walkable cell may be to count as near, and to be spawned on. */
    private const val NEAR_HEIGHT = 24.0
    private const val AREA_HEIGHT = 48.0

    /** The feet height of a free standing place near ([x], [z]) around [aroundY], or null: ground under it, two free cells over it. */
    private fun groundY(level: ClientLevel, x: Double, z: Double, aroundY: Double): Double? {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        val top = floor(aroundY).toInt() + SCAN_UP
        val bottom = floor(aroundY).toInt() - SCAN_DOWN
        val pos = BlockPos.MutableBlockPos()
        for (y in top downTo bottom) {
            if (solid(level, pos.set(bx, y - 1, bz)) && !solid(level, pos.set(bx, y, bz)) && !solid(level, pos.set(bx, y + 1, bz))) {
                return y.toDouble()
            }
        }
        return null
    }

    private fun solid(level: ClientLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return !state.getCollisionShape(level, pos).isEmpty || !state.fluidState.isEmpty
    }
}
