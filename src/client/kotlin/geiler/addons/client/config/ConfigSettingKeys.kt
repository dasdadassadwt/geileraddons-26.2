package geiler.addons.client.config

import geiler.addons.client.module.Module

/** Shared setting-key and compatibility-map rules used while loading and migrating config data. */
object ConfigSettingKeys {
    @JvmStatic
    fun settingKey(module: Module, settingName: String): String = "${module.configName()}.${settingName}"

    /** Reads a renamed setting without letting a shorter UI label discard its saved value. */
    @JvmStatic
    fun <T> settingValue(values: Map<String, T>, module: Module, settingName: String): T? {
        val value = values[settingKey(module, settingName)]
        if (value != null) return value
        val legacyKey = legacySettingKey(module, settingName)
        return if (legacyKey == null) null else values[legacyKey]
    }

    @JvmStatic
    fun <T> copyFirstSetting(
        values: MutableMap<String, T>,
        firstSource: String,
        secondSource: String,
        target: String,
    ): Boolean {
        if (values.containsKey(target)) return false
        if (values.containsKey(firstSource)) {
            values[target] = values.getValue(firstSource)
            return true
        }
        if (values.containsKey(secondSource)) {
            values[target] = values.getValue(secondSource)
            return true
        }
        return false
    }

    @JvmStatic
    fun <T> copySetting(values: MutableMap<String, T>, source: String, target: String): Boolean {
        if (values.containsKey(target) || !values.containsKey(source)) return false
        values[target] = values.getValue(source)
        return true
    }

    /** Copies same-named legacy settings through their original typed config maps. */
    @JvmStatic
    fun migrateSameNamedPuzzleSettings(
        toggles: MutableMap<String, Boolean?>?,
        numbers: MutableMap<String, Float?>?,
        colors: MutableMap<String, IntArray?>?,
        choices: MutableMap<String, String?>?,
        texts: MutableMap<String, String?>?,
        puzzles: Module?,
        legacyPrefix: String?,
    ): Boolean {
        if (toggles == null || numbers == null || colors == null || choices == null || texts == null ||
            puzzles == null || legacyPrefix == null
        ) return false

        var changed = false
        for (setting in puzzles.booleanSettings()) {
            changed = copySetting(toggles, legacyPrefix + setting.name(), settingKey(puzzles, setting.name())) || changed
        }
        for (setting in puzzles.numberSettings()) {
            changed = copySetting(numbers, legacyPrefix + setting.name(), settingKey(puzzles, setting.name())) || changed
        }
        for (setting in puzzles.colorSettings()) {
            changed = copySetting(colors, legacyPrefix + setting.name(), settingKey(puzzles, setting.name())) || changed
        }
        for (setting in puzzles.choiceSettings()) {
            changed = copySetting(choices, legacyPrefix + setting.name(), settingKey(puzzles, setting.name())) || changed
        }
        for (setting in puzzles.textSettings()) {
            changed = copySetting(texts, legacyPrefix + setting.name(), settingKey(puzzles, setting.name())) || changed
        }
        return changed
    }

    /** Translates only explicitly recognized legacy puzzle strategy values. */
    @JvmStatic
    fun migratePuzzleStrategy(
        toggles: MutableMap<String, Boolean?>?,
        choices: Map<String, String?>?,
        puzzles: Module?,
        oldName: String?,
        targetName: String?,
        translation: Map<String, Boolean?>?,
    ): Boolean {
        if (toggles == null || choices == null || puzzles == null || translation == null) return false
        // Java string concatenation in the former implementation rendered null names as "null".
        val targetKey = "${puzzles.configName()}.$targetName"
        if (toggles.containsKey(targetKey)) return false
        val choice = choices["${puzzles.configName()}.$oldName"]
            ?: choices["Dungeon Odin Helpers.$oldName"]
            ?: return false
        val migrated = translation[choice] ?: return false
        toggles[targetKey] = migrated
        return true
    }

    /** Copies a legacy module toggle only when the successor has no saved choice. */
    @JvmStatic
    fun migrateLegacyModuleEnabledState(
        enabled: MutableMap<String, Boolean?>?,
        legacyModuleName: String?,
        newModuleName: String?,
    ): Boolean {
        if (enabled == null) return false
        @Suppress("UNCHECKED_CAST")
        val nullableKeys = enabled as MutableMap<String?, Boolean?>
        if (nullableKeys.containsKey(newModuleName)) return false
        val legacyEnabled = nullableKeys[legacyModuleName] ?: return false
        nullableKeys[newModuleName] = legacyEnabled
        return true
    }

    @JvmStatic
    fun legacySettingKey(module: Module, settingName: String): String? {
        if (module.name() != "Auto Kick") return null
        val separator = settingName.indexOf(' ')
        if (separator <= 0) return null
        val floor = settingName.substring(0, separator)
        val suffix = settingName.substring(separator + 1)
        val legacySuffix = when (suffix) {
            "Ask Before" -> "Ask Before Kick"
            "Dupe" -> "Dupe Check"
            "Cata" -> "Minimum Cata"
            "Class" -> "Minimum Joined Class"
            "Class Avg" -> "Minimum Class Average"
            "Secrets" -> "Minimum Secrets"
            "Secret Avg" -> "Minimum Secret Average"
            "MP" -> "Minimum Magical Power"
            "Minimum PB" -> "Maximum PB Seconds"
            "Bank" -> "Minimum Bank"
            "Terminator" -> "Require Terminator"
            "Hyperion" -> "Require Hyperion"
            "GDrag" -> "Require Golden Dragon"
            else -> return null
        }
        return settingKey(module, "$floor $legacySuffix")
    }
}
