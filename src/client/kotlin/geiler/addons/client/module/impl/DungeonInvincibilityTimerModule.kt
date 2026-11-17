package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.odin.DungeonMaskProcTimers
import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.inventory.ItemBackgrounds
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Base64
import java.util.UUID
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
    showOnItem,
    alwaysShow,
    partyAnnounce,
), ModulePreview {
    @Volatile
    private var readings: List<DungeonMaskProcTimers.Reading> = emptyList()
    private val lastCenteredText = EnumMap<DungeonMaskProcTimers.Proc, String>(DungeonMaskProcTimers.Proc::class.java)
    private val cooldownTotals = EnumMap<DungeonMaskProcTimers.Proc, Int>(DungeonMaskProcTimers.Proc::class.java)

    /** Icon rows drawn by the HUD; rebuilt with every published snapshot. */
    @Volatile
    private var rows: List<TimerRow> = emptyList()

    private val diagnostics = ModuleDiagnostics(
        Category.F7, { name() }, MAX_DIAGNOSTIC_EVENTS_PER_TICK, 100L, MAX_DIAGNOSTIC_PAYLOAD_LENGTH,
    )

    init {
        group(
            SettingGroup.switched("Tracked Procs", bonzo, spirit, phoenix),
            SettingGroup("Display", display, showCooldowns, titleDurationTicks, alwaysShow),
            SettingGroup.switched("Sound", playProcSound, soundId, soundVolume, soundPitch),
            SettingGroup("Item Overlay", showOnItem),
            SettingGroup("Party Chat", partyAnnounce),
        )
    }

    override fun isSettingVisible(setting: Setting): Boolean {
        if (setting === titleDurationTicks) {
            return display.value() == "CENTERED" || display.value() == "BOTH"
        }
        if (setting === alwaysShow) {
            return display.value() == "HUD" || display.value() == "BOTH"
        }
        if (setting === soundId || setting === soundVolume || setting === soundPitch) {
            return playProcSound.value()
        }
        return true
    }

    /** Receives the adapter's immutable timer snapshot once per client tick. */
    fun publishReadings(next: List<DungeonMaskProcTimers.Reading>?) {
        readings = if (next.isNullOrEmpty()) emptyList() else java.util.List.copyOf(next)
        for (reading in readings) {
            // The first reading after a proc is the full cooldown, so it is the fill's 100% mark.
            if (reading.cooldownTicks <= 0) cooldownTotals.remove(reading.proc)
            else if (reading.cooldownTicks > (cooldownTotals[reading.proc] ?: 0)) cooldownTotals[reading.proc] = reading.cooldownTicks
        }
        if (isEnabled()) {
            diagnostics.advance()
            diagnostics.status { suppressed ->
                "readings=${readings.size},display=${display.value()},showCooldowns=${showCooldowns.value()}" +
                    ",alwaysShow=${alwaysShow.value()},partyAnnounce=${partyAnnounce.value()}" +
                    ",refreshTicks=${titleDurationTicks.intValue()},suppressedEvents=$suppressed"
            }
        }
        refreshOutput()
    }

    /** Called for the exact proc chat event; this does not infer procs from health changes. */
    fun onProc(proc: DungeonMaskProcTimers.Proc?) {
        if (!isEnabled() || proc == null) return

        if (partyAnnounce.value() && procEnabled(proc)) {
            val player = Minecraft.getInstance().player
            player?.connection?.sendCommand("pc ${partyMessage(proc)}")
            diagnostics.event("ANNOUNCE proc=${proc.name},result=${if (player != null) "sent" else "no_player"}")
        }

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

    /** Draws the cooldown fill over a Bonzo or Spirit mask item; x and y are the item's top-left corner. */
    fun drawItemFill(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (!isEnabled() || !showOnItem.value() || stack.isEmpty || readings.isEmpty()) return
        val proc = procForItemId(ItemBackgrounds.info(stack).skyblockId) ?: return
        val reading = readings.firstOrNull { it.proc == proc } ?: return
        val height = fillHeight(reading.cooldownTicks, cooldownTotals[proc] ?: 0, ICON_SIZE)
        if (height > 0) graphics.fill(x, y + ICON_SIZE - height, x + ICON_SIZE, y + ICON_SIZE, ITEM_FILL_COLOR)
    }

    fun clearSnapshots() {
        readings = emptyList()
        cooldownTotals.clear()
        lastCenteredText.clear()
        clearOutput()
    }

    override fun onEnable() {
        diagnostics.enable(
            "display=${display.value()},showCooldowns=${showCooldowns.value()}" +
                ",alwaysShow=${alwaysShow.value()},partyAnnounce=${partyAnnounce.value()}",
        )
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
        val iconRows = ArrayList<TimerRow>(DungeonMaskProcTimers.Proc.values().size)
        val centered = display.value() == "CENTERED" || display.value() == "BOTH"
        val hud = display.value() == "HUD" || display.value() == "BOTH"
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
            iconRows.add(timerRow(proc, reading))
            // Cooldowns are HUD-only; the centered title shows an active invincibility window and nothing else.
            if (centered && reading.activeTicks > 0) {
                if (subtitle != lastCenteredText[proc]) {
                    val durationTicks = titleDurationTicks.intValue()
                    TitleAlertStack.submitSubtitle(titleSource(proc), subtitle, durationTicks, ACTIVE_COLOR)
                    diagnostics.event(
                        "SUBTITLE result=submitted,source=${titleSource(proc)},durationTicks=$durationTicks" +
                            ",text=${boundedDiagnosticText(subtitle)},color=${String.format(Locale.ROOT, "%08X", ACTIVE_COLOR)}",
                    )
                    lastCenteredText[proc] = subtitle
                }
            } else {
                lastCenteredText.remove(proc)
                TitleAlertStack.clearSource(titleSource(proc))
            }
        }

        if (shouldShowHud(hud, hudLines.isNotEmpty(), alwaysShow.value(), DungeonContextTracker.inDungeon())) {
            // Once any proc is in use, tracked procs that are ready show a check mark beside their icon.
            for (proc in DungeonMaskProcTimers.Proc.values()) {
                if (procEnabled(proc) && iconRows.none { it.proc == proc }) iconRows.add(TimerRow(proc, "✔", READY_COLOR))
            }
            iconRows.sortBy { it.proc.ordinal }
            rows = iconRows
            if (hudLines.isNotEmpty()) publishHudLines(hudLines) else clearHudLines()
        } else {
            rows = emptyList()
            clearHudLines()
        }
    }

    private fun timerRow(proc: DungeonMaskProcTimers.Proc, reading: DungeonMaskProcTimers.Reading): TimerRow =
        if (reading.activeTicks > 0) TimerRow(proc, formatSeconds(reading.activeTicks), ACTIVE_HUD_COLOR)
        else TimerRow(proc, "${(reading.cooldownTicks + 19) / 20}s", COOLDOWN_HUD_COLOR)

    private fun sampleRows(): List<TimerRow> = listOf(
        TimerRow(DungeonMaskProcTimers.Proc.SPIRIT, "3.0s", ACTIVE_HUD_COLOR),
        TimerRow(DungeonMaskProcTimers.Proc.BONZO, "142s", COOLDOWN_HUD_COLOR),
        TimerRow(DungeonMaskProcTimers.Proc.PHOENIX, "✔", READY_COLOR),
    )

    private fun displayRows(): List<TimerRow> = rows.ifEmpty { sampleRows() }

    /** The base class keys these off text lines, which are empty while an always-shown HUD has only ready checks. */
    override fun visible(): Boolean = isEnabled() && rows.isNotEmpty()

    override fun previewMode(): String = if (rows.isEmpty()) "sample" else "live"

    override fun width(font: Font?): Int =
        ICON_SIZE + ICON_GAP + (displayRows().maxOfOrNull { font!!.width(it.text) } ?: 1)

    override fun height(font: Font?): Int = maxOf(1, displayRows().size * ROW_HEIGHT)

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
        drawRows(graphics, font, x, y, rows)

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
        drawRows(graphics, font, x, y, displayRows())

    /** One head icon per proc with its remaining time (gold active, red cooldown) or a green check when ready. */
    private fun drawRows(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, toDraw: List<TimerRow>) {
        for ((index, row) in toDraw.withIndex()) {
            val rowY = y + index * ROW_HEIGHT
            graphics.item(skull(row.proc), x, rowY)
            graphics.text(
                font, Component.literal(row.text), x + ICON_SIZE + ICON_GAP,
                rowY + (ICON_SIZE - font.lineHeight) / 2 + 1, row.colorArgb,
            )
        }
    }

    private fun clearOutput() {
        rows = emptyList()
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

    private data class TimerRow(val proc: DungeonMaskProcTimers.Proc, val text: String, val colorArgb: Int)

    companion object {
        private const val ACTIVE_COLOR = 0xFF55FF55.toInt()
        private const val ITEM_FILL_COLOR = 0x8055FF55.toInt()
        private const val ACTIVE_HUD_COLOR = 0xFFFFAA00.toInt()
        private const val COOLDOWN_HUD_COLOR = 0xFFFF5555.toInt()
        private const val READY_COLOR = 0xFF55FF55.toInt()
        private const val ICON_SIZE = 16
        private const val ICON_GAP = 3
        private const val ROW_HEIGHT = 17
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
        private val showOnItem = BooleanSetting("Show On Item", false)
        private val alwaysShow = BooleanSetting("Always Show In Dungeon", false)
        private val partyAnnounce = BooleanSetting("Party Announce", false)

        @JvmField
        val INSTANCE = DungeonInvincibilityTimerModule()

        /** Party chat text for a proc, e.g. "Bonzo proc". */
        @JvmStatic
        fun partyMessage(proc: DungeonMaskProcTimers.Proc): String = when (proc) {
            DungeonMaskProcTimers.Proc.BONZO -> "Bonzo proc"
            DungeonMaskProcTimers.Proc.SPIRIT -> "Spirit proc"
            DungeonMaskProcTimers.Proc.PHOENIX -> "Phoenix proc"
        }

        /** The HUD shows with live lines, or in a dungeon while Always Show is on; never when HUD display is off. */
        @JvmStatic
        fun shouldShowHud(hudEnabled: Boolean, hasLines: Boolean, alwaysShow: Boolean, inDungeon: Boolean): Boolean =
            hudEnabled && (hasLines || (alwaysShow && inDungeon))

        /** Height of the green cooldown fill on a 16px slot: full at the proc, shrinking to nothing when ready. */
        @JvmStatic
        fun fillHeight(remainingTicks: Int, totalTicks: Int, size: Int): Int {
            if (remainingTicks <= 0 || totalTicks <= 0) return 0
            return ((size * minOf(remainingTicks, totalTicks) + totalTicks - 1) / totalTicks).coerceIn(1, size)
        }

        /** The mask proc an item belongs to, by its SkyBlock id; only the Bonzo and Spirit masks. */
        @JvmStatic
        fun procForItemId(id: String): DungeonMaskProcTimers.Proc? = when (id) {
            "BONZO_MASK", "STARRED_BONZO_MASK" -> DungeonMaskProcTimers.Proc.BONZO
            "SPIRIT_MASK", "STARRED_SPIRIT_MASK" -> DungeonMaskProcTimers.Proc.SPIRIT
            else -> null
        }

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

        /** Skyblock mask and pet heads, as player heads with a fixed skin texture. */
        private val skulls = EnumMap<DungeonMaskProcTimers.Proc, ItemStack>(DungeonMaskProcTimers.Proc::class.java)

        private fun skull(proc: DungeonMaskProcTimers.Proc): ItemStack = skulls.getOrPut(proc) {
            val hash = when (proc) {
                DungeonMaskProcTimers.Proc.SPIRIT -> "9bbe721d7ad8ab965f08cbec0b834f779b5197f79da4aea3d13d253ece9dec2"
                DungeonMaskProcTimers.Proc.BONZO -> "12716ecbf5b8da00b05f316ec6af61e8bd02805b21eb8e440151468dc656549c"
                DungeonMaskProcTimers.Proc.PHOENIX -> "66b1b59bc890c9c97527787dde20600c8b86f6b9912d51a6bfcdb0e4c2aa3c97"
            }
            val json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$hash\"}}}"
            val property = Property("textures", Base64.getEncoder().encodeToString(json.toByteArray()))
            val properties = PropertyMap(ImmutableMultimap.builder<String, Property>().put("textures", property).build())
            val stack = ItemStack(Items.PLAYER_HEAD)
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(GameProfile(UUID.randomUUID(), "_", properties)))
            stack
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
