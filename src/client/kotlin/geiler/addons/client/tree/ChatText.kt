package geiler.addons.client.tree

import geiler.addons.client.utility.JavaStringSemantics

/** Chat text cleanup, shared by everything that has to recognise a Hypixel line. */
object ChatText {
    /** A number rather than a literal - this file is about characters that break when re-encoded. */
    private const val SECTION_SIGN = 0x00a7

    /**
     * Drops legacy colour codes and flattens every kind of space to an ordinary one.
     *
     * Both halves matter. Hypixel sends these lines with section-sign codes embedded rather than
     * as component styling, and one of them can sit in the middle of a phrase. The padding it uses
     * to centre a block is not always U+0020 either, and a non-breaking space is invisible in a
     * log yet fatal to an exact match.
     */
    @JvmStatic
    fun plain(message: String): String {
        val out = StringBuilder(message.length)
        var index = 0
        while (index < message.length) {
            val character = message[index]
            if (character.code == SECTION_SIGN) {
                index += 2
                continue
            }
            out.append(if (Character.isSpaceChar(character) || Character.isWhitespace(character)) ' ' else character)
            index++
        }
        return out.toString()
    }

    private val SECTION_OR_AMPERSAND_CODE = java.util.regex.Pattern.compile("(?i)[\u00a7&][0-9a-fk-or]")

    /** Removes section-sign and ampersand formatting codes (colours, bold, reset, ...) without touching other text. */
    @JvmStatic
    fun stripFormattingCodes(value: String): String = SECTION_OR_AMPERSAND_CODE.matcher(value).replaceAll("")

    /** Exact-match cleanup also drops invisible formatting/private-use characters and trims ends. */
    @JvmStatic
    fun stripForMatch(message: String): String {
        val out = StringBuilder(message.length)
        var index = 0
        while (index < message.length) {
            val character = message[index]
            if (character.code == SECTION_SIGN) {
                index += 2
                continue
            }
            val type = Character.getType(character)
            if (type != Character.FORMAT.toInt() && type != Character.PRIVATE_USE.toInt()) {
                out.append(if (Character.isSpaceChar(character) || Character.isWhitespace(character)) ' ' else character)
            }
            index++
        }
        return JavaStringSemantics.trim(out.toString())
    }
}
