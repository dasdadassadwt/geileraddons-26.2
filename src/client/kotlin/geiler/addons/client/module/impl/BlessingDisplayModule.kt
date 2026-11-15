package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.BlessingRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.ModuleSettingsSpec

/** HUD of the dungeon's active blessings, read from the tab-list footer (Odin's Blessing Display). */
class BlessingDisplayModule private constructor() : DungeonTextHudModule(
    "Blessing Display",
    "Shows the active blessings of the dungeon.",
    "blessing_display",
    "Blessing Display",
    *spec.settings.toTypedArray(),
) {
    private val power = spec.toggle("power")
    private val time = spec.toggle("time")
    private val stone = spec.toggle("stone")
    private val life = spec.toggle("life")
    private val wisdom = spec.toggle("wisdom")

    private var levels: Map<BlessingRules.Blessing, Int> = emptyMap()
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun previewLines(): List<String> = listOf("§4Power: §a19§r", "§5Time: §a19§r")

    override fun onEnable() {
        diagnostics.enable("power=${power.value()} time=${time.value()} stone=${stone.value()} life=${life.value()} wisdom=${wisdom.value()}")
        refreshLines()
    }

    override fun onDisable() {
        clearHudLines()
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    /** Called for every tab-list footer packet. */
    fun onTabFooter(footer: String) {
        if (!isEnabled() || !DungeonContextTracker.inDungeon()) return
        val parsed = BlessingRules.parseFooter(footer)
        if (parsed != levels) diagnostics.event("BLESSINGS ${parsed.entries.joinToString(",") { "${it.key.label}=${it.value}" }}")
        levels = parsed
        refreshLines()
    }

    /** Called each client tick: drops the HUD when the dungeon is left. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val inDungeon = DungeonContextTracker.inDungeon()
        if (!inDungeon && levels.isNotEmpty()) {
            levels = emptyMap()
            refreshLines()
        }
        diagnostics.status { "enabled=true inDungeon=$inDungeon blessings=${levels.size} suppressedEvents=$it" }
    }

    private fun refreshLines() {
        val lines = BlessingRules.lines(levels, power.value(), time.value(), stone.value(), life.value(), wisdom.value())
        if (lines.isEmpty()) clearHudLines() else publishHudLines(lines)
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Blessing Display")

        @JvmField
        val INSTANCE = BlessingDisplayModule()
    }
}
