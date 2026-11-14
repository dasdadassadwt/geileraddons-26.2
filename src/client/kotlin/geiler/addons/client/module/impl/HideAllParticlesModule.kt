package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.ParticleTypes

/** Client-only particle visibility control: everything, block breaking, or explosions. */
class HideAllParticlesModule private constructor(settings: Settings) : Module(
    "Hide Particles",
    "Hides particle effects on this client.",
    Category.VISUAL,
    false,
    settings.hideAll,
    settings.blockBreaking,
    settings.explosions,
) {
    private val hideAll = settings.hideAll
    private val blockBreaking = settings.blockBreaking
    private val explosions = settings.explosions
    private var clearExistingParticlesPending = false
    private var lastStatusTick = Long.MIN_VALUE
    private var lastClientTick = 0L
    private var cancelledAll = 0L
    private var cancelledBreaking = 0L
    private var cancelledExplosions = 0L

    override fun configName(): String = "Hide All Particles"

    override fun onEnable() {
        lastStatusTick = Long.MIN_VALUE
        clearExistingParticlesPending = true
        cancelledAll = 0L
        cancelledBreaking = 0L
        cancelledExplosions = 0L
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), true)
        writeDiagnostic(lastClientTick, "ENABLE ${flags()} particleEngine=deferred existingParticles=clear_pending")
    }

    override fun onDisable() {
        clearExistingParticlesPending = false
        writeDiagnostic(lastClientTick, "DISABLE enabled=false ${counters()}")
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
        writeDiagnostic(clientTick, "STATUS enabled=true ${flags()} ${counters()}")
    }

    /** `ParticleEngine.add`: drops every new particle while Hide All is on. */
    fun shouldCancelNewParticle(): Boolean {
        val cancel = shouldCancelNewParticle(isEnabled(), hideAll.value())
        if (cancel && cancelledAll < Long.MAX_VALUE) cancelledAll++
        return cancel
    }

    /** `ClientLevel.addDestroyBlockEffect` / `addBreakingBlockEffect`. */
    fun shouldCancelBlockBreaking(): Boolean {
        val cancel = isEnabled() && blockBreaking.value()
        if (cancel && cancelledBreaking < Long.MAX_VALUE) cancelledBreaking++
        return cancel
    }

    /** `ParticleEngine.createParticle`: explosion particle types, whether from a packet or a local explosion. */
    fun shouldCancelCreatedParticle(options: ParticleOptions?): Boolean {
        if (!isEnabled() || !explosions.value() || options == null) return false
        val cancel = isExplosionParticle(options.type)
        if (cancel && cancelledExplosions < Long.MAX_VALUE) cancelledExplosions++
        return cancel
    }

    private fun flags(): String =
        "hideAll=${hideAll.value()} blockBreaking=${blockBreaking.value()} explosions=${explosions.value()}"

    private fun counters(): String =
        "cancelledAll=$cancelledAll cancelledBreaking=$cancelledBreaking cancelledExplosions=$cancelledExplosions"

    private fun writeDiagnostic(clientTick: Long, line: String) {
        GeilerAddonsLog.write(Category.VISUAL, name(), clientTick, line.take(MAX_DIAGNOSTIC_LINE_LENGTH))
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Hide Particles")
        val hideAll = spec.toggle("hideAll")
        val blockBreaking = spec.toggle("blockBreaking")
        val explosions = spec.toggle("explosions")
    }

    companion object {
        @JvmField
        val INSTANCE = HideAllParticlesModule(Settings())

        @JvmStatic
        fun clearExistingParticlesOnEnable(clearAction: Runnable?): Boolean {
            if (clearAction == null) return false
            clearAction.run()
            return true
        }

        @JvmStatic
        fun shouldCancelNewParticle(moduleEnabled: Boolean, hideAll: Boolean): Boolean = moduleEnabled && hideAll

        /** The particle types NoFrills and vanilla use for explosions and wind-charge style bursts. */
        @JvmStatic
        fun isExplosionParticle(type: ParticleType<*>): Boolean =
            type === ParticleTypes.EXPLOSION || type === ParticleTypes.EXPLOSION_EMITTER ||
                type === ParticleTypes.GUST || type === ParticleTypes.GUST_EMITTER_LARGE

        private const val DIAGNOSTIC_STATUS_INTERVAL_TICKS = 100L
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 200
    }
}
