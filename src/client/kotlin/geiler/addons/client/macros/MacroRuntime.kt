package geiler.addons.client.macros

import geiler.addons.client.hud.HudManager
import geiler.addons.client.macros.config.AreaKillerConfig
import geiler.addons.client.macros.input.MouseRelease
import geiler.addons.client.macros.killer.AreaKillerMacro
import geiler.addons.client.macros.killer.AreaOverlay
import geiler.addons.client.macros.module.AreaKillerModule
import geiler.addons.client.macros.module.PathfinderModule
import geiler.addons.client.macros.navigation.PathfindMacro
import geiler.addons.client.macros.pathing.WorldWatch
import geiler.addons.client.module.Category
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents

/**
 * What the Pathfinder and the Area Killer share: the one configuration both macros read, and the wiring that has to
 * happen once while the mod starts. The Click GUI modules copy their settings into [config] every tick.
 */
object MacroRuntime {
    @JvmField
    val config = AreaKillerConfig()
    private var started = false

    /** Call once from the client initializer, after the modules are registered and before the config is loaded. */
    @JvmStatic
    fun init() {
        if (started) return
        started = true
        AreaKillerMacro.init(config)
        PathfindMacro.init(config)
        AreaOverlay.init(config)
        MouseRelease.init({ config.ungrabMouse }, { PathfindMacro.isRunning() || AreaKillerMacro.isRunning() })

        // A chunk that comes in or goes away changes what the planner may have read at its edges.
        ClientChunkEvents.CHUNK_LOAD.register { level, chunk -> WorldWatch.chunkChanged(level, chunk.pos.x, chunk.pos.z) }
        ClientChunkEvents.CHUNK_UNLOAD.register { level, chunk -> WorldWatch.chunkChanged(level, chunk.pos.x, chunk.pos.z) }

        HudManager.register(AreaKillerModule.INSTANCE, Category.AREA_KILLER, 0.01f, 0.33f)
    }

    /** Once per client tick: the modules copy their settings into [config] and follow their macro's running state. */
    @JvmStatic
    fun tick() {
        PathfinderModule.INSTANCE.tick()
        AreaKillerModule.INSTANCE.tick()
    }
}
