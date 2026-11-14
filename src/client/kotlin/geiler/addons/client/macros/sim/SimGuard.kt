package geiler.addons.client.macros.sim

import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.server.MinecraftServer

/**
 * The singleplayer-only rule of the Sim features. A world counts only while this client runs the integrated server and
 * it has not been opened to LAN: once other players can join, it is no longer singleplayer.
 *
 * Every effect is applied to the integrated server's own player and nowhere else, so a multiplayer connection has no
 * code path that could apply one; this guard is checked again on every pass as a second line of defence.
 */
object SimGuard {
    @JvmStatic
    fun allows(hasSingleplayerServer: Boolean, publishedToLan: Boolean): Boolean = hasSingleplayerServer && !publishedToLan

    /** The client's view; call on the client thread. */
    @JvmStatic
    fun allowedNow(): Boolean {
        val client = Minecraft.getInstance()
        val server = client.singleplayerServer
        return allows(client.hasSingleplayerServer() && server != null, server != null && server.isPublished)
    }

    /** The server's own view; call on the server thread. A dedicated or remote server can never pass. */
    @JvmStatic
    fun allowedFor(server: MinecraftServer): Boolean = server is IntegratedServer && allows(true, server.isPublished)
}
