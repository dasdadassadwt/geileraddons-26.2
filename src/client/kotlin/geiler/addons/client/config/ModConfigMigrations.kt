package geiler.addons.client.config

import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.impl.ChatChannelsModule
import geiler.addons.client.module.impl.ChatModule
import geiler.addons.client.module.impl.CritterMarkersModule
import geiler.addons.client.module.impl.DungeonBloodCampModule
import geiler.addons.client.module.impl.DungeonHelperModule
import geiler.addons.client.module.impl.DungeonInvincibilityTimerModule
import geiler.addons.client.module.impl.DungeonLividSolverModule
import geiler.addons.client.module.impl.DungeonPuzzleSolversModule
import geiler.addons.client.module.impl.DungeonRoomAlertModule
import geiler.addons.client.module.impl.DungeonRunSplitsModule
import geiler.addons.client.module.impl.DungeonScorerModule
import geiler.addons.client.module.impl.DungeonTerminalStatsModule
import geiler.addons.client.module.impl.DungeonTerminalTitlesModule
import geiler.addons.client.module.impl.GeneralModule
import geiler.addons.client.module.impl.PlayerNamesModule
import geiler.addons.client.module.impl.PlayerSizeModule
import geiler.addons.client.module.impl.SecretClickedModule
import geiler.addons.client.module.impl.SecretWaypointsModule
import geiler.addons.client.module.impl.SpiritLeapMenuModule
import geiler.addons.client.presentation.PlayerPresentationPolicy

/** Kotlin implementations of config migrations whose Java entrypoints remain stable. */
object ModConfigMigrations {
    /**
     * Moves the removed Hideonwall ESP module into Critter Markers. Its on/off state becomes Hideonwall Boxes (and turns
     * Critter Markers on when that module had no saved state, or the boxes would vanish), its Box Color becomes
     * Hideonwall Color. Saved Critter Markers values always win.
     */
    @JvmStatic
    fun migrateLegacyHideonwallEsp(
        enabled: MutableMap<String, Boolean?>,
        toggles: MutableMap<String, Boolean?>,
        colors: MutableMap<String, IntArray?>,
    ): Boolean {
        val markers = CritterMarkersModule.INSTANCE
        val oldEnabled = enabled["Hideonwall ESP"]
        var changed = false
        val boxesKey = ConfigSettingKeys.settingKey(markers, "Hideonwall Boxes")
        if (oldEnabled != null && !toggles.containsKey(boxesKey)) {
            toggles[boxesKey] = oldEnabled
            changed = true
            if (oldEnabled && !enabled.containsKey(markers.configName())) enabled[markers.configName()] = true
        }
        return ConfigSettingKeys.copySetting(
            colors, "Hideonwall ESP.Box Color", ConfigSettingKeys.settingKey(markers, "Hideonwall Color"),
        ) || changed
    }

    /** Moves the former channel-history HUD preference to the standalone Chat module once. */
    @JvmStatic
    fun migrateLegacyChatHud(
        enabled: MutableMap<String, Boolean?>?,
        toggles: MutableMap<String, Boolean?>?,
    ): Boolean {
        if (enabled == null || toggles == null || enabled.containsKey(ChatModule.INSTANCE.configName()) ||
            enabled[ChatChannelsModule.INSTANCE.configName()] != true
        ) return false
        val legacyKey = ConfigSettingKeys.settingKey(ChatChannelsModule.INSTANCE, "Show Channel History HUD")
        if (toggles[legacyKey] != true) return false
        enabled[ChatModule.INSTANCE.configName()] = true
        return true
    }

    /** Keeps the former shared Room Alerts sound as the Room Cleared sound after splitting the IDs. */
    @JvmStatic
    fun migrateDungeonRoomAlertSoundSettings(texts: MutableMap<String, String?>): Boolean {
        val alerts = DungeonRoomAlertModule.INSTANCE
        val legacyKey = alerts.configName() + ".Sound ID"
        val roomClearKey = ConfigSettingKeys.settingKey(alerts, "Room Cleared Sound ID")
        return ConfigSettingKeys.copySetting(texts, legacyKey, roomClearKey)
    }

