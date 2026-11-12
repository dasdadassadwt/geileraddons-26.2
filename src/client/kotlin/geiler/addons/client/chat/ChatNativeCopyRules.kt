package geiler.addons.client.chat

/** Pure native-chat line selection rules used by right-click copying. */
object ChatNativeCopyRules {
    /** Vanilla stores trimmed lines oldest-to-newest and draws the newest visible line at the bottom. */
    @JvmStatic
    fun lineIndexFromBottom(lineCount: Int, scroll: Int, rowFromBottom: Int): Int {
        if (lineCount <= 0 || scroll < 0 || rowFromBottom < 0) return -1
        val index = lineCount.toLong() - scroll.toLong() - 1L - rowFromBottom.toLong()
        return if (index < 0L || index >= lineCount.toLong()) -1 else index.toInt()
    }
}
