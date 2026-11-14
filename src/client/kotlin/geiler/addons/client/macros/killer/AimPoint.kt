package geiler.addons.client.macros.killer

import geiler.addons.client.macros.config.AreaKillerConfig
import net.minecraft.util.Util
import net.minecraft.world.entity.Entity
import java.util.concurrent.ThreadLocalRandom

/**
 * Where on a mob the crosshair goes.
 *
 * Always the same point is the one thing a hand never does: it settles somewhere in the upper body and wanders about in
 * it. So the aim is a zone, not a point. The zones that are ticked in the configuration are shares of the hitbox height;
 * every little while one of them is drawn at random and a spot inside it, and the view eases over to that spot. How much
 * of the zone the spots cover is the spread: at zero the middle of it every time, at full the whole of it - with the
 * middle favoured, as a hand would.
 *
 * The spot is kept for a few hundred milliseconds. Re-aiming on every click would make the view jitter in time with them,
 * and a spot that never changes would be the red dot in the picture.
 */
class AimPoint(private val config: AreaKillerConfig) {
    /** Where the crosshair is to go, as shares of the hitbox: height from the feet, and sideways from the middle. */
    class Spot(val height: Double, val sideX: Double, val sideZ: Double)

    /** A band of the hitbox, as shares of its height. Kept off the very edges so a moving mob is still hit. */
    private enum class Zone(val low: Double, val high: Double) {
        HEAD(0.84, 0.96),
        SHOULDERS(0.66, 0.82),
        UPPER(0.52, 0.94),
        CENTER(0.38, 0.66),
        LOWER(0.10, 0.48),
    }

    private var forTarget: Entity? = null
    private var spot = Spot(0.5, 0.0, 0.0)
    private var redrawAtMillis = 0L

    /**
     * The spot to aim at on this target now; the same one for a while, then another.
     *
     * @param airborne the target is flying through the air, knocked up or away. Whatever the ticked zones, the spot is
     *     then drawn from most of the body, and a little more often: a thing in the air is not aimed at any one place,
     *     and the view makes only small movements toward it.
     */
    fun spotFor(target: Entity, airborne: Boolean): Spot {
        val now = Util.getMillis()
        if (target !== forTarget || now >= redrawAtMillis) {
            forTarget = target
            val random = ThreadLocalRandom.current()
            spot = if (airborne) {
                Spot(
                    AIRBORNE_LOW + random.nextDouble() * (AIRBORNE_HIGH - AIRBORNE_LOW),
                    centred(random) * SIDE_RANGE * 0.5,
                    centred(random) * SIDE_RANGE * 0.5,
                )
            } else {
                draw()
            }
            val hold = if (airborne) AIRBORNE_HOLD_MILLIS else MIN_HOLD_MILLIS + (random.nextDouble() * HOLD_SPAN_MILLIS).toLong()
            redrawAtMillis = now + hold
        }
        return spot
    }

    /** Forgets the spot, so the next target gets a fresh draw at once. */
    fun reset() {
        forTarget = null
    }

    private fun draw(): Spot {
        val ticked = ArrayList<Zone>(Zone.values().size)
        if (config.aimHead) ticked.add(Zone.HEAD)
        if (config.aimShoulders) ticked.add(Zone.SHOULDERS)
        if (config.aimUpper) ticked.add(Zone.UPPER)
        if (config.aimCenter) ticked.add(Zone.CENTER)
        if (config.aimLower) ticked.add(Zone.LOWER)
        if (ticked.isEmpty()) ticked.add(Zone.CENTER)

        val random = ThreadLocalRandom.current()
        val zone = ticked[random.nextInt(ticked.size)]
        val spread = Math.max(0.0, Math.min(1.0, config.draw(config.aimSpreadMin, config.aimSpread) / 100.0))
        val middle = (zone.low + zone.high) * 0.5
        val half = (zone.high - zone.low) * 0.5
        return Spot(
            middle + half * spread * centred(random),
            SIDE_RANGE * spread * centred(random),
            SIDE_RANGE * spread * centred(random),
        )
    }

    companion object {
        /** How far from the middle a spot may be sideways at full spread, as a share of half the width. */
        private const val SIDE_RANGE = 0.7

        /** How long a spot is kept before another is drawn. */
        private const val MIN_HOLD_MILLIS = 250L
        private const val HOLD_SPAN_MILLIS = 650L

        /** The part of the hitbox a target in the air is aimed at, as shares of its height, and how long a spot is kept. */
        private const val AIRBORNE_LOW = 0.3
        private const val AIRBORNE_HIGH = 0.9
        private const val AIRBORNE_HOLD_MILLIS = 300L

        /** A number from -1 to 1 that is most often near 0: the mean of two even draws. */
        private fun centred(random: ThreadLocalRandom): Double = random.nextDouble() + random.nextDouble() - 1.0
    }
}
