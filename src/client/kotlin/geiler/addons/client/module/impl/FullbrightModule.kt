package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module

/** Lights every block and sky position at full brightness by overriding the lightmap state. */
class FullbrightModule private constructor() : Module(
    "Fullbright",
    "Makes everything fully lit, so caves and nights are as bright as daylight.",
    Category.VISUAL,
    false,
) {
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 160)
    private var appliedFrames = 0L
    private var refreshPending = false

    override fun onEnable() {
        appliedFrames = 0L
        refreshPending = true
        diagnostics.enable("brightness=$BRIGHTNESS nightVision=$NIGHT_VISION ambient=white")
    }

    override fun onDisable() {
        refreshPending = true
        diagnostics.event("DISABLE appliedFrames=$appliedFrames")
        diagnostics.disable()
    }

    /** Called by the shared client tick registration for the bounded status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true appliedFrames=$appliedFrames suppressedEvents=$it" }
    }

    /** True once after each switch, so the lightmap is rebuilt immediately. */
    fun consumeRefresh(): Boolean {
        val pending = refreshPending
        refreshPending = false
        return pending
    }

    /** Counts one lightmap extraction that the override was applied to. */
    fun noteApplied() {
        appliedFrames++
    }

    companion object {
        @JvmField
        val INSTANCE = FullbrightModule()

        /** Lightmap values the override forces: the gamma option at its maximum plus full night vision. */
        const val BRIGHTNESS = 1.0f
        const val NIGHT_VISION = 1.0f
        const val DARKNESS_SCALE = 0.0f
        const val BOSS_DARKENING = 0.0f
    }
}
