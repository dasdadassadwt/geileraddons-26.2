package geiler.addons.client.utility

/** Java String whitespace operations retained where migrated code needs source-compatible behavior. */
object JavaStringSemantics {
    @JvmStatic
    fun trim(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && value[start].code <= 0x20) start++
        while (end > start && value[end - 1].code <= 0x20) end--
        return value.substring(start, end)
    }

    @JvmStatic
    fun strip(value: String): String {
        val start = stripLeadingEnd(value, 0)
        val end = stripTrailingStart(value, start)
        return value.substring(start, end)
    }

    @JvmStatic
    fun stripLeading(value: String): String = value.substring(stripLeadingEnd(value, 0))

    @JvmStatic
    fun isBlank(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (!Character.isWhitespace(codePoint)) return false
            index += Character.charCount(codePoint)
        }
        return true
    }

    private fun stripLeadingEnd(value: String, from: Int): Int {
        var index = from
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (!Character.isWhitespace(codePoint)) break
            index += Character.charCount(codePoint)
        }
        return index
    }

    private fun stripTrailingStart(value: String, from: Int): Int {
        var end = value.length
        while (end > from) {
            val codePoint = value.codePointBefore(end)
            if (!Character.isWhitespace(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        return end
    }
}
