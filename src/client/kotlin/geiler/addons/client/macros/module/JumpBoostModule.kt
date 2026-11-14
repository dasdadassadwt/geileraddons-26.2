package geiler.addons.client.macros.module

import geiler.addons.client.macros.sim.SimGuard
import geiler.addons.client.macros.sim.SimMapping
import geiler.addons.client.macros.sim.SimRuntime
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.impl.ModuleDiagnostics

/** The real Jump Boost effect, at the selected level, with no time limit, in singleplayer only. */
class JumpBoostModule private constructor(private val level: NumberSetting) : Module(
    "Jump Boost",
    "Singleplayer only. Gives you the real Jump Boost effect at the chosen level (1 to 255) with no time limit " +
        "while this is on. It is removed when you switch this off or leave singleplayer.",
    Category.SIM,
    false,
    level,
) {
    private val diagnostics = ModuleDiagnostics(Category.SIM, { name() })

    /** The level the player sees, 1 to 255; the effect's own amplifier is one lower. */
    fun level(): Int = level.intValue()

    override fun onEnable() = diagnostics.enable(
        "level=${level.intValue()} amplifier=${SimMapping.jumpAmplifier(level.intValue())} singleplayerOnly=true",
    )

    override fun onDisable() = diagnostics.disable()

    override fun isActive(): Boolean = isEnabled() && SimGuard.allowedNow()

    override fun inactiveReason(): String? = if (isEnabled() && !SimGuard.allowedNow()) "Singleplayer only" else null

    /** Called every client tick: relays what the server thread did and writes the status heartbeat. */
    fun tick() {
        if (isEnabled()) diagnostics.advance()
        while (true) {
            val line = SimRuntime.pollEvent(SimRuntime.Feature.JUMP) ?: break
            if (isEnabled()) diagnostics.event(line) else diagnostics.record("EVENT $line")
        }
        if (!isEnabled()) return
        diagnostics.status { suppressed ->
            "enabled=true singleplayerOnly=${SimGuard.allowedNow()} applied=${SimRuntime.jumpApplied} " +
                "level=${level.intValue()} suppressedEvents=$suppressed"
        }
    }

    companion object {
        @JvmField
        val INSTANCE = JumpBoostModule(
            NumberSetting(
                "Level", SimMapping.MIN_JUMP_LEVEL.toFloat(), SimMapping.MAX_JUMP_LEVEL.toFloat(),
                SimMapping.DEFAULT_JUMP_LEVEL.toFloat(), true,
            ),
        )
    }
}