    /** The two alert groups once shared five keys; the Secrets group now has its own, seeded from the shared value. */
    @JvmStatic
    fun migrateDungeonRoomAlertSharedSettings(
        toggles: MutableMap<String, Boolean?>,
        numbers: MutableMap<String, Float?>,
    ): Boolean {
        val alerts = DungeonRoomAlertModule.INSTANCE
        fun key(name: String) = ConfigSettingKeys.settingKey(alerts, name)
        var changed = false
        for ((old, new) in listOf("Show Title" to "Secrets Show Title", "Play Sound" to "Secrets Play Sound")) {
            changed = ConfigSettingKeys.copySetting(toggles, key(old), key(new)) || changed
        }
        for ((old, new) in listOf("Sound Volume" to "Secrets Sound Volume", "Sound Pitch" to "Secrets Sound Pitch")) {
            changed = ConfigSettingKeys.copySetting(numbers, key(old), key(new)) || changed
        }
        return changed
    }

    /** Maps compatible values from the former standalone Blood Camp controls onto Odin's settings. */
    @JvmStatic
    fun migrateLegacyBloodCampSettings(
        toggles: MutableMap<String, Boolean?>,
        colors: MutableMap<String, IntArray?>,
        numbers: MutableMap<String, Float?>,
    ): Boolean {
        val blood = DungeonBloodCampModule.INSTANCE
        val oldPrefix = blood.configName() + "."
        var changed = ConfigSettingKeys.copySetting(
            toggles, oldPrefix + "Position Prediction", ConfigSettingKeys.settingKey(blood, "Blood Camp Assist"),
        )
        changed = ConfigSettingKeys.copySetting(
            toggles, oldPrefix + "Target Tracer", ConfigSettingKeys.settingKey(blood, "Line"),
        ) || changed
        changed = ConfigSettingKeys.copySetting(
            toggles, oldPrefix + "World Countdown", ConfigSettingKeys.settingKey(blood, "Time Left"),
        ) || changed
        changed = migrateBloodCampColorVisibility(
            toggles, colors, oldPrefix + "Target Box", blood.positionColorSetting(),
        ) || changed
        changed = migrateBloodCampColorVisibility(
            toggles, colors, oldPrefix + "Chicken Man Destination Box", blood.spawnColorSetting(),
        ) || changed

        val oldScaleKey = oldPrefix + "Marker Scale"
        val newScaleKey = ConfigSettingKeys.settingKey(blood, "Box Size")
        val oldScale = numbers[oldScaleKey]
        if (!numbers.containsKey(newScaleKey) && oldScale != null && oldScale.isFinite()) {
            val setting = blood.boxSizeSetting()
            numbers[newScaleKey] = java.lang.Math.max(setting.min(), java.lang.Math.min(setting.max(), oldScale))
            changed = true
        }
        return changed
    }

    private fun migrateBloodCampColorVisibility(
        toggles: Map<String, Boolean?>,
        colors: MutableMap<String, IntArray?>,
        oldToggleKey: String,
        color: ColorSetting,
    ): Boolean {
        val enabled = toggles[oldToggleKey] ?: return false
        val colorKey = ConfigSettingKeys.settingKey(DungeonBloodCampModule.INSTANCE, color.name())
        if (colors.containsKey(colorKey)) return false
        colors[colorKey] = intArrayOf(color.red(), color.green(), color.blue(), if (enabled) 255 else 0)
        return true
    }

    /** Moves the former combined dungeon HUD position onto its Run Splits successor. */
    @JvmStatic
    fun migrateLegacyDungeonHudPosition(hudPositions: MutableMap<String, FloatArray?>?): Boolean {
        if (hudPositions == null) return false
        val legacy = hudPositions.remove("dungeon_odin_helpers") ?: return false
        hudPositions.putIfAbsent("dungeon_run_splits", legacy.copyOf())
        return true
    }

    @JvmStatic
    fun resolveLegacyUtilityTimerHud(savedHud: Boolean?, oldEnabled: Boolean?, oldHud: Boolean?): Boolean? {
        if (savedHud != null) return savedHud
        if (oldEnabled == null) return oldHud
        return oldEnabled && (oldHud == null || oldHud)
    }

    @JvmStatic
    fun resolveLegacyUtilityTimerAlert(savedAlert: Boolean?, oldEnabled: Boolean?, oldAlert: Boolean?): Boolean? {
        if (savedAlert != null) return savedAlert
        if (oldEnabled == null) return oldAlert
        if (!oldEnabled) return false
        return oldAlert
    }

