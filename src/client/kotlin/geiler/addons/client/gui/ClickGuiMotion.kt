package geiler.addons.client.gui

/**
 * Motion profiles for the Click GUI. The setting stores the display name so old config files stay
 * human-readable; this enum owns the timings and easing so the screen never scatters magic
 * numbers through its input and rendering paths.
 */
enum class ClickGuiMotion(
    private val settingValue: String,
    private val transitionDurationMillis: Int,
    private val lifecycleDurationMillis: Int,
    private val closeDurationMillis: Int,
    private val cardStaggerDurationMillis: Int,
    private val hoverDurationMillis: Int,
    private val hoverMotionAmplitude: Float,
) {
    NONE("None", 0, 0, 0, 0, 0, 0.0f),
    REDUCED("Reduced", 140, 220, 120, 0, 180, 0.65f),
    EXPRESSIVE("Expressive", 280, 420, 200, 28, 280, 1.35f);

    fun transitionMillis(): Int = transitionDurationMillis

    fun lifecycleMillis(): Int = lifecycleDurationMillis

    fun closeMillis(): Int = closeDurationMillis

    fun cardStaggerMillis(): Int = cardStaggerDurationMillis

    fun hoverMillis(): Int = hoverDurationMillis

    fun hoverAmplitude(): Float = hoverMotionAmplitude

    fun animated(): Boolean = this != NONE

    /** Easing for movement: fast response, soft landing, and no overshoot into another control. */
    fun ease(progress: Float): Float {
        val clamped = clamp(progress)
        if (this == NONE) return 1.0f
        if (this == REDUCED) {
            return 1.0f - Math.pow((1.0f - clamped).toDouble(), 3.0).toFloat()
        }
        // Quintic ease-out gives Expressive its lively first movement without making navigation
        // wait at the end of the transition.
        return 1.0f - Math.pow((1.0f - clamped).toDouble(), 5.0).toFloat()
    }

    /** Close-only quadratic ease-out; unlike the opening profile it has no spring or long tail. */
    fun closeEase(progress: Float): Float {
        val clamped = clamp(progress)
        if (this == NONE) return 1.0f
        return 1.0f - (1.0f - clamped) * (1.0f - clamped)
    }

    /** Close progress uses its own short duration; opening remains on the lifecycle timing. */
    fun closeProgress(elapsedMillis: Long): Float {
        if (this == NONE) return 1.0f
        return closeEase(maxOf(0L, elapsedMillis).toFloat() / closeDurationMillis.toFloat())
    }

    /** Monotonic panel scale for closing, kept separate from the expressive opening spring. */
    fun closeScale(progress: Float): Float {
        if (this == NONE) return 1.0f
        val remaining = 1.0f - clamp(progress)
        return if (this == REDUCED) 0.90f + 0.10f * remaining else 0.72f + 0.28f * remaining
    }

    /** Frame-rate-independent settling used for scroll positions and their visible thumb. */
    fun settleScrollVisual(current: Double, target: Double, frameMillis: Long): Double {
        if (this == NONE) return target
        val timeConstantMillis = if (this == EXPRESSIVE) 95.0 else 60.0
        val factor = 1.0 - Math.exp(-frameMillis / timeConstantMillis)
        val value = current + (target - current) * factor
        return if (Math.abs(target - value) < 0.05) target else value
    }

    /** Small, bounded overshoot used only for the panel's scale and card entrance. */
    fun spring(progress: Float): Float {
        val clamped = clamp(progress)
        if (this == NONE) return 1.0f
        if (this == REDUCED) return ease(clamped)
        val c1 = 1.70158f
        val c3 = c1 + 1.0f
        val shifted = clamped - 1.0f
        return 1.0f + c3 * shifted * shifted * shifted + c1 * shifted * shifted
    }

    companion object {
        /** Resolves a config value without allowing a hand-edited file to disable the GUI accidentally. */
        @JvmStatic
        fun fromSetting(value: String?): ClickGuiMotion {
            return entries.firstOrNull { it.settingValue == value } ?: EXPRESSIVE
        }

        @JvmStatic
        fun elapsedMillis(startedNanos: Long, nowNanos: Long): Long =
            maxOf(0L, (nowNanos - startedNanos) / 1_000_000L)

        @JvmStatic
        fun smoothToggle(progress: Float): Float {
            val t = Math.max(0.0f, Math.min(1.0f, progress))
            return t * t * (3.0f - 2.0f * t)
        }

        private fun clamp(value: Float): Float = Math.max(0.0f, Math.min(1.0f, value))
    }
}
