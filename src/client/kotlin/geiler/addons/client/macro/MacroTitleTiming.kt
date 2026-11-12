package geiler.addons.client.macro

/** Pure fade envelope for a centered macro title. Times are elapsed and configured milliseconds. */
object MacroTitleTiming {
    @JvmStatic
    fun opacity(elapsedMillis: Long, fadeInMillis: Int, holdMillis: Int, fadeOutMillis: Int): Float {
        val elapsed = maxOf(0L, elapsedMillis)
        val fadeIn = maxOf(0, fadeInMillis)
        val hold = maxOf(0, holdMillis)
        val fadeOut = maxOf(0, fadeOutMillis)
        val `in` = if (fadeIn == 0) 1.0f else minOf(1.0f, elapsed / fadeIn.toFloat())
        val fadeOutStart = fadeIn.toLong() + hold
        val out = if (fadeOut == 0 || elapsed < fadeOutStart) 1.0f
        else maxOf(0.0f, 1.0f - (elapsed - fadeOutStart) / fadeOut.toFloat())
        return minOf(`in`, out)
    }
}
