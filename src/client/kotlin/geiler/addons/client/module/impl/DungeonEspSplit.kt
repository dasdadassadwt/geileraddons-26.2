package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting

/** Carries Dungeon Mob ESP settings into the Boss ESP and Class ESP modules split out of it. */
object DungeonEspSplitMigration {
    private const val OLD_MODULE = "Dungeon Mob ESP"

    /** Boss ESP starts on only when Mob ESP was on and its Wither or Mimic switch (both default on) was on. */
    @JvmStatic
    fun bossEnabled(oldEnabled: Boolean?, withers: Boolean?, mimic: Boolean?): Boolean? =
        if (oldEnabled == null) null else oldEnabled && ((withers ?: true) || (mimic ?: true))

    /** Class ESP starts on only when Mob ESP was on and Party Only Class Highlight (default off) was on. */
    @JvmStatic
    fun classEnabled(oldEnabled: Boolean?, party: Boolean?): Boolean? =
        if (oldEnabled == null) null else oldEnabled && (party ?: false)

    internal fun oldKey(settingName: String): String = "$OLD_MODULE.$settingName"

    internal fun restoreNumber(module: Module, values: Map<String, Float>, setting: NumberSetting) {
        val old = values[oldKey(setting.name())] ?: return
        if (!values.containsKey(module.configName() + "." + setting.name())) setting.setValue(old)
    }

    internal fun restoreToggle(module: Module, values: Map<String, Boolean>, setting: BooleanSetting) {
        val old = values[oldKey(setting.name())] ?: return
        if (!values.containsKey(module.configName() + "." + setting.name())) setting.setValue(old)
    }

    internal fun restoreColor(module: Module, values: Map<String, IntArray>, setting: ColorSetting) {
        val old = values[oldKey(setting.name())] ?: return
        if (old.size == 4 && !values.containsKey(module.configName() + "." + setting.name())) {
            setting.set(old[0], old[1], old[2], old[3])
        }
    }
}
