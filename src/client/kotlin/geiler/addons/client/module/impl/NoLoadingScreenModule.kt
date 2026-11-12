package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module

/**
 * Closes the terrain loading screen shown after joining a world or changing dimension, so the game is visible at once.
 * The chunks still load in the background and the player does not move until its chunk is loaded, as in vanilla.
 */
class NoLoadingScreenModule private constructor() : Module(
    "No Loading Screen",
    "Skips the terrain loading screen after joining a world or changing dimension and shows the game immediately.",
    Category.MISCELLANEOUS,
    false,
) {
    companion object {
        @JvmField
        val INSTANCE = NoLoadingScreenModule()
    }

    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() })
    private var skipped = 0

    override fun onEnable() {
        skipped = 0
        diagnostics.enable("screens=LevelLoadingScreen")
    }

    override fun onDisable() {
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { suppressed -> "skippedSinceEnable=$skipped suppressedEvents=$suppressed" }
    }

    /** True while the loading screen should be skipped. */
    fun shouldSkip(): Boolean = isEnabled()

    /** Called by the mixin each time it closes a loading screen. */
    fun recordSkip(reason: String) {
        skipped++
        diagnostics.event("SKIPPED LevelLoadingScreen reason=$reason")
    }
}
