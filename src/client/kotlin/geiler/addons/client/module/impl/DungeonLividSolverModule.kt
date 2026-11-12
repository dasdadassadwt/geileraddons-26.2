package geiler.addons.client.module.impl

import geiler.addons.client.module.ModuleSettingsSpec

/** Highlights the real Livid and shows its invulnerability timer in Dungeons. */
class DungeonLividSolverModule private constructor() : DungeonTextHudModule(
    "Livid Helper",
    "Identifies the real Livid, highlights the matching target, and shows its timer.",
    "dungeon_livid_solver",
    "Livid Helper",
    highlight,
    showTimer,
    showTargetTitle,
    showTracer,
    useTargetColor,
    tracerColor,
    tracerWidth,
) {
    init {
        group(*spec.groups.toTypedArray())
    }

    override fun configName(): String = "Livid Solver"

    fun showTargetTitle(): Boolean = showTargetTitle.value()

    fun showTracer(): Boolean = showTracer.value()

    fun useTargetColor(): Boolean = useTargetColor.value()

    fun tracerColorArgb(): Int = tracerColor.argb()

    fun tracerWidth(): Float = tracerWidth.value()

    override fun previewLines(): List<String> = listOf(lividLabel("gray"), "Invulnerable · 3.2s")

    companion object {
        /** Title/HUD text for the target: just the wool color, e.g. `GRAY`. */
        @JvmStatic
        fun lividLabel(colorName: String): String = colorName.uppercase(java.util.Locale.ROOT)

        /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
        private val spec = ModuleSettingsSpec.of("Livid Helper")

        /** These static fields are part of the Java Odin adapter and config-facing API. */
        @JvmField
        val highlight = spec.toggle("highlight")

        @JvmField
        val showTimer = spec.toggle("showTimer")

        @JvmField
        val showTargetTitle = spec.toggle("showTargetTitle")

        @JvmField
        val showTracer = spec.toggle("showTracer")

        @JvmField
        val useTargetColor = spec.toggle("useTargetColor")

        @JvmField
        val tracerColor = spec.color("tracerColor")

        @JvmField
        val tracerWidth = spec.slider("tracerWidth")

        @JvmField
        val INSTANCE = DungeonLividSolverModule()
    }
}
