package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.odin.DungeonMaskProcTimers
import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import java.util.EnumMap
import java.util.Locale

/** Centered active/cooldown countdowns and sound alerts for dungeon invincibility procs. */
class DungeonInvincibilityTimerModule private constructor() : DungeonTextHudModule(
    "Invincibility Timers",
    "Shows centered Bonzo, Spirit Mask, and Phoenix countdowns.",
    "dungeon_invincibility_timer",
    "Invincibility Timers",
    bonzo,
    spirit,
    phoenix,
    playProcSound,
    display,
    showCooldowns,
    titleDurationTicks,
    soundId,
    soundVolume,
    soundPitch,
), ModulePreview {
    @Volatile
    private var readings: List<DungeonMaskProcTimers.Reading> = emptyList()
    private val lastCenteredText = EnumMap<DungeonMaskProcTimers.Proc, String>(DungeonMaskProcTimers.Proc::class.java)

    private val diagnostics = ModuleDiagnostics(
        Category.F7, { name() }, MAX_DIAGNOSTIC_EVENTS_PER_TICK, 100L, MAX_DIAGNOSTIC_PAYLOAD_LENGTH,
    )

    init {
        group(
            SettingGroup.switched("Tracked Procs", bonzo, spirit, phoenix),
            SettingGroup("Display", display, showCooldowns, titleDurationTicks),
            SettingGroup.switched("Sound", playProcSound, soundId, soundVolume, soundPitch),
        )
    }

    override fun isSettingVisible(setting: Setting): Boolean {
        if (setting === titleDurationTicks) {
            return display.value() == "CENTERED" || display.value() == "BOTH"
        }
        if (setting === soundId || setting === soundVolume || setting === soundPitch) {
            return playProcSound.value()
        }
        return true
    }

    /** Receives the adapter's immutable timer snapshot once per client tick. */
    fun publishReadings(next: List<DungeonMaskProcTimers.Reading>?) {
        readings = if (next.isNullOrEmpty()) emptyList() else java.util.List.copyOf(next)
        if (isEnabled()) {
            diagnostics.advance()
            diagnostics.status { suppressed ->
                "readings=${readings.size},display=${display.value()},showCooldowns=${showCooldowns.value()}" +
                    ",refreshTicks=${titleDurationTicks.intValue()},suppressedEvents=$suppressed"
            }
        }
        refreshOutput()
    }

    /** Called for the exact proc chat event; this does not infer procs from health changes. */
    fun onProc(proc: DungeonMaskProcTimers.Proc?) {
        if (!isEnabled() || proc == null) return

        val configuredSoundId = soundId.value()
        val volume = soundVolume.value()
        val pitch = soundPitch.value()
        val soundEnabled = playProcSound.value()
        val procIsEnabled = soundEnabled && procEnabled(proc)
        if (!soundEnabled || !procIsEnabled) {
            val reason = if (!soundEnabled) "sound_disabled" else "proc_disabled"
            recordProcSoundOutcome(
                proc,
                classifyProcSoundOutcome(procIsEnabled, soundEnabled, soundIdValid = false, soundRegistered = false),
                reason,
                configuredSoundId,
                volume,
                pitch,
            )
            return
        }

        val id = Identifier.tryParse(configuredSoundId.trim())
        if (id == null) {
            recordProcSoundOutcome(
                proc,
                classifyProcSoundOutcome(procIsEnabled, soundEnabled, soundIdValid = false, soundRegistered = false),
                "invalid_id",
                configuredSoundId,
                volume,
                pitch,
            )
            return
        }

        val event: SoundEvent? = BuiltInRegistries.SOUND_EVENT.getValue(id)
        if (event == null) {
            recordProcSoundOutcome(
                proc,
                classifyProcSoundOutcome(procIsEnabled, soundEnabled, soundIdValid = true, soundRegistered = false),
                "not_registered",
                configuredSoundId,
                volume,
                pitch,
            )
            return
        }
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(event, pitch, volume),
        )
        recordProcSoundOutcome(
            proc,
            classifyProcSoundOutcome(procIsEnabled, soundEnabled, soundIdValid = true, soundRegistered = true),
            "none",
            configuredSoundId,
            volume,
            pitch,
        )
    }

    /** Keeps the centered display alive between timer snapshots and removes it immediately on exit. */
    fun tick() {
        if (!isEnabled()) {
            clearSnapshots()
            return
        }
        refreshOutput()
    }

    fun clearSnapshots() {
        readings = emptyList()
        lastCenteredText.clear()
        clearOutput()
    }

    override fun onEnable() {
        diagnostics.enable("display=${display.value()},showCooldowns=${showCooldowns.value()}")
        clearSnapshots()
    }

    override fun onDisable() {
        diagnostics.disable()
        clearSnapshots()
    }

    /** Keep the singular saved profile key while presenting the clearer plural module label. */
    override fun configName(): String = "Invincibility Timer"

    private fun refreshOutput() {
        if (!isEnabled()) {
            clearOutput()
            return
        }

        val hudLines = ArrayList<String>(DungeonMaskProcTimers.Proc.values().size)
        val centered = display.value() == "CENTERED" || display.value() == "BOTH"
        for (proc in DungeonMaskProcTimers.Proc.values()) {
            val reading = reading(proc)
            if (reading == null || !procEnabled(proc)) {
                lastCenteredText.remove(proc)
                TitleAlertStack.clearSource(titleSource(proc))
                continue
            }

            val countdown = timerText(reading, showCooldowns.value())
            if (countdown == null) {
                lastCenteredText.remove(proc)
                TitleAlertStack.clearSource(titleSource(proc))
                continue
            }

            val subtitle = "${procLabel(proc)} · $countdown"
            hudLines.add(subtitle)
            if (centered) {
                if (subtitle != lastCenteredText[proc]) {
                    val color = if (reading.activeTicks > 0) ACTIVE_COLOR else COOLDOWN_COLOR
                    val durationTicks = titleDurationTicks.intValue()
                    TitleAlertStack.submitSubtitle(titleSource(proc), subtitle, durationTicks, color)
                    diagnostics.event(
                        "SUBTITLE result=submitted,source=${titleSource(proc)},durationTicks=$durationTicks" +
                            ",text=${boundedDiagnosticText(subtitle)},color=${String.format(Locale.ROOT, "%08X", color)}",
                    )
                    lastCenteredText[proc] = subtitle
                }
            } else {
                lastCenteredText.remove(proc)
                TitleAlertStack.clearSource(titleSource(proc))
            }
        }

        if (display.value() == "HUD" || display.value() == "BOTH") publishHudLines(hudLines)
        else clearHudLines()
    }

    private fun clearOutput() {
        clearHudLines()
        for (proc in DungeonMaskProcTimers.Proc.values()) {
            TitleAlertStack.clearSource(titleSource(proc))
        }
    }

    private fun reading(proc: DungeonMaskProcTimers.Proc): DungeonMaskProcTimers.Reading? =
        readings.firstOrNull { it.proc == proc }

    private fun procEnabled(proc: DungeonMaskProcTimers.Proc): Boolean = when (proc) {
        DungeonMaskProcTimers.Proc.BONZO -> bonzo.value()
        DungeonMaskProcTimers.Proc.SPIRIT -> spirit.value()
        DungeonMaskProcTimers.Proc.PHOENIX -> phoenix.value()
    }

    @Suppress("UNUSED_PARAMETER")
    override fun previewLines(font: Font, width: Int): List<Component> = listOf(
        Component.literal("Display: ${display.displayValue()}"),
        Component.literal("Bonzo Mask · 3.0s active"),
        Component.literal("Spirit Mask · 30s cooldown"),
        Component.literal("Cooldowns ${if (showCooldowns.value()) "On" else "Off"}"),
        Component.literal("Sound: ${if (playProcSound.value()) "On" else "Off"}"),
    )

    override fun previewLines(): List<String> = listOf("Bonzo Mask · 3.0s active", "Spirit Mask · 30s cooldown")

    private fun recordProcSoundOutcome(
        proc: DungeonMaskProcTimers.Proc,
        result: String,
        reason: String,
        configuredSoundId: String,
        volume: Float,
        pitch: Float,
    ) {
        diagnostics.event(
            "PROC proc=${proc.name},result=$result,reason=$reason" +
                ",soundId=${boundedDiagnosticSoundId(configuredSoundId)}" +
                ",volume=$volume,pitch=$pitch",
        )
    }

    private fun boundedDiagnosticSoundId(value: String): String {
        val safe = buildString(minOf(value.length, MAX_DIAGNOSTIC_SOUND_ID_LENGTH)) {
            value.take(MAX_DIAGNOSTIC_SOUND_ID_LENGTH).forEach { character ->
                val allowed = character in 'a'..'z' || character in 'A'..'Z' ||
                    character in '0'..'9' || character in "_./:-"
                append(if (allowed) character else '_')
            }
        }
        return if (value.length <= MAX_DIAGNOSTIC_SOUND_ID_LENGTH) safe
        else safe.dropLast(3) + "..."
    }

    private fun boundedDiagnosticText(value: String): String {
        val safeValue = value.replace('\r', ' ').replace('\n', ' ')
        return if (safeValue.length <= MAX_DIAGNOSTIC_TEXT_LENGTH) safeValue
        else safeValue.take(MAX_DIAGNOSTIC_TEXT_LENGTH - 3) + "..."
    }

    companion object {
        private const val ACTIVE_COLOR = 0xFF55FF55.toInt()
        private const val COOLDOWN_COLOR = 0xFFFFAA00.toInt()
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 8
        private const val MAX_DIAGNOSTIC_PAYLOAD_LENGTH = 480
        private const val MAX_DIAGNOSTIC_TEXT_LENGTH = 96
        private const val MAX_DIAGNOSTIC_SOUND_ID_LENGTH = 96

        /** Retained as static fields because the Java dungeon adapter and config code read them. */
        @JvmField
        val bonzo = BooleanSetting("Bonzo Mask", true)

        @JvmField
        val spirit = BooleanSetting("Spirit Mask", true)

        @JvmField
        val phoenix = BooleanSetting("Phoenix", true)

        @JvmField
        val playProcSound = BooleanSetting("Play Proc Sound", true)

        private val display = ChoiceSetting(
            "Display",
            "BOTH",
            mapOf("CENTERED" to "Centered countdown title", "HUD" to "Movable HUD only", "BOTH" to "Both"),
            "CENTERED",
            "HUD",
            "BOTH",
        )
        private val showCooldowns = BooleanSetting("Show Cooldowns", true)
        private val titleDurationTicks = NumberSetting("Title Refresh (ticks)", 1f, 40f, 4f, true)
        private val soundId = TextSetting("Sound ID", "Sound ID", "block.note_block.pling", 96)
        private val soundVolume = NumberSetting("Sound Volume", 0f, 1f, 0.8f)
        private val soundPitch = NumberSetting("Sound Pitch", 0.5f, 2f, 1f)

        @JvmField
        val INSTANCE = DungeonInvincibilityTimerModule()

        /** Kept static for the existing Java regression harness. */
        @JvmStatic
        fun timerText(reading: DungeonMaskProcTimers.Reading, includeCooldowns: Boolean): String? {
            if (reading.activeTicks > 0) return formatSeconds(reading.activeTicks) + " active"
            if (includeCooldowns && reading.cooldownTicks > 0) {
                return formatSeconds(reading.cooldownTicks) + " cooldown"
            }
            return null
        }

        /** Pure outcome classification kept Java-visible for the focused interop check. */
        @JvmStatic
        fun classifyProcSoundOutcome(
            procEnabled: Boolean,
            soundEnabled: Boolean,
            soundIdValid: Boolean,
            soundRegistered: Boolean,
        ): String = when {
            !procEnabled || !soundEnabled -> "disabled"
            !soundIdValid -> "invalid_sound"
            !soundRegistered -> "unregistered_sound"
            else -> "played"
        }

        private fun titleSource(proc: DungeonMaskProcTimers.Proc): String =
            "invincibility." + proc.name.lowercase(Locale.ROOT)

        private fun procLabel(proc: DungeonMaskProcTimers.Proc): String = when (proc) {
            DungeonMaskProcTimers.Proc.BONZO -> "Bonzo Mask"
            DungeonMaskProcTimers.Proc.SPIRIT -> "Spirit Mask"
            DungeonMaskProcTimers.Proc.PHOENIX -> "Phoenix"
        }

        private fun formatSeconds(ticks: Int): String =
            String.format(Locale.ROOT, "%.1fs", maxOf(0, ticks) / 20.0)
    }
}
