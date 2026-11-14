package geiler.addons.client.macros.killer

import java.util.Locale
import kotlin.math.abs

/**
 * What the walk looked like over the last five seconds, counted so a log can say whether it was fluid: how much of the walking was
 * done with no movement key down, with a sideways key, held back to turn, and how far the keys' heading was from the view. Plain
 * counting, free of game state so checks can run it.
 */
class WalkStats {
    /** One stretch of ticks. [medianOff] and [over45] are over the ticks that had an angle, in degrees. */
    class Window(
        @JvmField val walkTicks: Int,
        @JvmField val noKeyTicks: Int,
        @JvmField val strafeTicks: Int,
        @JvmField val holdTicks: Int,
        @JvmField val medianOff: Double,
        @JvmField val over45: Int,
        @JvmField val angleTicks: Int,
        @JvmField val lookSwitches: Int,
        @JvmField val replans: Int,
    ) {
        fun noKeyShare(): Double = if (walkTicks == 0) 0.0 else noKeyTicks.toDouble() / walkTicks
        fun strafeShare(): Double = if (walkTicks == 0) 0.0 else strafeTicks.toDouble() / walkTicks
        fun over45Share(): Double = if (angleTicks == 0) 0.0 else over45.toDouble() / angleTicks

        fun describe(): String = String.format(
            Locale.ROOT,
            "walkTicks=%d noKeys=%.0f%% strafe=%.0f%% hold=%d medianOff=%.0f over45=%.0f%% lookSwitches=%d replans=%d",
            walkTicks, noKeyShare() * 100.0, strafeShare() * 100.0, holdTicks, medianOff, over45Share() * 100.0, lookSwitches, replans,
        )
    }

    private var ticks = 0
    private var walk = 0
    private var noKey = 0
    private var strafe = 0
    private var hold = 0
    private var switches = 0
    private var replanned = 0
    private val angles = ArrayList<Double>()

    private var totalWalk = 0
    private var totalNoKey = 0
    private var totalStrafe = 0
    private var totalHold = 0
    private var totalOver45 = 0
    private var totalAngles = 0
    private var totalSwitches = 0
    private var totalReplans = 0
    private val totalAngleBuckets = IntArray(BUCKETS)

    /** One tick. [offDegrees] is the heading of the keys minus the view, NaN when no keys were asked for. */
    fun sample(walking: Boolean, moveKey: Boolean, sideKey: Boolean, holding: Boolean, offDegrees: Double) {
        ticks++
        if (!walking) return
        walk++
        if (!moveKey) noKey++
        if (sideKey) strafe++
        if (holding) hold++
        if (!offDegrees.isNaN() && angles.size < MAX_ANGLES) angles.add(abs(offDegrees))
    }

    fun lookSwitch() {
        switches++
    }

    fun replan() {
        replanned++
    }

    /** The window just finished, once [WINDOW_TICKS] ticks have been sampled; the next one starts empty. Null before. */
    fun poll(): Window? {
        if (ticks < WINDOW_TICKS) return null
        val window = current()
        totalWalk += walk
        totalNoKey += noKey
        totalStrafe += strafe
        totalHold += hold
        totalOver45 += window.over45
        totalAngles += window.angleTicks
        totalSwitches += switches
        totalReplans += replanned
        for (angle in angles) totalAngleBuckets[bucketOf(angle)]++
        ticks = 0
        walk = 0
        noKey = 0
        strafe = 0
        hold = 0
        switches = 0
        replanned = 0
        angles.clear()
        return window
    }

    /** Everything counted since the start or the last [reset], the window in progress included. */
    fun total(): Window {
        val open = current()
        val buckets = totalAngleBuckets.copyOf()
        for (angle in angles) buckets[bucketOf(angle)]++
        val count = totalAngles + open.angleTicks
        var median = 0.0
        if (count > 0) {
            var seen = 0
            for (b in 0 until BUCKETS) {
                seen += buckets[b]
                if (seen > count / 2) {
                    median = b * BUCKET_DEGREES + BUCKET_DEGREES / 2.0
                    break
                }
            }
        }
        return Window(
            totalWalk + walk, totalNoKey + noKey, totalStrafe + strafe, totalHold + hold, median, totalOver45 + open.over45, count,
            totalSwitches + switches, totalReplans + replanned,
        )
    }

    fun reset() {
        ticks = 0
        walk = 0
        noKey = 0
        strafe = 0
        hold = 0
        switches = 0
        replanned = 0
        angles.clear()
        totalWalk = 0
        totalNoKey = 0
        totalStrafe = 0
        totalHold = 0
        totalOver45 = 0
        totalAngles = 0
        totalSwitches = 0
        totalReplans = 0
        totalAngleBuckets.fill(0)
    }

    private fun current(): Window {
        val sorted = angles.sorted()
        val median = if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
        return Window(walk, noKey, strafe, hold, median, angles.count { it > OVER }, angles.size, switches, replanned)
    }

    private fun bucketOf(angle: Double): Int = (angle / BUCKET_DEGREES).toInt().coerceIn(0, BUCKETS - 1)

    companion object {
        /** A window is this many ticks: five seconds. */
        const val WINDOW_TICKS = 100

        private const val MAX_ANGLES = 200
        private const val OVER = 45.0
        private const val BUCKET_DEGREES = 5.0
        private const val BUCKETS = 37
    }
}

/**
 * Standing still while it should be moving: true once after a second of it, then at most every five seconds while it goes on, so
 * the log gets a snapshot of the state and not a line a tick.
 */
class StandWatch {
    private var count = 0
    private var lastReport = -REPORT_EVERY

    fun tick(standing: Boolean): Boolean {
        if (!standing) {
            count = 0
            lastReport = -REPORT_EVERY
            return false
        }
        count++
        if (count < AFTER_TICKS || count - lastReport < REPORT_EVERY) return false
        lastReport = count
        return true
    }

    companion object {
        const val AFTER_TICKS = 20
        const val REPORT_EVERY = 100
    }
}