    /** Restores count display/source from the prior combined dungeon helper settings. */
    @JvmStatic
    fun migrateLegacyDungeonSplitTiming(
        toggles: MutableMap<String, Boolean?>,
        choices: MutableMap<String, String?>,
    ): Boolean {
        val oldPrefix = "Dungeon Odin Helpers."
        val module = DungeonRunSplitsModule.INSTANCE
        val legacyMode = choices[oldPrefix + "Split Timing Mode"]
        val recognizedLegacyMode = legacyMode == "REAL_TIME" || legacyMode == "CLIENT_TICKS" ||
            legacyMode == "SERVER_PING_PACKETS"
        val countSourceKey = ConfigSettingKeys.settingKey(module, "Count Source")
        val showCountKey = ConfigSettingKeys.settingKey(module, "Show Count Value")
        val showTickTimeKey = ConfigSettingKeys.settingKey(module, "Show Tick Time")
        val showRealTimeKey = ConfigSettingKeys.settingKey(module, "Show Real Time")
        var migrated = false
        if (!choices.containsKey(countSourceKey)) {
            var interimSource = choices[oldPrefix + "Tick Count Source"]
            if (interimSource == null) interimSource = choices[oldPrefix + "Count Source"]
            val migratedSource = when {
                interimSource == "CLIENT_TICKS" || interimSource == "SERVER_PING_PACKETS" -> interimSource
                recognizedLegacyMode && legacyMode == "SERVER_PING_PACKETS" -> "SERVER_PING_PACKETS"
                recognizedLegacyMode -> "CLIENT_TICKS"
                else -> null
            }
            if (migratedSource != null) {
                choices[countSourceKey] = migratedSource
                migrated = true
            }
        }
        if (!toggles.containsKey(showCountKey) && recognizedLegacyMode) {
            val showTickTiming = toggles[oldPrefix + "Show Tick Timing"] ?: (legacyMode != "REAL_TIME")
            toggles[showCountKey] = showTickTiming
            migrated = true
        }
        if (recognizedLegacyMode) {
            // The old mode was a single selection. Preserve it instead of enabling both new clocks.
            if (!toggles.containsKey(showTickTimeKey)) {
                toggles[showTickTimeKey] = legacyMode == "CLIENT_TICKS"
                migrated = true
            }
            if (!toggles.containsKey(showRealTimeKey)) {
                toggles[showRealTimeKey] = legacyMode == "REAL_TIME"
                migrated = true
            }
        }
        return migrated
    }

