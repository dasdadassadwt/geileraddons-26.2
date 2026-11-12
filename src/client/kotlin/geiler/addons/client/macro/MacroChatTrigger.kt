package geiler.addons.client.macro

import geiler.addons.client.tree.ChatText
import java.util.Locale

/** Matching and throttling rules for chat-triggered event stacks. */
object MacroChatTrigger {
    /** A stored pattern is bounded so a hand-edited config cannot carry a paragraph into the UI. */
    const val MAX_PATTERN_LENGTH = 128
    /** How long a macro-sent chat line stays recognisable as our own echo. */
    const val ECHO_WINDOW_NANOS = 5_000_000_000L
    /** Newest kept lines; only the tail of a burst can plausibly still be in flight. */
    const val MAX_SENT_LINES = 16

    /** Whether a received line satisfies a pattern. */
    @JvmStatic
    fun matches(pattern: String?, contains: Boolean, line: String?): Boolean {
        val needle = normalise(pattern)
        if (needle.isEmpty()) return false
        val haystack = normalise(line)
        if (haystack.isEmpty()) return false
        return if (contains) haystack.contains(needle) else haystack == needle
    }

    /** Case-folded, format-free text; the single normalization every chat comparison in here shares. */
    @JvmStatic
    fun normalise(line: String?): String = line?.let { ChatText.stripForMatch(it).lowercase(Locale.ROOT) } ?: ""

    /** Whether a stack's minimum repeat delay has elapsed since its previous start. */
    @JvmStatic
    fun cooldownElapsed(lastStartNanos: Long, nowNanos: Long, cooldownMillis: Int): Boolean {
        if (lastStartNanos <= 0L) return true
        return nowNanos - lastStartNanos >= maxOf(0, cooldownMillis) * 1_000_000L
    }

    /** Whether a received line is a macro's own chat coming back from the server. */
    @JvmStatic
    fun isOwnEcho(line: String?, sent: List<SentLine?>?, nowNanos: Long): Boolean {
        val haystack = normalise(line)
        if (haystack.isEmpty() || sent.isNullOrEmpty()) return false
        for (entry in sent) {
            if (entry == null || nowNanos - entry.nanos > ECHO_WINDOW_NANOS) continue
            val needle = normalise(entry.text)
            if (needle.isNotEmpty() && endsWithWord(haystack, needle)) return true
        }
        return false
    }

    /** The echo is the sent text at the end of the line, behind an optional "Channel > Name:" prefix. */
    private fun endsWithWord(haystack: String, needle: String): Boolean =
        haystack.endsWith(needle) && (haystack.length == needle.length || haystack[haystack.length - needle.length - 1].isWhitespace())

    /** Drops entries that are too old to matter and keeps only the newest [MAX_SENT_LINES]. */
    @JvmStatic
    fun pruneSent(sent: List<SentLine?>?, nowNanos: Long): List<SentLine> {
        if (sent.isNullOrEmpty()) return emptyList()
        val kept = ArrayList<SentLine>(minOf(sent.size, MAX_SENT_LINES))
        for (entry in sent) {
            if (entry == null || nowNanos - entry.nanos > ECHO_WINDOW_NANOS) continue
            kept.add(entry)
        }
        if (kept.size > MAX_SENT_LINES) {
            return java.util.List.copyOf(kept.subList(kept.size - MAX_SENT_LINES, kept.size))
        }
        return java.util.List.copyOf(kept)
    }

    /** One chat line this client sent from a macro, kept until its echo window closes. */
    @JvmRecord
    data class SentLine(val text: String?, val nanos: Long)

    /** A chat-triggered stack that matched a line but did not start. */
    @JvmRecord
    data class Blocked(val macroId: Int, val scriptId: String?, val reason: String?, val nanos: Long)
}
