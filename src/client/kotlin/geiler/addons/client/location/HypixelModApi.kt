package geiler.addons.client.location

import geiler.addons.client.config.ModConfig
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket
import net.minecraft.client.Minecraft

/** Island state backed by the official shared Hypixel Mod API. */
object HypixelModApi {
    private val state = HypixelIslandState()
    private var initialized = false

    /** The island named by the latest valid location event, or [Island.NONE] if unknown. */
    @JvmStatic
    fun currentIsland(): Island {
        if (!ModConfig.hypixelModApi()) {
            state.reset()
            return Island.NONE
        }
        return state.currentIsland()
    }

    /** Whether the official API has supplied a location event for the current connection. */
    @JvmStatic
    fun hasLocation(): Boolean {
        if (!ModConfig.hypixelModApi()) {
            state.reset()
            return false
        }
        return state.hasLocation()
    }

    /** Whether the player is on the Garden right now. */
    @JvmStatic
    fun onGarden(): Boolean = currentIsland() == Island.GARDEN

    /** Why the player is not on [wanted], or null if they are. */
    @JvmStatic
    fun reasonNotOn(wanted: Island?): String? {
        if (!ModConfig.hypixelModApi()) {
            state.reset()
            return "Island detection is off in the config"
        }
        if (state.currentIsland() == wanted) return null
        if (!state.hasLocation()) return "Waiting for the server to name the island"
        return "Not ${wanted!!.displayName()}"
    }

    /** Subscribes once; the official API sends the shared register packet when appropriate. */
    @JvmStatic
    fun init() {
        if (initialized) return

        val api = HypixelModAPI.getInstance()
        api.createHandler(ClientboundLocationPacket::class.java) { packet -> onLocation(packet) }
        api.subscribeToEventPacket(ClientboundLocationPacket::class.java)
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> state.reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> state.reset() }
        initialized = true
    }

    private fun onLocation(packet: ClientboundLocationPacket) {
        if (!ModConfig.hypixelModApi()) return

        val eventEpoch = state.epoch()
        val serverTypeName = packet.serverType.orElse(null)?.name
        val mode = packet.mode.orElse(null)
        // The API may call handlers away from the render thread; publish Minecraft-facing state there.
        Minecraft.getInstance().execute {
            state.applyLocation(eventEpoch, ModConfig.hypixelModApi(), serverTypeName, mode)
        }
    }
}

/** Thread-safe location values and stale-event guard kept independent of Minecraft for checks. */
internal class HypixelIslandState {
    @Volatile
    private var island = Island.NONE

    @Volatile
    private var located = false

    @Volatile
    private var connectionEpoch = 0L

    fun currentIsland(): Island = island

    fun hasLocation(): Boolean = located

    fun epoch(): Long = connectionEpoch

    fun reset() {
        connectionEpoch++
        island = Island.NONE
        located = false
    }

    /** Applies only the event captured for the active connection while island detection is enabled. */
    fun applyLocation(eventEpoch: Long, enabled: Boolean, serverTypeName: String?, mode: String?) {
        if (eventEpoch != connectionEpoch || !enabled) return
        located = true
        island = if (serverTypeName.equals("SKYBLOCK", ignoreCase = true)) {
            Island.fromMode(mode)
        } else {
            Island.OTHER
        }
    }
}
