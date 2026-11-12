package geiler.addons.client.module.impl

import geiler.addons.client.module.ModuleSettingsSpec

/** Terminal, lever, and device completion counts. */
class DungeonTerminalStatsModule private constructor() : DungeonTextHudModule(
    "Terminal Stats",
    "Counts terminal, lever, and device completions during dungeon runs.",
    "dungeon_terminal_stats",
    "Terminal Stats",
    breakdown,
    selfOnly,
) {
    init {
        group(*spec.groups.toTypedArray())
    }

    override fun previewLines(): List<String> = listOf("Terminals 4 · Levers 1 · Devices 2")

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    companion object {
        private val spec = ModuleSettingsSpec.of("Terminal Stats")

        @JvmField
        val breakdown = spec.toggle("breakdown")

        @JvmField
        val selfOnly = spec.toggle("selfOnly")

        @JvmField
        val INSTANCE = DungeonTerminalStatsModule()
    }
}
