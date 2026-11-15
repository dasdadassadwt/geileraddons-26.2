package geiler.addons.client.dungeon.f7

/** Melody terminal progress detection, after NoFrills' Melody Message. */
object MelodyRules {
    const val EMPTY = -1
    const val OTHER = 0
    const val RED = 1
    const val LIME = 2

    class Progress(val percent: String, val count: Int)

    @JvmStatic
    fun isMelodyTitle(title: String?): Boolean = title?.trim() == "Click the button on time!"

    /**
     * [slots] lists the container slots in order as [EMPTY], [OTHER], [RED] (red terracotta) or [LIME].
     * Walking from the last slot, every red or lime pane is counted; the first lime pane reached while the
     * count is still below [lastCount] decides the progress: 1 is 67 %, 2 is 33 %.
     * Returns null while the screen is still being built, or when nothing new is reached.
     */
    @JvmStatic
    fun progress(slots: IntArray, lastCount: Int): Progress? {
        if (slots.any { it == EMPTY }) return null
        var count = 0
        for (i in slots.indices.reversed()) {
            val slot = slots[i]
            if (slot == RED || slot == LIME) count++
            if (slot == LIME && count < lastCount) {
                val percent = when (count) {
                    1 -> "67%"
                    2 -> "33%"
                    else -> ""
                }
                if (percent.isNotEmpty()) return Progress(percent, count)
            }
        }
        return null
    }

    @JvmStatic
    fun format(template: String, percent: String): String = template.replace("{percent}", percent)
}