    /** Carries settings from the removed combined dungeon helper into its focused modules. */
    @JvmStatic
    fun migrateLegacyDungeonFeatureSettings(
        enabled: MutableMap<String, Boolean?>?,
        toggles: MutableMap<String, Boolean?>,
        numbers: MutableMap<String, Float?>,
        colors: MutableMap<String, IntArray?>,
        choices: MutableMap<String, String?>,
        texts: MutableMap<String, String?>,
    ): Boolean {
        val legacyModuleName = "Dungeon Odin Helpers"
        val oldPrefix = "$legacyModuleName."
        val puzzles = DungeonPuzzleSolversModule.INSTANCE
        val scorer = DungeonScorerModule.INSTANCE
        val splits = DungeonRunSplitsModule.INSTANCE
        val blood = DungeonBloodCampModule.INSTANCE
        val livid = DungeonLividSolverModule.INSTANCE
        val invincibility = DungeonInvincibilityTimerModule.INSTANCE
        val leap = SpiritLeapMenuModule.INSTANCE
        val terminalStats = DungeonTerminalStatsModule.INSTANCE
        val terminalTitles = DungeonTerminalTitlesModule.INSTANCE
        var changed = ConfigSettingKeys.migrateLegacyModuleEnabledState(enabled, legacyModuleName, leap.configName())
        changed = ConfigSettingKeys.migrateLegacyModuleEnabledState(enabled, legacyModuleName, puzzles.configName()) || changed
        changed = migrateLegacyPuzzleSettings(toggles, numbers, colors, choices, texts, puzzles, oldPrefix) || changed

        changed = copyLegacySettings(toggles, oldPrefix, scorer,
            "Score Alerts 270/300" to "Score Alerts", "Score Alert Sound" to "Play Sound") || changed
        changed = copyLegacySettings(numbers, oldPrefix, scorer,
            "Score Alert Duration (Ticks)" to "Duration (Ticks)") || changed
        changed = copyLegacySettings(texts, oldPrefix, scorer,
            "Score Alert Text" to "Alert Text", "Score Alert Sound ID" to "Sound ID") || changed

        changed = copyLegacySettings(toggles, oldPrefix, splits,
            "Dungeon Splits" to "Show Splits", "Show Current Split" to "Show Current Split",
            "Show Tick Timing" to "Show Count Value") || changed
        changed = copyLegacySettings(choices, oldPrefix, splits, "Count Source" to "Count Source") || changed
        val splitTickKey = ConfigSettingKeys.settingKey(splits, "Show Tick Time")
        val splitCountKey = ConfigSettingKeys.settingKey(splits, "Show Count Value")
        val splitCountSourceKey = ConfigSettingKeys.settingKey(splits, "Count Source")
        if (!toggles.containsKey(splitTickKey) && toggles.containsKey(splitCountKey)) {
            val legacyCountEnabled = toggles[splitCountKey] == true
            val legacyCountWasTicks = choices[splitCountSourceKey] != "SERVER_PING_PACKETS"
            toggles[splitTickKey] = legacyCountEnabled && legacyCountWasTicks
            changed = true
        }
        changed = copyLegacySettings(toggles, oldPrefix, splits,
            "Local Run Summary" to "Local Run Summary") || changed
        changed = copyLegacySettings(toggles, oldPrefix, blood,
            "Blood Camp Position Prediction" to "Blood Camp Assist") || changed
        changed = copyLegacySettings(toggles, oldPrefix, livid,
            "Livid Highlight & Timer" to "Highlight Livid", "Livid Highlight & Timer" to "Show Invulnerability Timer") || changed
        changed = copyLegacySettings(toggles, oldPrefix, invincibility,
            "Bonzo Mask Timer" to "Bonzo Mask", "Spirit Mask Timer" to "Spirit Mask",
            "Phoenix Proc Timer" to "Phoenix") || changed

        changed = copyLegacySettings(toggles, oldPrefix, leap,
            "Spirit Leap Cards & Keys" to "Spirit Leap Cards & Keys", "Leap on Mouse Release" to "Leap on Mouse Release",
            "Announce Leaps" to "Announce Leaps", "Spirit Leap Sound" to "Play Arrival Sound",
            "Incoming Leap Title" to "Show Incoming Leap Title", "Class Colors" to "Class Colors") || changed
        changed = copyLegacySettings(numbers, oldPrefix, leap,
            "Incoming Leap Title Duration (Ticks)" to "Title Duration (Ticks)") || changed
        changed = copyLegacySettings(choices, oldPrefix, leap,
            "Leap Sorting" to "Sort Teammates", "Leap Key Mode" to "Key Mode") || changed
        changed = copyLegacySettings(texts, oldPrefix, leap,
            "Spirit Leap Sound ID" to "Arrival Sound ID", "Custom Leap Order" to "Custom Order",
            "Top Left Key" to "Top Left Key", "Top Right Key" to "Top Right Key",
            "Bottom Left Key" to "Bottom Left Key", "Bottom Right Key" to "Bottom Right Key",
            "Archer Key" to "Archer Key", "Berserk Key" to "Berserk Key", "Healer Key" to "Healer Key",
            "Mage Key" to "Mage Key", "Tank Key" to "Tank Key") || changed

        changed = copyLegacySettings(toggles, oldPrefix, terminalStats,
            "Terminal Breakdown" to "Show Breakdown", "Count Self Only" to "Count Self Only") || changed
        changed = copyLegacySettings(toggles, oldPrefix, terminalTitles,
            "Replace Terminal Titles" to "Replace Titles") || changed
        changed = copyLegacySettings(texts, oldPrefix, terminalTitles,
            "Terminal Title Template" to "Title Template") || changed
        changed = migrateLegacyDungeonGuidePhaseOutputs(toggles) || changed
        return changed
    }

