package geiler.addons.client.macros.killer

import geiler.addons.client.macros.input.WalkInput
import geiler.addons.client.macros.navigation.EdgeGuard
import geiler.addons.client.macros.pathing.Terrain
import geiler.addons.client.macros.rotation.SmoothRotation
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Runs straight at a mob that is close - a second, much simpler way of getting somewhere, next to the planner and not
 * part of it.
 *
 * A mob that was just hit flies off, and so does one that was standing a few blocks away when the fight started. The
 * planner answers a question nobody asked then: it takes a tick or more to find a route, the mob has moved by the time it
 * is done, and a route is built for a place, not for something that keeps going. What a player does is turn to it and
 * run. That is all this does: it keeps the keys down toward the mob while the view stays on it, stops at the wanted
 * distance, and says so the moment the way ahead is not a plain walk - then the planner takes over.
 *
 * It refuses more than it accepts. Before every step the next stretch of ground is checked for something to stand on that
 * is about level, free of anything that hurts, and inside the area; a ledge, a gap, a wall, a pool that may not be waded,
 * or the gate zone is a [Result.BLOCKED], never a try.
 */
class DirectChase {
    enum class Result {
        /** Running at the target. */
        MOVING,

        /** As close as asked. */
        CLOSE,

        /** Not something to run straight at: the caller has to plan a route, or give up. */
        BLOCKED,
    }

    private var close = false

    /** Forgets having been close, so the next call starts by closing in. */
    fun reset() {
        close = false
    }

    /**
     * Whether the next stretch in water is somewhere to wade or float: a stand in every column along it, none of them a
     * step higher than a walk. A wall of water-height or higher is where the water stops - and where a player who is told
     * to keep going has pushed against it, keys down, for as long as the chase lasted.
     */
    private fun waterAheadClear(player: LocalPlayer, terrain: Terrain, unitX: Double, unitZ: Double, ahead: Double): Boolean {
        val here = terrain.standUnder(player) ?: return false
        var feet = here.floorY
        var along = WATER_CHECK_STEP
        while (along <= ahead + 1.0E-6) {
            val next = terrain.standNear(player.x + unitX * along, feet, player.z + unitZ * along)
            if (next == null || next.floorY - feet > Terrain.STEP_HEIGHT) return false
            feet = next.floorY
            along += WATER_CHECK_STEP
        }
        return true
    }

    /**
     * One tick of the chase. Presses the keys itself; the view is the caller's.
     *
     * @param stopDistance eye-to-hitbox distance at which to stand still
     * @param maxRange the furthest a target may be and still be run at, eye to hitbox
     * @param sprint whether sprinting is allowed at all
     * @param sprintBeyond sprint only while the target is further away than this, on top of the chase's own limit
     */
    fun step(
        player: LocalPlayer, terrain: Terrain, target: Entity, stopDistance: Double, maxRange: Double, sprint: Boolean,
        sprintBeyond: Double, input: WalkInput,
    ): Result {
        val gap = sqrt(target.boundingBox.distanceToSqr(player.eyePosition))
        // Letting go of the keys does not stop the player: they slide on for about as far as a tick and a quarter of their
        // speed. Stopping at the wanted distance would put them that much too close.
        val motion = player.deltaMovement
        val coast = sqrt(motion.x * motion.x + motion.z * motion.z) * COAST_TICKS
        val near = gap <= stopDistance + coast || close && gap <= stopDistance + RESUME_MARGIN
        close = near
        if (near) {
            input.stand(player.isInWater)
            return Result.CLOSE
        }
        if (gap > maxRange || !player.hasLineOfSight(target)) return Result.BLOCKED
        if (target.onGround() && abs(target.y - player.y) > LEVEL_TOLERANCE) return Result.BLOCKED

        val dx = target.x - player.x
        val dz = target.z - player.z
        val flat = sqrt(dx * dx + dz * dz)
        if (flat < 0.05) {
            input.stand(player.isInWater)
            return Result.CLOSE
        }

        val yaw = SmoothRotation.lookAt(player.position(), Vec3(target.x, player.y, target.z)).yaw
        val off = abs(Mth.wrapDegrees(yaw - player.yRot)).toDouble()
        val run = sprint && gap > max(SPRINT_DISTANCE, sprintBeyond) && off < SPRINT_ANGLE_DEGREES && !player.isInWater

        // In the air there is nothing to steer and nothing to check: the keys stay as they were. On the ground the next
        // stretch has to be a plain walk, and the ground has to go as far as the player will: a fixed look ahead puts a player at
        // four times the speed over every edge, with the slide it has after the keys are let go of.
        if (player.onGround() || player.isInWater) {
            val kept = sqrt(motion.x * motion.x + motion.z * motion.z)
            val push = player.getAttributeValue(Attributes.MOVEMENT_SPEED) * INPUT * (if (run && !player.isSprinting) SPRINT_FACTOR else 1.0)
            val reach = EdgeGuard.lookahead(kept, push)
            val ahead = min(reach, flat)
            if (player.isInWater) {
                if (!waterAheadClear(player, terrain, dx / flat, dz / flat, ahead)) return Result.BLOCKED
            } else {
                val x = player.x + dx / flat * ahead
                val z = player.z + dz / flat * ahead
                val floor = terrain.surfaceUnder(x, z, player.y, Terrain.STEP_HEIGHT)
                if (!floor.isFinite()) return Result.BLOCKED
                if (!terrain.canWalkLine(player.position(), Vec3(x, floor, z))) return Result.BLOCKED
                // The target is nearer than the slide is long: the ground is only needed as far as the mob, where the run stops.
                val needed = min(ahead, EdgeGuard.travel(kept, push))
                if (EdgeGuard.brake(terrain.groundAhead(player.position(), dx / flat, dz / flat, needed), needed, false)) return Result.BLOCKED
            }
        }

        input.walk(yaw, run, player.isInWater)
        return Result.MOVING
    }

    companion object {
        /** The sprint's share of the speed, and what the keys give of it. */
        private const val SPRINT_FACTOR = 1.3
        private const val INPUT = 0.98

        /** How many ticks of its current speed a player slides after the keys are let go of, on the ground. */
        private const val COAST_TICKS = 3.5

        /** Having stopped at the wanted distance, the mob has to get this much further before the chase goes on. */
        private const val RESUME_MARGIN = 0.35

        /** A target on the ground this far above or below is for the planner: stairs, ledges, a pit. */
        private const val LEVEL_TOLERANCE = 1.4

        /** Sprint only when the target is this far off and the view is nearly on it. */
        private const val SPRINT_DISTANCE = 4.0
        private const val SPRINT_ANGLE_DEGREES = 25.0

        /** How far apart the columns are that are looked at in water. */
        private const val WATER_CHECK_STEP = 0.5
    }
}
