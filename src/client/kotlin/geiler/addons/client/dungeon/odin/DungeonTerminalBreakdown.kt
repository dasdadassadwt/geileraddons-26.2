package geiler.addons.client.dungeon.odin

import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern
import kotlin.jvm.JvmRecord

/** Kotlin implementation for bounded terminal-completion history and replacement templates. */
class DungeonTerminalBreakdown {
    private val counts = LinkedHashMap<String, Int>()
    private val playerCounts = LinkedHashMap<String, MutablePlayerCounts>()
    private val recent = ArrayList<DungeonTerminalBreakdown.Completion>()

    fun observeTitle(
        plainTitle: String?,
        localPlayer: String?,
        selfOnly: Boolean,
    ): DungeonTerminalBreakdown.Completion? {
        if (plainTitle == null) return null
        val normalizedTitle = COLOR_SEQUENCE.matcher(plainTitle).replaceAll("").trim { it <= ' ' }
        val matcher = COMPLETION.matcher(normalizedTitle)
        if (!matcher.matches()) return null

        val player = matcher.group(1).trim { it <= ' ' }
        if (selfOnly && (localPlayer == null || !player.equals(localPlayer, ignoreCase = true))) return null
        val current = parseBounded(matcher.group(4))
        val total = parseBounded(matcher.group(5))
        if (current < 1 || total < current || total > 32) return null

        val completion = DungeonTerminalBreakdown.Completion(
            player,
            matcher.group(2).lowercase(Locale.ROOT),
            matcher.group(3).lowercase(Locale.ROOT),
            current,
            total,
        )
        counts[completion.type!!] = (counts[completion.type] ?: 0) + 1
        playerCounts.computeIfAbsent(normalizePlayer(player)) { MutablePlayerCounts(player) }
            .increment(completion.type)
        recent.add(completion)
        if (recent.size > MAX_RECENT) recent.removeAt(0)
        return completion
    }

    fun replacement(template: String?, completion: DungeonTerminalBreakdown.Completion?): String? {
        if (completion == null || template == null) return null
        val result = template.replace("{name}", completion.player!!)
            .replace("{action}", completion.action!!)
            .replace("{type}", completion.type!!)
            .replace("{current}", completion.current.toString())
            .replace("{total}", completion.total.toString())
        return COLOR.matcher(result).replaceAll("\u00A7\$1")
    }

    fun recent(): List<DungeonTerminalBreakdown.Completion> = java.util.List.copyOf(recent)

    fun counts(): Map<String, Int> = java.util.Map.copyOf(counts)

    fun perPlayer(): List<DungeonTerminalBreakdown.PlayerSummary> = java.util.List.copyOf(
        playerCounts.values.map(MutablePlayerCounts::snapshot),
    )

    fun reset() {
        counts.clear()
        playerCounts.clear()
        recent.clear()
    }

    private fun normalizePlayer(player: String): String = player.lowercase(Locale.ROOT)

    private fun parseBounded(text: String): Int = text.toIntOrNull() ?: -1

    private class MutablePlayerCounts(private val player: String) {
        private var terminals = 0
        private var levers = 0
        private var devices = 0

        fun increment(type: String) {
            when (type) {
                "terminal" -> terminals++
                "lever" -> levers++
                "device" -> devices++
            }
        }

        fun snapshot() = DungeonTerminalBreakdown.PlayerSummary(player, terminals, levers, devices)
    }

    private companion object {
        const val MAX_RECENT = 12
        val COMPLETION = Pattern.compile(
            "^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d{1,2})/(\\d{1,2})\\)$",
            Pattern.CASE_INSENSITIVE,
        )
        val COLOR_SEQUENCE = Pattern.compile("[\\u00A7&][0-9A-FK-ORa-fk-or]")
        val COLOR = Pattern.compile("&([0-9A-FK-ORa-fk-or])")
    }

    @JvmRecord
    data class Completion(
        val player: String?,
        val action: String?,
        val type: String?,
        val current: Int,
        val total: Int,
    ) {
        override fun toString(): String =
            "Completion[player=$player, action=$action, type=$type, current=$current, total=$total]"
    }
    @JvmRecord
    data class PlayerSummary(
        val player: String?,
        val terminals: Int,
        val levers: Int,
        val devices: Int,
    ) {
        fun total(): Int = terminals + levers + devices

        override fun toString(): String =
            "PlayerSummary[player=$player, terminals=$terminals, levers=$levers, devices=$devices]"
    }
}