    private fun migrateLegacyPuzzleSettings(
        toggles: MutableMap<String, Boolean?>,
        numbers: MutableMap<String, Float?>,
        colors: MutableMap<String, IntArray?>,
        choices: MutableMap<String, String?>,
        texts: MutableMap<String, String?>,
        puzzles: DungeonPuzzleSolversModule,
        legacyPrefix: String,
    ): Boolean {
        val priorModulePrefix = "${puzzles.configName()}."
        var changed = false
        changed = copyPuzzleLegacySettings(toggles, priorModulePrefix, legacyPrefix, puzzles,
            Triple("Water Board", "Water Board", "Water Board Solver"),
            Triple("TP Maze", "TP Maze", "TP Maze Solver"),
            Triple("Ice Fill", "Ice Fill", "Ice Fill Solver"),
            Triple("Blaze", "Blaze", "Blaze Solver"),
            Triple("Creeper Beams", "Creeper Beam Guidance", "Creeper Beams Solver"),
            Triple("Three Weirdos", "Three Weirdos", "Three Weirdos"),
            Triple("Quiz", "Quiz", "Quiz Solver"),
            Triple("Boulder", "Boulder", "Boulder Solver"),
            Triple("Creeper Beam Hit Ding", "Creeper Beam Hit Ding", "Beam Hit Sound"),
            Triple("Show Route Tracer", "TP Maze Tracer", "Maze Tracer")) || changed
        changed = copyPuzzleLegacySettings(colors, priorModulePrefix, legacyPrefix, puzzles,
            Triple("Single Candidate Color", "TP Maze Single Candidate Color", "Color for one"),
            Triple("Multiple Candidates Color", "TP Maze Multiple Candidate Color", "Color for multiple"),
            Triple("Visited Pad Color", "TP Maze Visited Color", "Color for visited"),
            Triple("TP Maze Tracer Color", "TP Maze Tracer Color", "Tracer Color")) || changed
        changed = ConfigSettingKeys.migratePuzzleStrategy(toggles, choices, puzzles, "Water Board Strategy",
            "Optimized Solutions", mapOf("STANDARD" to false, "OPTIMIZED" to true)) || changed
        changed = ConfigSettingKeys.migratePuzzleStrategy(toggles, choices, puzzles, "Ice Fill Route",
            "Use Optimized Patterns", mapOf("EASY" to false, "HARD" to true)) || changed
        return ConfigSettingKeys.migrateSameNamedPuzzleSettings(toggles, numbers, colors, choices, texts,
            puzzles, legacyPrefix) || changed
    }

    private fun <T> copyPuzzleLegacySettings(
        values: MutableMap<String, T>,
        priorModulePrefix: String,
        legacyPrefix: String,
        module: Module,
        vararg mappings: Triple<String, String, String>,
    ): Boolean {
        var changed = false
        for ((priorName, legacyName, targetName) in mappings) {
            val targetKey = ConfigSettingKeys.settingKey(module, targetName)
            var copied = ConfigSettingKeys.copySetting(values, priorModulePrefix + priorName, targetKey)
            copied = ConfigSettingKeys.copySetting(values, legacyPrefix + legacyName, targetKey) || copied
            changed = copied || changed
        }
        return changed
    }

    private fun <T> copyLegacySettings(
        values: MutableMap<String, T>,
        oldPrefix: String,
        targetModule: Module,
        vararg mappings: Pair<String, String>,
    ): Boolean {
        var changed = false
        for ((oldName, targetName) in mappings) {
            val copied = ConfigSettingKeys.copySetting(
                values, oldPrefix + oldName, ConfigSettingKeys.settingKey(targetModule, targetName),
            )
            changed = copied || changed
        }
        return changed
    }

    private fun migrateLegacyDungeonGuidePhaseOutputs(toggles: MutableMap<String, Boolean?>): Boolean {
        val guide = DungeonHelperModule.INSTANCE
        val legacyPrefix = "Dungeon Guide Phase Outputs."
        var changed = false
        val floors = listOf("Entrance_", "F1_", "F2_", "F3_", "F4_", "F5_", "F6_", "F7_",
            "M1_", "M2_", "M3_", "M4_", "M5_", "M6_", "M7_")
        for (setting in guide.booleanSettings()) {
            val name = setting.name()
            var legacyName = name
            for (floor in floors) {
                if (name.startsWith(floor)) {
                    legacyName = name.substring(floor.length)
                    break
                }
            }
            changed = copyLegacySettings(toggles, legacyPrefix, guide, legacyName to name) || changed
        }
        return changed
    }

