package geiler.addons.client.tree

import java.util.regex.Pattern

/** The three gift-dropping trees, and how each one's gift line is recognised in chat. */
enum class TreeType(private val nameForDisplay: String) {
    HELIX("Helix"),
    FIG("Fig"),
    MANGROVE("Mangrove");

    private val cutLine: Pattern
    private val giftLine: Pattern

    init {
        val quotedName = Pattern.quote(nameForDisplay)
        // Spacing is deliberately loose because the separator is not reliably an ordinary space.
        cutLine = Pattern.compile(
            "cut\\s.*\\sof\\s+the\\s+$quotedName\\s*Tree",
            Pattern.CASE_INSENSITIVE,
        )
        giftLine = Pattern.compile("$quotedName\\s*Tree\\s*Gift", Pattern.CASE_INSENSITIVE)
    }

    fun displayName(): String = nameForDisplay

    companion object {
        private val VALUES = values()

        /** The tree named in a gift line, or null. */
        @JvmStatic
        fun fromMessage(message: String): TreeType? {
            val plain = ChatText.plain(message)
            for (type in VALUES) {
                if (type.cutLine.matcher(plain).find() || type.giftLine.matcher(plain).find()) return type
            }
            return null
        }
    }
}
