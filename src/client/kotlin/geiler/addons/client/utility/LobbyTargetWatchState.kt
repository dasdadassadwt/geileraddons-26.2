package geiler.addons.client.utility

import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern
import geiler.addons.client.chat.ChatStringSemantics

/** Emits a target once per confirmed connection/player-entity presence interval. */
class LobbyTargetWatchState {
    private var present: Set<String> = java.util.Set.of()
    private var notified: Set<String> = java.util.Set.of()

    fun update(
        configuredTargets: Collection<String?>?,
        confirmedNames: Collection<String?>?,
    ): List<String> = update(configuredTargets, confirmedNames, outputAvailable = true)

    /** Keeps an unreported arrival pending until its output surface is available. */
    fun update(
        configuredTargets: Collection<String?>?,
        confirmedNames: Collection<String?>?,
        outputAvailable: Boolean,
    ): List<String> {
        val targets = normalizeTargets(configuredTargets)
        val presentNames = normalizeNames(confirmedNames)
        val nowPresent = HashSet<String>()
        val newlyPresent = ArrayList<String>()
        for ((targetKey, displayName) in targets) {
            if (!presentNames.contains(targetKey)) continue
            nowPresent.add(targetKey)
            if (outputAvailable && !notified.contains(targetKey)) newlyPresent.add(displayName)
        }
        val stillNotified = HashSet<String>(notified)
        stillNotified.retainAll(nowPresent)
        if (outputAvailable) {
            for ((targetKey, _) in targets) {
                if (nowPresent.contains(targetKey)) stillNotified.add(targetKey)
            }
        }
        present = java.util.Set.copyOf(nowPresent)
        notified = java.util.Set.copyOf(stillNotified)
        return java.util.List.copyOf(newlyPresent)
    }

    fun reset() {
        present = java.util.Set.of()
        notified = java.util.Set.of()
    }

    fun presentTargets(): Set<String> = present

    /** Number of currently present targets that still need an output surface before notification. */
    fun pendingTargetCount(): Int = present.count { it !in notified }

    companion object {
        private val VALID_NAME = ChatStringSemantics.MINECRAFT_USERNAME
        private val TARGET_SEPARATOR = Pattern.compile("[,\\r\\n]+")

        /** Comma/newline-separated user input; invalid names and blank entries are ignored. */
        @JvmStatic
        fun parseTargets(text: String?): List<String> {
            if (text == null || text.isBlank()) return java.util.List.of()
            val result = ArrayList<String>()
            for (candidate in TARGET_SEPARATOR.split(text)) {
                val name = candidate.trim { Character.isWhitespace(it) }
                if (VALID_NAME.matcher(name).matches() && result.none { it.equals(name, ignoreCase = true) }) {
                    result.add(name)
                }
            }
            return java.util.List.copyOf(result)
        }

        private fun normalizeTargets(names: Collection<String?>?): LinkedHashMap<String, String> {
            val result = LinkedHashMap<String, String>()
            if (names == null) return result
            for (name in names) {
                val display = name?.trim { Character.isWhitespace(it) } ?: continue
                if (!VALID_NAME.matcher(display).matches()) continue
                result.putIfAbsent(normalize(display), display)
            }
            return result
        }

        private fun normalizeNames(names: Collection<String?>?): Set<String> {
            val result = HashSet<String>()
            if (names == null) return result
            for (name in names) {
                val display = name?.trim { Character.isWhitespace(it) } ?: continue
                if (VALID_NAME.matcher(display).matches()) result.add(normalize(display))
            }
            return result
        }

        private fun normalize(name: String): String = name.lowercase(Locale.ROOT)
    }
}
