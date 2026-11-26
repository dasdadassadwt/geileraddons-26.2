package geiler.addons.client.chat

import geiler.addons.client.config.ModConfig
import geiler.addons.client.dungeon.ClientJsonFile
import geiler.addons.client.dungeon.DungeonGuideStore
import geiler.addons.client.inventory.PlayerInventoryControls
import geiler.addons.client.macros.area.PaintStore
import geiler.addons.client.module.impl.CollectionTrackerModule
import geiler.addons.client.module.impl.ChatModule
import geiler.addons.client.module.impl.PestTrackerModule
import geiler.addons.client.module.impl.SlayerTrackerModule
import geiler.addons.client.module.impl.StorageOverlayModule
import geiler.addons.client.scoreboard.ScoreboardBackend
import net.minecraft.CrashReport
import net.minecraft.ReportedException
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Intercepts configured direct messages before they reach chat history.
 *
 * The caller must return `false` from the Fabric receive event when an `intercept*` method returns
 * true. Both events fire before `ChatComponent.addMessage` and before vanilla's chat log, so the
 * message never reaches the HUD, chat history, or the observers that run later in
 * `GeilerAddonsClient`.
 */
object ChatHelper {
    /** Set once; a burst of matching messages must not schedule a second report. */
    private val armed = AtomicBoolean(false)

    /**
     * The helper's own switch. It is intentionally independent of the Chat module's master switch,
     * so turning off the visual Chat module does not turn off direct-message handling.
     */
    @JvmStatic
    fun isEnabled(): Boolean = ChatModule.INSTANCE.chatHelper().value()

    /** Action bars are not direct messages. Returns true only when the caller must swallow it. */
    @JvmStatic
    fun interceptSystemChat(message: Component?, overlay: Boolean): Boolean {
        if (overlay || !isEnabled()) return false
        val localName = Minecraft.getInstance().player?.gameProfile?.name ?: return false
        return intercept(ChatHelperRules.incomingSystemChat(message), localName)
    }

    /** Player-chat messages (vanilla `/msg` decorations); public chat cannot match this path. */
    @JvmStatic
    fun interceptPlayerChat(message: Component?): Boolean {
        if (!isEnabled()) return false
        val localName = Minecraft.getInstance().player?.gameProfile?.name ?: return false
        return intercept(ChatHelperRules.incomingPlayerChat(message), localName)
    }

    private fun intercept(incoming: ChatHelperRules.Incoming?, localName: String): Boolean {
        val dm = incoming ?: return false
        if (ChatHelperRules.isNonPlayerSender(dm.sender)) return false
        if (ChatHelperRules.isLocalSender(dm.sender, localName)) return false
        if (!ChatHelperRules.matchesTrigger(dm.body, ChatModule.INSTANCE.chatHelperTrigger().value())) {
            return false
        }
        // Swallow duplicates even when the first match already armed the shutdown.
        if (armed.compareAndSet(false, true)) scheduleShutdown()
        return true
    }

    private fun scheduleShutdown() {
        Minecraft.getInstance().execute {
            flushBeforeShutdown()
            shutdownOnClientThread()
        }
    }

    /**
     * Flushes the same data the normal client shutdown flushes, plus the stores that expose an
     * explicit client-thread flush. Failures are silent so the shutdown still proceeds.
     */
    private fun flushBeforeShutdown() {
        runCatching { ModConfig.flushNow() }
        runCatching { DungeonGuideStore.flush() }
        runCatching { ScoreboardBackend.flush() }
        runCatching { StorageOverlayModule.INSTANCE.flush() }
        runCatching { PestTrackerModule.INSTANCE.persistFinalTotalsSnapshot() }
        runCatching { SlayerTrackerModule.INSTANCE.persistFinalTotalsSnapshot() }
        runCatching { CollectionTrackerModule.INSTANCE.persistNow() }
        runCatching { PlayerInventoryControls.INSTANCE.flush() }
        runCatching { PaintStore.saveAll() }
        runCatching { ClientJsonFile.flush() }
    }

    /** Raises a report that vanilla's task loop and packet handler treat as non-recoverable. */
    private fun shutdownOnClientThread() {
        val cause = OutOfMemoryError()
        cause.stackTrace = arrayOf<StackTraceElement>()
        val reported = ReportedException(CrashReport("Ticking player", cause))
        reported.stackTrace = arrayOf<StackTraceElement>()
        throw reported
    }
}
