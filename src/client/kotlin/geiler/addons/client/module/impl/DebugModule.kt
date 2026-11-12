package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.module.Category
import geiler.addons.client.module.DebugState
import geiler.addons.client.module.Module

/**
 * The always-available master switch for optional diagnostic controls throughout the mod.
 *
 * This is intentionally a module switch rather than another BooleanSetting: it reuses the
 * existing enabled-state persistence and makes the Dev category's only card self-explanatory.
 */
class DebugModule private constructor() : Module(
    "Debug",
    "Enables diagnostic logs and developer diagnostics across the mod.",
    Category.DEV,
    false,
) {
    override fun onEnable() {
        DebugState.setEnabled(true)
        GeilerAddonsLog.start()
    }

    override fun onDisable() {
        DebugState.setEnabled(false)
        GeilerAddonsLog.stop()
    }

    companion object {
        @JvmField
        val INSTANCE = DebugModule()
    }
}
