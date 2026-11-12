package geiler.addons.client.dungeon

import geiler.addons.client.chat.ChatStringSemantics
import java.util.ArrayList
import java.util.function.ToIntFunction
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/** Kotlin implementation of the count-aware map labels and width-constrained text rules. */
object DungeonRoomMapLabel {
    private val SECRET_COUNT = Pattern.compile("(\\?\\s*/\\s*\\d+|\\d+\\s*/\\s*\\d+)$")
    private val PARAGRAPH_BREAK = Pattern.compile("\\R")
    private val WHITESPACE = Pattern.compile("\\s+")

    @JvmStatic
    fun build(name: String?, foundSecrets: Int, maxSecrets: Int, mode: String?): String =
        build(name, foundSecrets, maxSecrets, mode, false)

    /** Known unopened rooms start at zero even when the room catalog has no maximum. */
    @JvmStatic
    fun build(name: String?, foundSecrets: Int, maxSecrets: Int, mode: String?, unopened: Boolean): String {
        var displayName = name?.stripJavaWhitespace() ?: ""
        if (displayName.equals("Unknown", ignoreCase = true)) displayName = ""
        val selectedMode = mode ?: "Both"
        if (selectedMode == "Off") return ""
        val count = when {
            maxSecrets > 0 -> {
                val found = if (unopened) "0" else if (foundSecrets < 0) "?" else min(foundSecrets, maxSecrets).toString()
                "$found/$maxSecrets"
            }
            unopened -> "0/?"
            else -> ""
        }
        return when (selectedMode) {
            "Room Name" -> if (displayName.isEmpty()) "?" else displayName
            "Room Secrets" -> if (count.isEmpty()) "?" else count
            "Both" -> {
                if (displayName.isEmpty()) displayName = "?"
                if (count.isEmpty()) displayName else "$displayName\n$count"
            }
            else -> ""
        }
    }

    /** Wraps at word boundaries and hard-wraps an overlong word without dropping text. */
    @JvmStatic
    fun wrap(label: String?, maximumWidth: Int, measure: ToIntFunction<String>?): List<String> {
        if (label.isNullOrEmpty() || measure == null) return emptyList()
        val limit = max(1, maximumWidth)
        val lines = ArrayList<String>()
        for (paragraph in PARAGRAPH_BREAK.split(label, -1)) {
            if (ChatStringSemantics.isBlank(paragraph)) {
                lines.add("")
                continue
            }
            val line = StringBuilder()
            for (word in WHITESPACE.split(ChatStringSemantics.trim(paragraph))) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (measure.applyAsInt(candidate) <= limit) {
                    line.setLength(0)
                    line.append(candidate)
                    continue
                }
                if (line.isNotEmpty()) {
                    lines.add(line.toString())
                    line.setLength(0)
                }
                if (measure.applyAsInt(word) <= limit) {
                    line.append(word)
                } else {
                    val pieces = splitWord(word, limit, measure)
                    for (index in 0 until pieces.size - 1) lines.add(pieces[index])
                    if (pieces.isNotEmpty()) line.append(pieces.last())
                }
            }
            if (line.isNotEmpty()) lines.add(line.toString())
        }
        return java.util.List.copyOf(lines)
    }

    private fun splitWord(word: String, limit: Int, measure: ToIntFunction<String>): List<String> {
        val pieces = ArrayList<String>()
        var start = 0
        while (start < word.length) {
            var bestEnd = start
            var end = start + Character.charCount(word.codePointAt(start))
            while (end <= word.length) {
                if (measure.applyAsInt(word.substring(start, end)) <= limit) bestEnd = end else break
                if (end == word.length) break
                end += Character.charCount(word.codePointAt(end))
            }
            if (bestEnd == start) bestEnd = start + Character.charCount(word.codePointAt(start))
            pieces.add(word.substring(start, bestEnd))
            start = bestEnd
        }
        return pieces
    }

    /** Returns a label whose measured width never exceeds maximumWidth. */
    @JvmStatic
    fun fit(label: String?, maximumWidth: Int, measure: ToIntFunction<String>?): String {
        if (label == null || ChatStringSemantics.isBlank(label) || measure == null) return ""
        val limit = max(0, maximumWidth)
        if (measure.applyAsInt(label) <= limit) return label

        val counter = SECRET_COUNT.matcher(label)
        if (counter.find()) {
            val name = label.substring(0, counter.start()).stripTrailingJavaWhitespace()
            val secrets = WHITESPACE.matcher(counter.group()).replaceAll("")
            if (measure.applyAsInt(secrets) > limit) return ""
            var roomNameLimit = limit - measure.applyAsInt(secrets)
            if (name.isNotEmpty()) roomNameLimit -= measure.applyAsInt(" ")
            if (name.isEmpty() || roomNameLimit <= 0) return secrets
            val compactName = abbreviate(name, roomNameLimit, measure)
            return if (compactName.isEmpty()) secrets else "$compactName $secrets"
        }
        return abbreviate(label, limit, measure)
    }

    private fun abbreviate(name: String?, limit: Int, measure: ToIntFunction<String>): String {
        if (name == null || ChatStringSemantics.isBlank(name) || limit <= 0) return ""
        if (measure.applyAsInt(name) <= limit) return name
        val words = WHITESPACE.split(ChatStringSemantics.trim(name))
        var longest = 1
        for (word in words) longest = max(longest, word.codePointCount(0, word.length))
        for (keep in longest - 1 downTo 1) {
            val parts = ArrayList<String>(words.size)
            for (word in words) {
                val count = word.codePointCount(0, word.length)
                parts.add(if (count <= keep) word else word.substring(0, word.offsetByCodePoints(0, keep)) + "…")
            }
            val candidate = parts.joinToString(" ")
            if (measure.applyAsInt(candidate) <= limit) return candidate
        }
        if (words.size > 1) {
            val initials = StringBuilder(words.size)
            for (word in words) initials.appendCodePoint(word.codePointAt(0))
            val candidate = initials.toString()
            if (measure.applyAsInt(candidate) <= limit) return candidate
        }
        return ""
    }

    private fun String.stripJavaWhitespace(): String = trim { Character.isWhitespace(it) }

    private fun String.stripTrailingJavaWhitespace(): String = trimEnd { Character.isWhitespace(it) }
}
