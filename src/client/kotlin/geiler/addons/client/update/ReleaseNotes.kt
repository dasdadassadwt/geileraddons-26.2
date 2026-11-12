package geiler.addons.client.update

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Turns a GitHub release body into text that is safe to draw in the Click GUI.
 *
 * The body is remote, untrusted text: strip Markdown and HTML, bound its length and line count,
 * and return plain lines. It is never a link or a click target; the update UI offers only its own
 * buttons.
 */
object ReleaseNotes {
    /** Bounds for the stored text; the whole response is already capped at 256 KiB. */
    const val MAX_NOTES_CHARS = 4_000
    const val MAX_NOTES_LINES = 400
    const val MAX_LINE_CHARS = 200

    private const val REPOSITORY_RELEASES = "${ProjectLinks.GITHUB_URL}/releases/"
    private const val MAX_URL_LENGTH = 256
    private const val EMPTY_NOTES = "This release has no notes."
    private const val TRUNCATED = "… release notes truncated"

    private val LINK = Pattern.compile("!?\\[([^\\]]*)\\]\\([^)]*\\)")
    private val HTML = Pattern.compile("<[^>]*>")
    private val HEADING = Pattern.compile("^\\s*#{1,6}\\s*")
    private val BULLET = Pattern.compile("^\\s*[-*+]\\s+")
    private val QUOTE = Pattern.compile("^\\s*>+\\s*")
    private val MARKERS = Pattern.compile("[`*_~|]+")

    /** Plain, bounded lines for the Info panel. Never null and never blank. */
    @JvmStatic
    fun sanitize(markdown: String?): String {
        if (markdown == null || isJavaBlank(markdown)) return EMPTY_NOTES
        val lines = ArrayList<String>()
        var characters = 0
        var truncated = false
        for (raw in markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            var line = plainLine(raw)
            if (line.isEmpty()) {
                // One blank line between paragraphs is enough; the panel adds its own spacing.
                if (lines.isNotEmpty() && lines.last().isNotEmpty()) lines.add("")
                continue
            }
            if (line.length > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS - 1) + "…"
            if (lines.size >= MAX_NOTES_LINES || characters + line.length > MAX_NOTES_CHARS) {
                truncated = true
                break
            }
            lines.add(line)
            characters += line.length + 1
        }
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        if (lines.isEmpty()) return EMPTY_NOTES
        if (truncated) lines.add(TRUNCATED)
        return lines.joinToString("\n")
    }

    /** The API URL, or null when it is not this project's own release page. */
    @JvmStatic
    fun validateReleaseUrl(htmlUrl: String?): String? {
        if (htmlUrl == null) return null
        val trimmed = stripJavaWhitespace(htmlUrl)
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
        if (!trimmed.regionMatches(0, REPOSITORY_RELEASES, 0, REPOSITORY_RELEASES.length, ignoreCase = true)) return null
        for (character in trimmed) {
            if (Character.isWhitespace(character) || Character.isISOControl(character)) return null
        }
        return trimmed
    }

    /** One body line without its Markdown dressings, control characters, or surrounding space. */
    private fun plainLine(raw: String?): String {
        if (raw == null) return ""
        var line = LINK.matcher(raw).replaceAll("$1")
        line = HTML.matcher(line).replaceAll("")
        line = HEADING.matcher(line).replaceFirst("")
        line = QUOTE.matcher(line).replaceFirst("")
        val bullet: Matcher = BULLET.matcher(line)
        if (bullet.find()) line = "• " + line.substring(bullet.end())
        line = MARKERS.matcher(line).replaceAll("")
        val cleaned = StringBuilder(line.length)
        for (character in line) {
            if (character == '\t') {
                cleaned.append(' ')
                continue
            }
            if (Character.isISOControl(character)) continue
            cleaned.append(character)
        }
        return stripJavaWhitespace(cleaned.toString())
    }

    private fun stripJavaWhitespace(value: String): String =
        value.trim { character -> Character.isWhitespace(character) }

    /** Java String.isBlank() uses Character.isWhitespace and excludes space-only nonbreaking chars. */
    private fun isJavaBlank(value: String): Boolean {
        for (character in value) if (!Character.isWhitespace(character)) return false
        return true
    }
}
