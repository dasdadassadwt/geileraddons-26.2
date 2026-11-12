package geiler.addons.client.presentation

/** Pure math for continuous color transitions across every glyph in a player name. */
object PlayerNameGradientMath {
    @JvmStatic
    fun glyphStart(index: Int, glyphCount: Int): Double {
        if (glyphCount <= 0) return 0.0
        return clampIndex(index, glyphCount) / glyphCount.toDouble()
    }

    @JvmStatic
    fun glyphEnd(index: Int, glyphCount: Int): Double {
        if (glyphCount <= 0) return 1.0
        return (clampIndex(index, glyphCount) + 1.0) / glyphCount
    }

    @JvmStatic
    fun sampleRgb(colors: List<Int>?, position: Double, cyclic: Boolean): Int {
        if (colors.isNullOrEmpty()) return 0xFFFFFF
        if (colors.size == 1) return colors.first() and 0xFFFFFF
        if (cyclic) {
            val wrapped = position - Math.floor(position)
            val scaled = wrapped * colors.size
            val start = Math.floor(scaled).toInt()
            return mix(colors[start % colors.size], colors[(start + 1) % colors.size], scaled - start)
        }
        val scaled = Math.max(0.0, Math.min(1.0, position)) * (colors.size - 1)
        val start = Math.min(Math.floor(scaled).toInt(), colors.size - 2)
        return mix(colors[start], colors[start + 1], scaled - start)
    }

    @JvmStatic
    fun animatedPhase(initialPhase: Double, createdNanos: Long, nowNanos: Long, cyclesPerSecond: Float): Double {
        if (!java.lang.Float.isFinite(cyclesPerSecond) || cyclesPerSecond <= 0.0f) return initialPhase
        val elapsedSeconds = Math.max(0L, nowNanos - createdNanos) / 1_000_000_000.0
        val phase = initialPhase + elapsedSeconds * cyclesPerSecond
        return phase - Math.floor(phase)
    }

    @JvmStatic
    fun colorAtGlyph(
        colors: List<Int>?,
        index: Int,
        glyphCount: Int,
        withinGlyph: Double,
        phase: Double,
        cyclic: Boolean,
    ): Int {
        val start = glyphStart(index, glyphCount)
        val end = glyphEnd(index, glyphCount)
        val horizontal = Math.max(0.0, Math.min(1.0, withinGlyph))
        return sampleRgb(colors, start + (end - start) * horizontal + phase, cyclic)
    }

    private fun clampIndex(index: Int, glyphCount: Int): Int = Math.max(0, Math.min(index, glyphCount - 1))

    private fun mix(first: Int, second: Int, amount: Double): Int {
        val red = interpolate(first shr 16 and 0xFF, second shr 16 and 0xFF, amount)
        val green = interpolate(first shr 8 and 0xFF, second shr 8 and 0xFF, amount)
        val blue = interpolate(first and 0xFF, second and 0xFF, amount)
        return red shl 16 or (green shl 8) or blue
    }

    private fun interpolate(first: Int, second: Int, amount: Double): Int =
        Math.round(first + (second - first) * amount).toInt()
}
