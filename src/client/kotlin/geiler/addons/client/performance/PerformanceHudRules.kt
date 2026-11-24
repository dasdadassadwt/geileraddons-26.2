package geiler.addons.client.performance

import java.util.Locale

/** Text for the Performance HUD; a null reading is unavailable and shown as `--`. */
object PerformanceHudRules {
    @JvmStatic
    fun lines(
        showPing: Boolean,
        showTps: Boolean,
        showFps: Boolean,
        showMemory: Boolean,
        ping: Int?,
        tps: Double?,
        fps: Int?,
        memoryUsedMb: Long,
        memoryMaxMb: Long,
    ): List<String> = buildList {
        if (showPing) add("Ping: " + (ping?.let { "${maxOf(0, it)}ms" } ?: "--"))
        if (showTps) add("TPS: " + (tps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "--"))
        if (showFps) add("FPS: " + (fps?.toString() ?: "--"))
        if (showMemory) {
            val percent = if (memoryMaxMb > 0) memoryUsedMb * 100 / memoryMaxMb else 0
            add("Memory: $memoryUsedMb/$memoryMaxMb MB ($percent%)")
        }
    }
}
