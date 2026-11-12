package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.odin.SpiritLeapIncomingPresentation
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import java.util.Map

/** Spirit Leap menu teammate cards, keys, incoming titles, and arrival announcements and sounds. */
class SpiritLeapMenuModule private constructor(private val settings: Settings) : Module(
    "Spirit Leap Menu",
    "Shows a fixed teammate card screen and provides configurable Spirit Leap keys.",
    Category.F7,
    settings.showCardsAndKeys, settings.onMouseRelease, settings.announceLeaps, settings.playSound,
    settings.incomingTitle, settings.incomingTitleDurationTicks, settings.soundId, settings.classColors,
    settings.extraRoundness, settings.cardDistance, settings.tankColor, settings.berserkColor,
    settings.mageColor, settings.archerColor, settings.healerColor, settings.showPovs, settings.povFps,
    settings.povTransparency, settings.sort, settings.keyMode, settings.customOrder, settings.topLeftKey,
    settings.topRightKey, settings.bottomLeftKey, settings.bottomRightKey, settings.archerKey,
    settings.berserkKey, settings.healerKey, settings.mageKey, settings.tankKey,
) {
    init {
        group(
            SettingGroup(
                "Menu Input", settings.showCardsAndKeys, settings.onMouseRelease, settings.keyMode,
                settings.topLeftKey, settings.topRightKey, settings.bottomLeftKey, settings.bottomRightKey,
                settings.archerKey, settings.berserkKey, settings.healerKey, settings.mageKey, settings.tankKey,
            ),
            SettingGroup("Teammate Cards", settings.classColors, settings.sort, settings.customOrder,
                settings.extraRoundness, settings.cardDistance),
            SettingGroup("POV Cameras", settings.showPovs, settings.povFps, settings.povTransparency),
            SettingGroup("Class Colors", settings.tankColor, settings.berserkColor, settings.mageColor,
                settings.archerColor, settings.healerColor),
            SettingGroup(
                "Arrival Feedback", settings.announceLeaps, settings.playSound, settings.incomingTitle,
                settings.incomingTitleDurationTicks, settings.soundId,
            ),
        )
        // Register the custom shader before the initial resource preparation, even while POVs are disabled.
        SpiritLeapPovRenderer.cameraPanelPipeline()
    }

    override fun settingTooltip(setting: Setting): String? =
        if (setting.name() == "Custom Order") {
            "Enter player names separated by commas. The first name is placed in the top-left card."
        } else null

    override fun onEnable() {
        SpiritLeapPovRenderer.INSTANCE.onFeatureEnabled()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), true)
        SpiritLeapResourceReloadListener.setDiagnosticsEnabled(true)
        GeilerAddonsLog.write(Category.F7, name(), 0,
            "FEATURE ENABLED ${diagnosticSettings()} pov=idle(reason=menu-closed)")
    }

    override fun onDisable() {
        GeilerAddonsLog.write(Category.F7, name(), 0, "FEATURE DISABLED")
        SpiritLeapResourceReloadListener.setDiagnosticsEnabled(false)
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), false)
        SpiritLeapPovRenderer.INSTANCE.close()
        DungeonOdinAssistModule.INSTANCE.clearLeapRoster()
        lastStatusTick = Long.MIN_VALUE
        lastStatus = null
        eventBudget.reset()
    }

    override fun isSettingVisible(setting: Setting): Boolean {
        if (setting === povFps || setting === povTransparency) return showPovs.value()
        if (setting === tankColor || setting === berserkColor || setting === mageColor ||
            setting === archerColor || setting === healerColor
        ) return classColors.value()
        if (setting === customOrder) return "CUSTOM" == sort.value()
        if (setting === topLeftKey || setting === topRightKey || setting === bottomLeftKey ||
            setting === bottomRightKey
        ) return "CORNERS" == keyMode.value()
        if (setting === archerKey || setting === berserkKey || setting === healerKey ||
            setting === mageKey || setting === tankKey
        ) return "CLASS" == keyMode.value()
        return super.isSettingVisible(setting)
    }

    private class Settings {
        val showCardsAndKeys = BooleanSetting("Spirit Leap Cards & Keys", "Show Cards & Keys", true)
        val onMouseRelease = BooleanSetting("Leap on Mouse Release", false)
        val announceLeaps = BooleanSetting("Announce Leaps", false)
        val playSound = BooleanSetting("Play Arrival Sound", true)
        val incomingTitle = BooleanSetting("Show Incoming Leap Title", false)
        val incomingTitleDurationTicks = NumberSetting(
            "Title Duration (Ticks)", SpiritLeapIncomingPresentation.MIN_DURATION_TICKS.toFloat(),
            SpiritLeapIncomingPresentation.MAX_DURATION_TICKS.toFloat(),
            SpiritLeapIncomingPresentation.DEFAULT_DURATION_TICKS.toFloat(), true,
        )
        val soundId = TextSetting("Arrival Sound ID", "block.note_block.pling", 96)
        val classColors = BooleanSetting("Class Colors", true)
        val extraRoundness = NumberSetting("Extra Roundness", 0f, 18f, 9f, true)
        val cardDistance = NumberSetting("Card Distance", 5.5f, 18.0f, 8.5f)
        val tankColor = ColorSetting("Tank Class Color", "Tank Color", 0, 170, 0)
        val berserkColor = ColorSetting("Berserk Class Color", "Berserk Color", 170, 0, 0)
        val mageColor = ColorSetting("Mage Class Color", "Mage Color", 85, 85, 255)
        val archerColor = ColorSetting("Archer Class Color", "Archer Color", 255, 170, 0)
        val healerColor = ColorSetting("Healer Class Color", "Healer Color", 255, 85, 255)
        val showPovs = BooleanSetting("Show POVs", "Show POVs", true)
        val povFps = NumberSetting("POV FPS", 1f, 30f, 4f, true)
        val povTransparency = NumberSetting("POV Transparency", "POV Transparency", 0f, 100f, 0f, true, false)
        val sort = ChoiceSetting(
            "Sort Teammates", "ODIN",
            Map.of("ODIN", "Class Layout", "CLASS", "Class Order", "NAME", "Name", "HEALTH", "Health", "CUSTOM", "Custom"),
            "ODIN", "CLASS", "NAME", "HEALTH", "CUSTOM",
        )
        val keyMode = ChoiceSetting(
            "Key Mode", "CORNERS", Map.of("CORNERS", "Card Corners", "CLASS", "Player Class"), "CORNERS", "CLASS",
        )
        val customOrder = TextSetting("Custom Order", "Player Names · comma separated", "", 96)
        val topLeftKey = key("Top Left Key", "Top Left Card Key", "1")
        val topRightKey = key("Top Right Key", "Top Right Card Key", "2")
        val bottomLeftKey = key("Bottom Left Key", "Bottom Left Card Key", "3")
        val bottomRightKey = key("Bottom Right Key", "Bottom Right Card Key", "4")
        val archerKey = key("Archer Key", "Archer Key", "1")
        val berserkKey = key("Berserk Key", "Berserk Key", "2")
        val healerKey = key("Healer Key", "Healer Key", "3")
        val mageKey = key("Mage Key", "Mage Key", "4")
        val tankKey = key("Tank Key", "Tank Key", "5")

        private fun key(name: String, label: String, defaultValue: String) =
            TextSetting(name, label, defaultValue, 64, true)
    }

    companion object {
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 16
        private const val MAX_DIAGNOSTIC_KEY_CHARS = 32
        private const val MAX_DIAGNOSTIC_SOUND_ID_CHARS = 96
        private const val MAX_DIAGNOSTIC_CUSTOM_ORDER_CHARS = 96
        private const val MAX_DIAGNOSTIC_CUSTOM_ORDER_ENTRIES = 48

        private val defaultSettings = Settings()

        @JvmField val showCardsAndKeys = defaultSettings.showCardsAndKeys
        @JvmField val onMouseRelease = defaultSettings.onMouseRelease
        @JvmField val announceLeaps = defaultSettings.announceLeaps
        @JvmField val playSound = defaultSettings.playSound
        @JvmField val incomingTitle = defaultSettings.incomingTitle
        @JvmField val incomingTitleDurationTicks = defaultSettings.incomingTitleDurationTicks
        @JvmField val soundId = defaultSettings.soundId
        @JvmField val classColors = defaultSettings.classColors
        @JvmField val extraRoundness = defaultSettings.extraRoundness
        @JvmField val cardDistance = defaultSettings.cardDistance
        @JvmField val tankColor = defaultSettings.tankColor
        @JvmField val berserkColor = defaultSettings.berserkColor
        @JvmField val mageColor = defaultSettings.mageColor
        @JvmField val archerColor = defaultSettings.archerColor
        @JvmField val healerColor = defaultSettings.healerColor
        @JvmField val showPovs = defaultSettings.showPovs
        @JvmField val povFps = defaultSettings.povFps
        @JvmField val povTransparency = defaultSettings.povTransparency
        @JvmField val sort = defaultSettings.sort
        @JvmField val keyMode = defaultSettings.keyMode
        @JvmField val customOrder = defaultSettings.customOrder
        @JvmField val topLeftKey = defaultSettings.topLeftKey
        @JvmField val topRightKey = defaultSettings.topRightKey
        @JvmField val bottomLeftKey = defaultSettings.bottomLeftKey
        @JvmField val bottomRightKey = defaultSettings.bottomRightKey
        @JvmField val archerKey = defaultSettings.archerKey
        @JvmField val berserkKey = defaultSettings.berserkKey
        @JvmField val healerKey = defaultSettings.healerKey
        @JvmField val mageKey = defaultSettings.mageKey
        @JvmField val tankKey = defaultSettings.tankKey

        @JvmField val INSTANCE = SpiritLeapMenuModule(defaultSettings)

        private var lastStatusTick = Long.MIN_VALUE
        private var lastStatus: String? = null
        private val eventBudget = DiagnosticEventBudget(MAX_DIAGNOSTIC_EVENTS_PER_TICK)

        @JvmStatic
        fun classColorArgb(dungeonClass: String?): Int = when (DungeonClass.parse(dungeonClass)) {
            DungeonClass.TANK -> tankColor.argb()
            DungeonClass.BERSERK -> berserkColor.argb()
            DungeonClass.MAGE -> mageColor.argb()
            DungeonClass.ARCHER -> archerColor.argb()
            DungeonClass.HEALER -> healerColor.argb()
            null -> 0xFFFFFFFF.toInt()
        }

        @JvmStatic
        fun diagnosticSettings(): String =
            "cards=${showCardsAndKeys.value()} classColors=${classColors.value()}" +
                " sort=${sort.value()} roundness=${extraRoundness.intValue()}" +
                " distance=${cardDistance.display()} colors=${tankColor.hex()}/${berserkColor.hex()}/${mageColor.hex()}/${archerColor.hex()}/${healerColor.hex()}" +
                " showPOVs=${showPovs.value()} povFps=${povUpdatesPerSecond()}" +
                " povTransparency=${povTransparencyPercent()} clickPhase=${if (onMouseRelease.value()) "release" else "press"}" +
                " keyMode=${keyMode.value()}" +
                " cornerKeys[tl=${boundedDiagnosticText(topLeftKey.value(), MAX_DIAGNOSTIC_KEY_CHARS)}" +
                ",tr=${boundedDiagnosticText(topRightKey.value(), MAX_DIAGNOSTIC_KEY_CHARS)}" +
                ",bl=${boundedDiagnosticText(bottomLeftKey.value(), MAX_DIAGNOSTIC_KEY_CHARS)}" +
                ",br=${boundedDiagnosticText(bottomRightKey.value(), MAX_DIAGNOSTIC_KEY_CHARS)}]" +
                " classKeys[archer=${boundedDiagnosticText(archerKey.value(), MAX_DIAGNOSTIC_KEY_CHARS)}" +
                ",berserk=${boundedDiagnosticText(berserkKey.value(), MAX_DIAGNOSTIC_KEY_CHARS)}" +
                ",healer=${boundedDiagnosticText(healerKey.value(), MAX_DIAGNOSTIC_KEY_CHARS)}" +
                ",mage=${boundedDiagnosticText(mageKey.value(), MAX_DIAGNOSTIC_KEY_CHARS)}" +
                ",tank=${boundedDiagnosticText(tankKey.value(), MAX_DIAGNOSTIC_KEY_CHARS)}]" +
                " customOrderEntries=${diagnosticCustomOrderEntryCount(customOrder.value())}" +
                " announceLeaps=${announceLeaps.value()} playArrivalSound=${playSound.value()}" +
                " incomingTitle=${incomingTitle.value()} titleTicks=${incomingTitleDurationTicks.intValue()}" +
                " soundId=${boundedDiagnosticText(soundId.value(), MAX_DIAGNOSTIC_SOUND_ID_CHARS)}"

        @JvmStatic
        fun boundedDiagnosticText(value: String?, maxChars: Int): String {
            if (value.isNullOrEmpty()) return ""
            val limit = maxChars.coerceIn(0, MAX_DIAGNOSTIC_SOUND_ID_CHARS)
            val length = minOf(value.length, limit)
            val safe = StringBuilder(length + if (value.length > length) 1 else 0)
            for (index in 0 until length) {
                val char = value[index]
                when {
                    char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
                        char == '.' || char == '_' || char == ':' || char == '/' || char == '+' || char == '-' -> safe.append(char)
                    Character.isWhitespace(char) -> safe.append('_')
                    else -> safe.append('?')
                }
            }
            if (value.length > length) safe.append('~')
            return safe.toString()
        }

        @JvmStatic
        fun diagnosticCustomOrderEntryCount(value: String?): Int {
            if (value.isNullOrEmpty()) return 0
            val length = minOf(value.length, MAX_DIAGNOSTIC_CUSTOM_ORDER_CHARS)
            var entries = 0
            var hasContent = false
            for (index in 0 until length) {
                val char = value[index]
                if (char == ',') {
                    if (hasContent) entries++
                    hasContent = false
                } else if (!Character.isWhitespace(char)) {
                    hasContent = true
                }
            }
            if (hasContent) entries++
            return minOf(entries, MAX_DIAGNOSTIC_CUSTOM_ORDER_ENTRIES)
        }

        @JvmStatic fun povsEnabled(): Boolean = showPovs.value()
        @JvmStatic fun povUpdatesPerSecond(): Int = povFps.intValue()
        @JvmStatic fun povTransparencyPercent(): Int = povTransparency.intValue()

        @JvmStatic
        fun shouldRenderLeapCards(cardsAndKeysVisible: Boolean, inDungeon: Boolean, hasValidMenuHead: Boolean, leapMenu: Boolean) =
            cardsAndKeysVisible && inDungeon && hasValidMenuHead && leapMenu

        @JvmStatic
        fun recordStatus(tick: Long, state: String?) {
            rollDiagnosticTick(tick)
            if (!INSTANCE.isEnabled() || state == null) return
            if (state != lastStatus || tick - lastStatusTick >= 100L) {
                lastStatus = state
                lastStatusTick = tick
                GeilerAddonsLog.write(Category.F7, INSTANCE.name(), tick, "STATUS $state")
            }
        }

        @JvmStatic
        fun recordEvent(tick: Long, event: String?) {
            rollDiagnosticTick(tick)
            if (!INSTANCE.isEnabled() || event == null) return
            if (!eventBudget.admit()) return
            GeilerAddonsLog.write(Category.F7, INSTANCE.name(), tick, event)
        }

        private fun rollDiagnosticTick(tick: Long) {
            val rollover = eventBudget.roll(tick)
            if (rollover.suppressed > 0) {
                GeilerAddonsLog.write(
                    Category.F7, INSTANCE.name(), rollover.tick,
                    "EVENTS_SUPPRESSED count=${rollover.suppressed} limit=$MAX_DIAGNOSTIC_EVENTS_PER_TICK",
                )
            }
        }
    }

    class DiagnosticEventBudget(private val limit: Int) {
        private var tick = Long.MIN_VALUE
        private var emitted = 0
        private var suppressed = 0

        init {
            if (limit <= 0) throw IllegalArgumentException("limit must be positive")
        }

        fun roll(nextTick: Long): Rollover {
            if (tick == nextTick) return Rollover(tick, 0)
            val rollover = Rollover(tick, suppressed)
            tick = nextTick
            emitted = 0
            suppressed = 0
            return rollover
        }

        fun admit(): Boolean {
            if (emitted >= limit) {
                suppressed++
                return false
            }
            emitted++
            return true
        }

        fun emitted() = emitted
        fun suppressed() = suppressed
        fun limit() = limit

        fun reset() {
            tick = Long.MIN_VALUE
            emitted = 0
            suppressed = 0
        }

        @JvmRecord
        data class Rollover(val tick: Long, val suppressed: Int) {
            override fun toString() = "Rollover[tick=$tick, suppressed=$suppressed]"
        }
    }
}