    @JvmStatic
    fun migrateLegacyUtilityTimer(
        enabled: MutableMap<String, Boolean?>,
        toggles: MutableMap<String, Boolean?>,
        texts: MutableMap<String, String?>,
        numbers: MutableMap<String, Float?>,
    ): Boolean {
        val oldPrefix = "Utility Timer."
        val newPrefix = "General."
        var changed = false
        val oldEnabled = enabled["Utility Timer"]
        val oldHudKey = oldPrefix + "Show Timer HUD"
        val newHudKey = newPrefix + GeneralModule.INSTANCE.showTimerHud().name()
        val oldHud = toggles[oldHudKey]
        if (!toggles.containsKey(newHudKey)) {
            val migratedHud = resolveLegacyUtilityTimerHud(null, oldEnabled, oldHud)
            if (migratedHud != null) {
                toggles[newHudKey] = migratedHud
                changed = true
            }
        }

        val oldAlertKey = oldPrefix + "Completion Alert"
        val newAlertKey = newPrefix + GeneralModule.INSTANCE.timerCompletionAlert().name()
        if (!toggles.containsKey(newAlertKey)) {
            val oldAlert = toggles[oldAlertKey]
            val migratedAlert = resolveLegacyUtilityTimerAlert(null, oldEnabled, oldAlert)
            if (migratedAlert != null) {
                toggles[newAlertKey] = migratedAlert
                changed = true
            }
        }
        changed = ConfigSettingKeys.copySetting(
            toggles,
            oldPrefix + "Chat Alert",
            newPrefix + GeneralModule.INSTANCE.timerCompletionChat().name(),
        ) || changed
        changed = ConfigSettingKeys.copySetting(
            toggles,
            oldPrefix + "Title Alert",
            newPrefix + GeneralModule.INSTANCE.timerCompletionTitle().name(),
        ) || changed
        changed = ConfigSettingKeys.copySetting(
            toggles,
            oldPrefix + "Sound Alert",
            newPrefix + GeneralModule.INSTANCE.timerCompletionSound().name(),
        ) || changed
        changed = ConfigSettingKeys.copySetting(
            texts,
            oldPrefix + "Completion Text",
            newPrefix + GeneralModule.INSTANCE.timerCompletionText().name(),
        ) || changed
        changed = ConfigSettingKeys.copySetting(
            numbers,
            oldPrefix + "Title Duration (ticks)",
            newPrefix + GeneralModule.INSTANCE.timerTitleDurationTicks().name(),
        ) || changed
        return changed
    }

    /** Copies presentation settings into the split modules without migrating the old master toggle. */
    @JvmStatic
    fun migrateLegacyPlayerPresentation(
        enabled: MutableMap<String, Boolean?>,
        toggles: MutableMap<String, Boolean?>,
    ): Boolean {
        var changed = false
        val oldEnabled = enabled["Player Presentation"]
        val namesKey = PlayerNamesModule.INSTANCE.configName()
        val sizeKey = PlayerSizeModule.INSTANCE.configName()
        val savedNamesEnabled = PlayerPresentationPolicy.splitModuleEnable(enabled[namesKey], oldEnabled)
        val savedSizeEnabled = PlayerPresentationPolicy.splitModuleEnable(enabled[sizeKey], oldEnabled)
        if (savedNamesEnabled != null && savedNamesEnabled != enabled.put(namesKey, savedNamesEnabled)) changed = true
        if (savedSizeEnabled != null && savedSizeEnabled != enabled.put(sizeKey, savedSizeEnabled)) changed = true

        val oldPrefix = "Player Presentation."
        val namesPrefix = PlayerNamesModule.INSTANCE.configName() + "."
        val sizePrefix = PlayerSizeModule.INSTANCE.configName() + "."
        changed = ConfigSettingKeys.copySetting(toggles, oldPrefix + "Replace Names", namesPrefix + "Replace Names") || changed
        changed = ConfigSettingKeys.copySetting(toggles, oldPrefix + "Apply Name Styles", namesPrefix + "Apply Name Styles") || changed
        changed = ConfigSettingKeys.copySetting(toggles, oldPrefix + "Apply Sizes", sizePrefix + "Use Hosted Sizes") || changed
        changed = ConfigSettingKeys.copySetting(toggles, oldPrefix + "Use Hosted Sizes", sizePrefix + "Use Hosted Sizes") || changed
        return changed
    }

