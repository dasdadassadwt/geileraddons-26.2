package geiler.addons.client.macros.sim

import geiler.addons.client.macros.module.EtherwarpSimModule
import geiler.addons.client.macros.module.JumpBoostModule
import geiler.addons.client.macros.module.OneTapModule
import geiler.addons.client.macros.module.SpeedSimModule

/** The per-tick glue between the Sim modules and the server-thread [SimRuntime]. */
object SimModules {
    /** Call once per client tick. */
    @JvmStatic
    fun tick() {
        SimRuntime.publish(
            SpeedSimModule.INSTANCE.isEnabled(),
            SpeedSimModule.INSTANCE.speedStat(),
            JumpBoostModule.INSTANCE.isEnabled(),
            JumpBoostModule.INSTANCE.level(),
            OneTapModule.INSTANCE.isEnabled(),
        )
        SpeedSimModule.INSTANCE.tick()
        JumpBoostModule.INSTANCE.tick()
        OneTapModule.INSTANCE.tick()
        EtherwarpSimModule.INSTANCE.tick()
    }
}
