package geiler.addons.client.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The view bob the world pass applies while walking (vanilla `GameRenderer.bobView`), so labels projected onto the HUD
 * can follow the boxes they annotate instead of staying still while those bob. The two inputs are captured from the game
 * each frame by a mixin; a capture older than [FRESH_NANOS] means bobbing is off or paused and nothing is applied.
 */
object ViewBob {
    private const val FRESH_NANOS = 100_000_000L
    private var walk = 0f
    private var bob = 0f
    private var capturedAt = 0L

    /** Called by the mixin at the start of the game's own bobbing, with the walk distance and bob amount it is about to use. */
    @JvmStatic
    fun capture(walkDistance: Float, bobAmount: Float) {
        walk = walkDistance
        bob = bobAmount
        capturedAt = System.nanoTime()
    }

    /** Moves a point from the camera's view space (x right, y up, z backward) by the current bob; the point itself when stale. */
    @JvmStatic
    fun apply(point: DoubleArray) {
        if (System.nanoTime() - capturedAt > FRESH_NANOS) return
        apply(point, walk, bob)
    }

    /** The same transform with explicit inputs: translate, then roll about Z, then pitch about X, as vanilla orders its pose stack. */
    @JvmStatic
    fun apply(point: DoubleArray, walkDistance: Float, bobAmount: Float) {
        if (bobAmount == 0f) return
        val phase = walkDistance * Math.PI
        val pitch = Math.toRadians(abs(cos(phase - 0.2) * bobAmount) * 5.0)
        val roll = Math.toRadians(sin(phase) * bobAmount * 3.0)
        var x = point[0]
        var y = point[1]
        var z = point[2]
        val pitchedY = y * cos(pitch) - z * sin(pitch)
        val pitchedZ = y * sin(pitch) + z * cos(pitch)
        y = pitchedY
        z = pitchedZ
        val rolledX = x * cos(roll) - y * sin(roll)
        val rolledY = x * sin(roll) + y * cos(roll)
        x = rolledX
        y = rolledY
        point[0] = x + sin(phase) * bobAmount * 0.5
        point[1] = y - abs(cos(phase) * bobAmount)
        point[2] = z
    }
}
