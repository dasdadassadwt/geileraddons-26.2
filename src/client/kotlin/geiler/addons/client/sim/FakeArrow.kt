package geiler.addons.client.sim

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shortbow arrows as Hypixel moves them: one straight move a tick, then x0.99 and 0.05 down. The shot numbers (start
 * point, side arrow angle, start and tick, aim noise) are engineerClient's measurements; no code copied, and they were
 * not measured here. It only tests the targets.
 */
object FakeArrow {
    /** An arrow at [from] whose first move is [velocity], made [delay] ticks after the shot's tick. */
    class Launch(val from: Vec3, val velocity: Vec3, val delay: Int)

    /** [target] is the index of the box hit, or -1; [ticks] counts from the shot's tick; [path] is one point per move. */
    class Hit(val target: Int, val ticks: Int, val path: List<Vec3>)

    private const val SPEED = 3.0
    private const val DRAG = 0.99
    private const val GRAVITY = 0.05
    private const val MAX_TICKS = 30

    /** The main arrow starts this far under the eye and to your right. */
    private const val EYE_DROP = 0.1
    private const val RIGHT_OFFSET = 0.01745
    private const val SIDE_DEGREES = 5.5
    private const val SIDE_DROP = 0.5

    /** Sd of the gaussian added to each axis of the unit look, for the main arrow only. */
    const val AIM_NOISE = 0.0075

    /**
     * One shot. [eye] is the player's eye, [noise] the aim noise to add. The main arrow moves on the shot's tick. A
     * Terminator adds two side arrows a tick later: half a block under where the main arrow got to, along your clean
     * look turned to the sides, as fast as the main arrow now is, with that tick's gravity added back. (Hypixel spawns
     * each side arrow twice in the same spot; the copy cannot change a result, so it is left out.)
     */
    @JvmStatic
    fun volley(eye: Vec3, yaw: Float, pitch: Float, noise: Vec3, terminator: Boolean): List<Launch> {
        val yawRadians = Math.toRadians(yaw.toDouble())
        val pitchRadians = Math.toRadians(pitch.toDouble())
        val look = Vec3(-sin(yawRadians) * cos(pitchRadians), -sin(pitchRadians), cos(yawRadians) * cos(pitchRadians))
        val start = eye.add(-cos(yawRadians) * RIGHT_OFFSET, -EYE_DROP, -sin(yawRadians) * RIGHT_OFFSET)
        val main = Launch(start, look.add(noise).scale(SPEED), 0)
        if (!terminator) return listOf(main)
        val first = start.add(main.velocity)
        val speed = slowed(main.velocity).length()
        val sides = listOf(-SIDE_DEGREES, SIDE_DEGREES).map { degrees ->
            val turn = Math.toRadians(degrees)
            val x = look.x * cos(turn) - look.z * sin(turn)
            val z = look.x * sin(turn) + look.z * cos(turn)
            Launch(first.add(0.0, -SIDE_DROP, 0.0), Vec3(x * speed, look.y * speed + GRAVITY, z * speed), 1)
        }
        return listOf(main) + sides
    }

    private fun slowed(velocity: Vec3) = Vec3(velocity.x * DRAG, velocity.y * DRAG - GRAVITY, velocity.z * DRAG)

    @JvmStatic
    fun fly(arrow: Launch, targets: List<AABB>): Hit {
        var position = arrow.from
        var velocity = arrow.velocity
        val path = ArrayList<Vec3>()
        for (move in 0 until MAX_TICKS) {
            val next = position.add(velocity)
            var best = -1
            var bestDistance = Double.MAX_VALUE
            targets.forEachIndexed { index, box ->
                val point = box.clip(position, next)
                if (point.isPresent) {
                    val distance = position.distanceToSqr(point.get())
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = index
                    }
                }
            }
            if (best >= 0) return Hit(best, arrow.delay + move, path)
            position = next
            path.add(position)
            velocity = slowed(velocity)
        }
        return Hit(-1, arrow.delay + MAX_TICKS, path)
    }
}
