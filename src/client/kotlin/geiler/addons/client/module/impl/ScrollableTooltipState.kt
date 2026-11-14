package geiler.addons.client.module.impl

/**
 * Offset and scale of the hovered item's tooltip. Pure state: the module feeds it scroll input and the hovered slot,
 * the tooltip renderer reads the offset back. Offsets are in tooltip units (they scale with [scale]).
 */
class ScrollableTooltipState {
    var x = 0.0f
        private set
    var y = 0.0f
        private set
    var scale = 1.0f
        private set
    private var lastSlot: Any? = null

    fun reset() {
        x = 0.0f
        y = 0.0f
        scale = 1.0f
    }

    /** Moves the offset back to zero when the hovered slot changed and [resetOnChange] is on; scale is kept. */
    fun hover(slot: Any?, resetOnChange: Boolean) {
        if (slot !== lastSlot) {
            if (resetOnChange) {
                x = 0.0f
                y = 0.0f
            }
            lastSlot = slot
        }
    }

    /**
     * Applies one scroll notch. Ctrl scales, Shift scrolls sideways, plain scrolls vertically; each is gated by its
     * allow flag. Alt shortens the step. Returns whether the notch changed anything.
     */
    fun scroll(
        amount: Double,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
        allowHorizontal: Boolean,
        allowVertical: Boolean,
        allowScale: Boolean,
    ): Boolean {
        val direction = Math.signum(amount).toFloat()
        if (direction == 0.0f) return false
        val step = if (alt) SMALL_STEP else STEP
        when {
            ctrl -> {
                if (!allowScale) return false
                val base = if (scale <= 0.0f) 1.0f else scale
                scale = base * (1.0f + SCALE_STEP * direction)
            }
            shift -> {
                if (!allowHorizontal) return false
                x += step * direction
            }
            else -> {
                if (!allowVertical) return false
                y += step * direction
            }
        }
        return true
    }

    /** The scale to draw with: a zero or negative scale counts as 1. */
    fun effectiveScale(): Float = if (scale <= 0.0f) 1.0f else scale

    companion object {
        const val STEP = 9.0f
        const val SMALL_STEP = 4.0f
        const val SCALE_STEP = 0.1f
    }
}
