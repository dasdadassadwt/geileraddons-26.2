package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import java.util.Locale
import java.util.regex.Pattern

/** Custom title text for dungeon terminal completions. */
class DungeonTerminalTitlesModule private constructor() : Module(
    "Terminal Titles",
    "Replaces terminal completion titles with a configurable short format.",
    Category.F7,
    false,
    replaceTitles,
    titleTemplate,
) {
    private var diagnosticTick = 0L
    private var sampleCounter = 0L
    private var lastStatusSample = Long.MIN_VALUE
    private var lastStatusSignature: String? = null
    private var suppressedStatusChanges = 0
    private var titleEventTick = Long.MIN_VALUE
    private var titleEventsThisTick = 0
    private var suppressedTitleEvents = 0

    init {
        group(*spec.groups.toTypedArray())
        ClientTickEvents.END_CLIENT_TICK.register { client -> sampleDiagnostics(client) }
    }

    override fun configName(): String = "Terminal Titles"

    override fun onEnable() {
        diagnosticTick = 0L
        sampleCounter = 0L
        lastStatusSample = Long.MIN_VALUE
        lastStatusSignature = null
        suppressedStatusChanges = 0
        titleEventTick = Long.MIN_VALUE
        titleEventsThisTick = 0
        suppressedTitleEvents = 0
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), true)
        writeDiagnostic(0L, "FEATURE ENABLED " + diagnosticSettings())
    }

    override fun onDisable() {
        writeDiagnostic(
            diagnosticTick,
            "FEATURE DISABLED replaceTitles=" + replaceTitles.value()
                + " suppressedStatusChanges=" + suppressedStatusChanges
                + " suppressedTitleEvents=" + suppressedTitleEvents,
        )
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), false)
    }

    @Synchronized
    private fun sampleDiagnostics(client: Minecraft) {
        if (!isEnabled()) return
        if (!client.isSameThread) {
            client.execute { sampleDiagnostics(client) }
            return
        }

        if (sampleCounter < Long.MAX_VALUE) sampleCounter++
        val level = client.level
        val worldTick = level?.gameTime
        diagnosticTick = if (worldTick != null && worldTick >= 0L) worldTick else sampleCounter

        val inDungeon = DungeonContextTracker.inDungeon()
        val floor = DungeonContextTracker.currentFloor()
        val floorSource = DungeonContextTracker.floorSource().name.lowercase(Locale.ROOT)
        val status = "enabled=true" +
            ", active=" + (inDungeon && replaceTitles.value()) +
            ", inDungeon=" + inDungeon +
            ", floor=" + (floor?.displayName() ?: "unknown") +
            ", floorSource=" + floorSource +
            ", levelPresent=" + (level != null) +
            ", playerPresent=" + (client.player != null) +
            ", replaceTitles=" + replaceTitles.value() +
            ", " + diagnosticSettings()
        val changed = status != lastStatusSignature
        if (changed) lastStatusSignature = status
        val elapsed = if (lastStatusSample == Long.MIN_VALUE) Long.MAX_VALUE
            else (sampleCounter - lastStatusSample).coerceAtLeast(0L)

        when {
            changed && elapsed >= STATUS_CHANGE_INTERVAL_TICKS ->
                writeStatus("changed", status)
            !changed && suppressedStatusChanges > 0 && elapsed >= STATUS_CHANGE_INTERVAL_TICKS ->
                writeStatus("coalesced", status)
            elapsed >= STATUS_HEARTBEAT_TICKS ->
                writeStatus("heartbeat", status)
            changed -> suppressedStatusChanges = increment(suppressedStatusChanges)
        }
    }

    @Synchronized
    private fun recordTitleDecisionInternal(clientTick: Long, originalTitle: String?, replacementTitle: String?) {
        if (!isEnabled()) return
        val tick = clientTick.coerceAtLeast(0L)
        if (titleEventTick != tick) {
            titleEventTick = tick
            titleEventsThisTick = 0
        }
        if (titleEventsThisTick >= MAX_TITLE_EVENTS_PER_TICK) {
            suppressedTitleEvents = increment(suppressedTitleEvents)
            return
        }
        titleEventsThisTick++

        val completion = parseCompletionTitle(originalTitle)
        val original = diagnosticOriginal(originalTitle, completion)
        val replacement = diagnosticReplacement(replacementTitle, completion)
        val suppressed = suppressedTitleEvents
        suppressedTitleEvents = 0
        writeDiagnostic(
            tick,
            "TITLE decision=" + (if (replacementTitle == null) "preserve-original" else "replace") +
                " original=" + original +
                " replacement=" + replacement +
                " suppressedBefore=" + suppressed,
        )
    }

    private fun writeStatus(reason: String, status: String) {
        val suppressed = suppressedStatusChanges
        suppressedStatusChanges = 0
        lastStatusSample = sampleCounter
        writeDiagnostic(
            diagnosticTick,
            "STATUS reason=" + reason + " " + status + " suppressedChanges=" + suppressed,
        )
    }

    private fun writeDiagnostic(tick: Long, line: String) {
        GeilerAddonsLog.write(Category.F7, name(), tick.coerceAtLeast(0L), boundedLine(line))
    }

    private fun diagnosticSettings(): String {
        val template = titleTemplate.value()
        return "templateLength=" + template.length +
            ", placeholders={name:" + template.contains("{name}") +
            ",action:" + template.contains("{action}") +
            ",type:" + template.contains("{type}") +
            ",current:" + template.contains("{current}") +
            ",total:" + template.contains("{total}") + "}"
    }

    private fun parseCompletionTitle(title: String?): CompletionTitle? {
        if (title == null) return null
        val plain = LEGACY_FORMATTING.matcher(title).replaceAll("").trim()
        val matcher = COMPLETION_TITLE.matcher(plain)
        if (!matcher.matches()) return null
        return CompletionTitle(
            matcher.group(1),
            matcher.group(2).lowercase(Locale.ROOT),
            matcher.group(3).lowercase(Locale.ROOT),
            matcher.group(4),
            matcher.group(5),
        )
    }

    private fun diagnosticOriginal(title: String?, completion: CompletionTitle?): String {
        if (title == null) return "missing"
        if (completion == null) return "redacted-unmatched(length=" + title.length.coerceAtMost(MAX_REPORTED_INPUT_LENGTH) + ")"
        val plain = LEGACY_FORMATTING.matcher(title).replaceAll("").trim()
        val safe = plain.replace(completion.player, "<player>", ignoreCase = true)
        return quotedDiagnosticText(safe, null)
    }

    private fun diagnosticReplacement(title: String?, completion: CompletionTitle?): String {
        if (title == null) return "none"
        if (completion == null) {
            return "redacted-unmatched(length=" + title.length.coerceAtMost(MAX_REPORTED_INPUT_LENGTH) + ")"
        }
        return quotedDiagnosticText(title, completion.player)
    }

    private fun quotedDiagnosticText(value: String, playerName: String?): String {
        var safe = OUTPUT_COLOR.matcher(value).replaceAll("&\$1")
        if (!playerName.isNullOrEmpty()) {
            safe = safe.replace(playerName, "<player>", ignoreCase = true)
        }
        safe = CONTROL_CHARACTERS.matcher(safe).replaceAll(" ")
        safe = WHITESPACE.matcher(safe).replaceAll(" ").trim()
        safe = safe.replace("\\", "\\\\").replace("\"", "\\\"")
        if (safe.length > MAX_TITLE_TEXT_LENGTH) {
            safe = safe.take(MAX_TITLE_TEXT_LENGTH - 3) + "..."
        }
        return "\"" + safe + "\""
    }

    private fun boundedLine(line: String): String =
        if (line.length <= MAX_DIAGNOSTIC_LINE_LENGTH) line
        else line.take(MAX_DIAGNOSTIC_LINE_LENGTH - 3) + "..."

    private fun increment(value: Int): Int = if (value < Int.MAX_VALUE) value + 1 else value

    private data class CompletionTitle(
        val player: String,
        val action: String,
        val type: String,
        val current: String,
        val total: String,
    )

    companion object {
        private const val STATUS_CHANGE_INTERVAL_TICKS = 20L
        private const val STATUS_HEARTBEAT_TICKS = 100L
        private const val MAX_TITLE_EVENTS_PER_TICK = 8
        private const val MAX_TITLE_TEXT_LENGTH = 96
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 480
        private const val MAX_REPORTED_INPUT_LENGTH = 10_000

        private val LEGACY_FORMATTING = Pattern.compile("(?i)[§&][0-9A-FK-OR]")
        private val OUTPUT_COLOR = Pattern.compile("(?i)[§&]([0-9A-FK-OR])")
        private val COMPLETION_TITLE = Pattern.compile(
            "^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d{1,2})/(\\d{1,2})\\)$",
            Pattern.CASE_INSENSITIVE,
        )
        private val CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]")
        private val WHITESPACE = Pattern.compile("\\s+")

        /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
        private val spec = ModuleSettingsSpec.of("Terminal Titles")

        @JvmField
        val replaceTitles = spec.toggle("replaceTitles")

        @JvmField
        val titleTemplate = spec.text("titleTemplate")

        @JvmField
        val INSTANCE = DungeonTerminalTitlesModule()

        /**
         * Records the packet hook's outcome while the module is enabled.
         *
         * The original terminal finisher name is replaced with <player>; unmatched title text is
         * omitted, and replacement text is bounded before it reaches the diagnostic file.
         */
        @JvmStatic
        fun recordTitleDecision(clientTick: Long, originalTitle: String?, replacementTitle: String?) {
            INSTANCE.recordTitleDecisionInternal(clientTick, originalTitle, replacementTitle)
        }
    }
}
