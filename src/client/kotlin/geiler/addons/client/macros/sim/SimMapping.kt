package geiler.addons.client.macros.sim

/**
 * The numbers and rules of the Sim features, free of any game state so they can be checked offline.
 *
 * Speed Sim follows its specification: with Speed stat S the ground speed is the vanilla speed of the gait times S / 100,
 * so 100 is vanilla and S - 100 is the percentage faster. Jump Boost and 1 Tap use the real status effects; Minecraft's
 * amplifier is zero-based (amplifier 0 is "level 1"), so the displayed level is always amplifier + 1.
 */
object SimMapping {
    /** Strength level 255 is amplifier 254; amplifier 255 would be level 256. */
    const val ONE_TAP_AMPLIFIER = 254

    const val MIN_JUMP_LEVEL = 1
    const val MAX_JUMP_LEVEL = 255
    const val DEFAULT_JUMP_LEVEL = 3

    /**
     * Speed stat limits: 100 is vanilla. The top is far above any useful value. Minecraft clamps the movement-speed
     * attribute at 1024, which walking never reaches (1000 at the top); sprinting meets it above about 787 000.
     */
    const val MIN_SPEED_STAT = 1.0
    const val MAX_SPEED_STAT = 1_000_000.0
    const val DEFAULT_SPEED_STAT = 400.0

    /** Vanilla movement on ordinary flat ground, in blocks per second. */
    enum class Gait(val baseBlocksPerSecond: Double) {
        WALK(4.317),
        SPRINT(5.612),
        SNEAK(1.295),
    }

    /** The zero-based amplifier of the Jump Boost effect for a selected level; never off by one. */
    @JvmStatic
    fun jumpAmplifier(level: Int): Int = level.coerceIn(MIN_JUMP_LEVEL, MAX_JUMP_LEVEL) - 1

    /** What Speed stat [stat] adds to the movement speed's total multiplier: the multiplier itself is stat / 100. */
    @JvmStatic
    fun speedModifierValue(stat: Double): Double = stat / 100.0 - 1.0

    @JvmStatic
    fun speedIncreasePercent(stat: Double): Double = stat - 100.0

    @JvmStatic
    fun groundSpeed(gait: Gait, stat: Double): Double = gait.baseBlocksPerSecond * stat / 100.0
}

/** What one maintenance pass does about one Sim effect on the local player. */
enum class SimAction {
    NONE,
    APPLY,
    REMOVE;

    companion object {
        /**
         * @param allowed the world is a plain singleplayer world
         * @param enabled the feature is switched on
         * @param present the player currently has the effect or modifier
         * @param matches what is present is what the feature wants now
         * @param appliedByUs the feature put it there; only such effects are ever removed
         */
        @JvmStatic
        fun of(allowed: Boolean, enabled: Boolean, present: Boolean, matches: Boolean, appliedByUs: Boolean): SimAction = when {
            allowed && enabled -> if (present && matches) NONE else APPLY
            appliedByUs -> REMOVE
            else -> NONE
        }
    }
}
