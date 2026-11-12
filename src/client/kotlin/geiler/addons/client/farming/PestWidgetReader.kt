package geiler.addons.client.farming

import geiler.addons.GeilerAddons
import geiler.addons.client.access.PlayerTabOverlayAccess
import geiler.addons.client.presentation.PlayerTabNameSource
import java.util.ArrayList
import java.util.Optional
import java.util.function.Supplier
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.PlayerTabOverlay
import net.minecraft.client.multiplayer.PlayerInfo

/**
 * Reads the Pests widget from the client-thread tab list. The default source is ordered the way
 * vanilla draws it; the packet listener's player collection has no ordering guarantee.
 */
class PestWidgetReader {
    private val lines: Supplier<List<String>>?

    constructor() : this(Supplier { PestWidgetReader.currentTabList() })

    /** Test seam: the parser's ordering contract is the caller's, so the lines are injectable. */
    constructor(lines: Supplier<List<String>>?) {
        this.lines = lines
    }

    /**
     * Reads the widget once.
     *
     * Empty means "unknown" when the widget is absent, unreadable, or was not fully rendered; it
     * must not be treated as a clear observation.
     */
    fun read(): Optional<Set<Int>> = TabWidgetText.parse(lines!!.get())

    /** Reads the cooldown state from the same ordered tab-list widget used for plot status. */
    fun readCooldown(): Optional<PestCooldownText.Observation> = PestCooldownText.parse(lines!!.get())

    companion object {
        private var warnedAboutOrdering = false

        /** Reads the vanilla-rendered order; callers must stay on the client thread. */
        @JvmStatic
        fun currentTabList(): List<String> {
            val minecraft = Minecraft.getInstance()
            val player = minecraft.player ?: return emptyList()
            if (player.connection == null) return emptyList()
            val gui = minecraft.gui ?: return emptyList()
            val overlay = gui.hud.getTabList() ?: return emptyList()
            val listed = orderedPlayers(overlay)
            if (listed.isEmpty()) return emptyList()

            val lines = ArrayList<String>(listed.size)
            for (info in listed) lines.add(PlayerTabNameSource.rawLine(info))
            return lines
        }

        /**
         * Use the order that vanilla actually draws. If the invoker is unavailable, return no rows
         * rather than substituting the unordered packet collection and applying a guessed plot set.
         */
        private fun orderedPlayers(overlay: PlayerTabOverlay): List<PlayerInfo> {
            try {
                val ordered: List<PlayerInfo>? =
                    (overlay as PlayerTabOverlayAccess).`geileraddons$getPlayerInfos`()
                if (ordered != null) return ordered
            } catch (failure: RuntimeException) {
                warnOrderingUnavailable(failure)
            } catch (failure: LinkageError) {
                warnOrderingUnavailable(failure)
            }
            return emptyList()
        }

        private fun warnOrderingUnavailable(failure: Throwable) {
            if (warnedAboutOrdering) return
            warnedAboutOrdering = true
            GeilerAddons.LOGGER.warn(
                "[Infested plot] Tab list ordering is unavailable; the Pests widget cannot be read",
                failure,
            )
        }
    }
}
