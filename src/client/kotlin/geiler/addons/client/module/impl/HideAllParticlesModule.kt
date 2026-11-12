package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import net.minecraft.client.Minecraft

/** Single-switch client-only particle visibility control. */
class HideAllParticlesModule private constructor() : Module(
    "Hide Particles",
    "Hides particle effects on this client.",
    Category.VISUAL,
    false,
) {
    private var clearExistingParticlesPending = false
    private var lastStatusTick = Long.MIN_VALUE
    private var lastClientTick = 0L

    override fun configName(): String = "Hide All Particles"

    override fun onEnable() {
        lastStatusTick = Long.MIN_VALUE
        clearExistingParticlesPending = true
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), true)
        writeDiagnostic(lastClientTick, "ENABLE particleEngine=deferred existingParticles=clear_pending")
    }

    override fun onDisable() {
        clearExistingParticlesPending = false
        writeDiagnostic(lastClientTick, "DISABLE enabled=false")
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), false)
        lastStatusTick = Long.MIN_VALUE
    }

    /** Called by the shared client tick registration to emit a bounded status heartbeat. */
    fun tick(clientTick: Long) {
        lastClientTick = clientTick
        if (!isEnabled()) return
        if (clearExistingParticlesPending) {
            val particleEngine = Minecraft.getInstance().particleEngine
            val clearInvoked = clearExistingParticlesOnEnable(Runnable { particleEngine.clearParticles() })
            clearExistingParticlesPending = false
            val outcome = if (clearInvoked) "clear_invoked" else "clear_skipped"
            writeDiagnostic(clientTick, "ENABLE_APPLIED particleEngine=available existingParticles=$outcome")
        }
        if (lastStatusTick != Long.MIN_VALUE && clientTick - lastStatusTick < DIAGNOSTIC_STATUS_INTERVAL_TICKS) return

        lastStatusTick = clientTick
        val status = "STATUS enabled=true particleEngine=available newParticleGate=module_enabled"
        writeDiagnostic(clientTick, status)
    }

    private fun writeDiagnostic(clientTick: Long, line: String) {
        GeilerAddonsLog.write(Category.VISUAL, name(), clientTick, line.take(MAX_DIAGNOSTIC_LINE_LENGTH))
    }

    companion object {
        @JvmField
        val INSTANCE = HideAllParticlesModule()

        @JvmStatic
        fun clearExistingParticlesOnEnable(clearAction: Runnable?): Boolean {
            if (clearAction == null) return false
            clearAction.run()
            return true
        }

        @JvmStatic
        fun shouldCancelNewParticle(moduleEnabled: Boolean): Boolean = moduleEnabled

        private const val DIAGNOSTIC_STATUS_INTERVAL_TICKS = 100L
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 160
    }
}
