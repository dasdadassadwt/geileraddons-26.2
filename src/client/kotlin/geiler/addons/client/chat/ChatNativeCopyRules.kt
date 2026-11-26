package geiler.addons.client.chat

/** Pure native-chat line selection rules used by right-click copying. */
object ChatNativeCopyRules {
    /** Vanilla stores trimmed lines newest-first (`addFirst`); the bottom drawn row is index [scroll]. */
    @JvmStatic
    fun lineIndexFromBottom(lineCount: Int, scroll: Int, rowFromBottom: Int): Int {
        if (lineCount <= 0 || scroll < 0 || rowFromBottom < 0) return -1
        val index = scroll.toLong() + rowFromBottom.toLong()
        return if (index >= lineCount.toLong()) -1 else index.toInt()
    }
}
