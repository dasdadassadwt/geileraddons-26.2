package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.helpers.SpiritLeapSelection
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/** Holds a validated Leap container surface until that screen closes or the feature is disabled. */
class SpiritLeapSurfaceOwner private constructor() {
    companion object {
        const val POST_LEAP_TRANSITION_NANOS = SpiritLeapTransitionGuard.WINDOW_NANOS
        private var owner: AbstractContainerScreen<*>? = null
        private val transitionGuard = SpiritLeapTransitionGuard()
        private var dispatchedClickDepth = 0

        init {
            ClientTickEvents.END_CLIENT_TICK.register { client ->
                if (client.isSameThread() && transitionGuard.expireIfStale(System.nanoTime())) {
                    logClearedTransition("expired")
                }
            }
        }

        @JvmStatic
        fun shouldOwnSurface(contextValid: Boolean, sameScreenOwner: Boolean, validatedLeapSurface: Boolean) =
            contextValid && (sameScreenOwner || validatedLeapSurface)

        @JvmStatic
        fun shouldRetainSurface(sameScreenOwner: Boolean, playerPresent: Boolean, featureEnabled: Boolean) =
            sameScreenOwner && playerPresent && featureEnabled

        @JvmStatic
        fun acquireAfterValidatedRender(screen: AbstractContainerScreen<*>?): Boolean {
            if (!isValidContext(screen)) return false
            if (!shouldOwnSurface(true, owner === screen, true)) return false
            if (transitionGuard.clearForValidatedLeapScreen(screen)) logClearedTransition("validated-leap-screen")
            if (owner !== screen) {
                owner = screen
                SpiritLeapMenuModule.recordEvent(currentClientTick(), "MENU_SURFACE owner=acquired source=validated-head")
            }
            return true
        }

        @JvmStatic
        fun owns(screen: AbstractContainerScreen<*>?): Boolean {
            val client = Minecraft.getInstance()
            if (!client.isSameThread()) return false
            if (screen == null || owner !== screen) return false
            val enabled = SpiritLeapMenuModule.INSTANCE.isEnabled() && SpiritLeapMenuModule.showCardsAndKeys.value()
            val currentScreen = client.gui.screen()
            if (!shouldRetainSurface(currentScreen === screen, client.player != null, enabled)) {
                val reason = if (currentScreen !== screen) "screen-replaced"
                else if (client.player == null) "player-detached" else "feature-disabled"
                release(screen, reason)
                return false
            }
            return true
        }

        @JvmStatic
        fun release(screen: AbstractContainerScreen<*>?, reason: String?) {
            if (!Minecraft.getInstance().isSameThread()) return
            if (transitionGuard.clearForRemovedScreen(screen)) logClearedTransition("suppressed-screen-closed")
            if (screen == null || owner !== screen) return
            owner = null
            SpiritLeapMenuModule.recordEvent(
                currentClientTick(),
                "MENU_SURFACE owner=released reason=${SpiritLeapMenuModule.boundedDiagnosticText(reason, 32)}",
            )
            SpiritLeapPovRenderer.INSTANCE.close()
        }

        @JvmStatic
        fun diagnosticState(screen: AbstractContainerScreen<*>?): String {
            val client = Minecraft.getInstance()
            if (screen != null && client.gui.screen() === screen &&
                transitionGuard.isSuppressing(screen, featureEnabled(), System.nanoTime())
            ) return "transition-suppressed"
            return if (owns(screen)) "latched" else "none"
        }

        /** Arms a screen-transition guard only after a validated Leap click reached vanilla dispatch. */
        @JvmStatic
        fun recordLeapDispatch(screen: AbstractContainerScreen<*>?) {
            val client = Minecraft.getInstance()
            if (!client.isSameThread() || screen == null || client.player == null || !featureEnabled() ||
                !SpiritLeapSelection.isLeapMenu(screen.title.string)
            ) return
            if (transitionGuard.arm(screen, System.nanoTime())) {
                SpiritLeapMenuModule.recordEvent(
                    currentClientTick(),
                    "MENU_TRANSITION armed=post-leap windowMs=${POST_LEAP_TRANSITION_NANOS / 1_000_000L}",
                )
            }
        }

        /** Cancels only the first replacement Leap container after dispatch. */
        @JvmStatic
        fun shouldSuppressPostLeapReplacement(screen: Screen?): Boolean {
            val client = Minecraft.getInstance()
            if (!client.isSameThread() || screen == null || client.gui.screen() !== screen) return false
            val leapContainer = screen is AbstractContainerScreen<*> && SpiritLeapSelection.isLeapMenu(screen.title.string)
            val decision = transitionGuard.evaluate(screen, featureEnabled(), leapContainer, System.nanoTime())
            if (decision == SpiritLeapTransitionGuard.Decision.SUPPRESS_FIRST_REPLACEMENT) {
                SpiritLeapMenuModule.recordEvent(currentClientTick(),
                    "MENU_TRANSITION suppressed=1 reason=post-dispatch-leap-replacement")
                return true
            }
            if (decision == SpiritLeapTransitionGuard.Decision.SUPPRESS_SAME_REPLACEMENT) return true
            val clearReason = when (decision) {
                SpiritLeapTransitionGuard.Decision.CLEARED_DISABLED -> "feature-disabled"
                SpiritLeapTransitionGuard.Decision.CLEARED_EXPIRED -> "expired"
                SpiritLeapTransitionGuard.Decision.CLEARED_UNRELATED -> "first-replacement-not-leap"
                SpiritLeapTransitionGuard.Decision.CLEARED_SCREEN_CHANGED -> "replacement-screen-changed"
                else -> null
            }
            if (clearReason != null) logClearedTransition(clearReason)
            return false
        }

        @JvmStatic fun isDispatchingClick() = dispatchedClickDepth > 0

        @JvmStatic
        fun runDispatchedClick(dispatch: Runnable?) {
            if (dispatch == null) return
            val client: Minecraft? = Minecraft.getInstance()
            val leapSource = (client?.gui?.screen() as? AbstractContainerScreen<*>)
                ?.takeIf { isValidContext(it) }
            dispatchedClickDepth++
            try {
                dispatch.run()
            } finally {
                dispatchedClickDepth--
            }
            if (leapSource != null) recordLeapDispatch(leapSource)
        }

        private fun isValidContext(screen: AbstractContainerScreen<*>?): Boolean {
            val client = Minecraft.getInstance()
            return screen != null && client.isSameThread() && client.gui.screen() === screen && client.player != null &&
                SpiritLeapMenuModule.INSTANCE.isEnabled() && SpiritLeapMenuModule.showCardsAndKeys.value() &&
                geiler.addons.client.dungeon.DungeonContextTracker.inDungeon() &&
                SpiritLeapSelection.isLeapMenu(screen.title.string)
        }

        private fun featureEnabled() = SpiritLeapMenuModule.INSTANCE.isEnabled() &&
            SpiritLeapMenuModule.showCardsAndKeys.value()

        private fun logClearedTransition(reason: String) {
            SpiritLeapMenuModule.recordEvent(
                currentClientTick(),
                "MENU_TRANSITION cleared reason=${SpiritLeapMenuModule.boundedDiagnosticText(reason, 32)}",
            )
        }

        private fun currentClientTick(): Long {
            val client = Minecraft.getInstance()
            return client.level?.gameTime ?: 0L
        }
    }
}