    /**
     * Chat waypoints used to be part of Waypoints. Copies their settings (and the enabled state, since they ran
     * whenever Waypoints did) to Chat Waypoints once; a value that already exists there is never overwritten.
     */
    @JvmStatic
    fun migrateChatWaypointSplit(
        enabled: MutableMap<String, Boolean?>,
        toggles: MutableMap<String, Boolean?>,
        colors: MutableMap<String, IntArray?>,
        numbers: MutableMap<String, Float?>,
    ): Boolean {
        val old = "Waypoints."
        val new = "Chat Waypoints."
        var changed = false
        for (name in listOf("From Party Chat", "From Guild Chat", "From All Chat", "Own Messages", "Show Distance")) {
            changed = ConfigSettingKeys.copySetting(toggles, old + name, new + name) || changed
        }
        for (name in listOf("Duration", "Text Scale", "Max Distance")) {
            changed = ConfigSettingKeys.copySetting(numbers, old + name, new + name) || changed
        }
        changed = ConfigSettingKeys.copySetting(colors, old + "Chat Waypoint Color", new + "Chat Waypoint Color") || changed
        if (!enabled.containsKey("Chat Waypoints") && enabled["Waypoints"] == true) {
            enabled["Chat Waypoints"] = true
            changed = true
        }
        return changed
    }

    /** Reads and removes the former global Waypoints "Show Names" value; it now lives on each group. */
    @JvmStatic
    fun takeLegacyWaypointShowNames(toggles: MutableMap<String, Boolean?>): Boolean? = toggles.remove("Waypoints.Show Names")

    /** Renamed Secret Waypoint labels keep values under their original module keys. */
    @JvmStatic
    fun migrateSecretSettingNames(
        toggles: MutableMap<String, Boolean?>,
        colors: MutableMap<String, IntArray?>,
        numbers: MutableMap<String, Float?>,
        texts: MutableMap<String, String?>,
    ): Boolean {
        val sourcePrefix = "Secret Waypoints."
        val targetPrefix = SecretWaypointsModule.INSTANCE.configName() + "."
        val legacyDungeonPrefix = "Dungeon Secret Waypoints."
        var changed = false
        val renames = arrayOf(
            arrayOf("Scan Loaded Room Candidates", "Find Live Markers"),
            arrayOf("Chest Candidates", "Chests"),
            arrayOf("Dropped Item Candidates", "Dropped Items"),
            arrayOf("Essence Drop Candidates", "Essence"),
            arrayOf("Bat Candidates", "Bats"),
            arrayOf("Item Frame Candidates", "Item Frames"),
            arrayOf("Redstone Key Candidates", "Redstone Keys"),
            arrayOf("Lever Candidates", "Levers"),
            arrayOf("Candidate Color", "Live Marker Color"),
        )
        for (rename in renames) {
            changed = ConfigSettingKeys.copyFirstSetting(
                toggles, sourcePrefix + rename[0], legacyDungeonPrefix + rename[0], targetPrefix + rename[1],
            ) || changed
            changed = ConfigSettingKeys.copyFirstSetting(
                colors, sourcePrefix + rename[0], legacyDungeonPrefix + rename[0], targetPrefix + rename[1],
            ) || changed
        }

        val clickedPrefix = SecretClickedModule.INSTANCE.configName() + "."
        val clickedRenames = arrayOf(
            arrayOf("Chime on Marked Click", "Play Chime"),
            arrayOf("Click Feedback Window (ms)", "Confirmation Window (ms)"),
            arrayOf("Allow Secret Block Feedback in Boss", "Allow Secret Blocks in Boss"),
            arrayOf("Click Chime", "Chime Sound"),
            arrayOf("Chime Volume", "Chime Volume"),
            arrayOf("Chime Pitch", "Chime Pitch"),
        )
        for (rename in clickedRenames) {
            val oldKey = sourcePrefix + rename[0]
            val legacyKey = legacyDungeonPrefix + rename[0]
            val newKey = clickedPrefix + rename[1]
            changed = ConfigSettingKeys.copyFirstSetting(toggles, oldKey, legacyKey, newKey) || changed
            changed = ConfigSettingKeys.copyFirstSetting(numbers, oldKey, legacyKey, newKey) || changed
            changed = ConfigSettingKeys.copyFirstSetting(texts, oldKey, legacyKey, newKey) || changed
        }
        return changed
    }
}
