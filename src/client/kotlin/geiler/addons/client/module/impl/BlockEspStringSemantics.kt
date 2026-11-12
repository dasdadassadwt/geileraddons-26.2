package geiler.addons.client.module.impl

/** Java String blank semantics used by the frozen Block ESP config and labels. */
internal object BlockEspStringSemantics {
    fun isJavaBlank(value: String): Boolean {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (!Character.isWhitespace(codePoint)) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }
}
