package geiler.addons.client.module.impl

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/** Extracts the validated Leap surface before vanilla HUD elements and suppresses its later screen pass. */
class SpiritLeapGuiOrdering private constructor() {
    class EarlyRenderMarker {
        private var screen: Any? = null

        fun beginGuiExtraction() {
            screen = null
        }

        fun mark(screen: Any?) {
            this.screen = screen
        }

        fun consume(screen: Any?): Boolean {
            if (this.screen !== screen) return false
            this.screen = null
            return true
        }
    }

    companion object {
        private val EARLY_RENDER = EarlyRenderMarker()

        @JvmStatic
        fun beginGuiExtraction() {
            EARLY_RENDER.beginGuiExtraction()
        }

        @JvmStatic
        fun renderBeforeHud(graphics: GuiGraphicsExtractor) {
            val client = Minecraft.getInstance()
            val screen = client.gui.screen()
            if (!client.isSameThread() || client.gui.overlay() != null || screen !is AbstractContainerScreen<*> ||
                SpiritLeapSurfaceOwner.shouldSuppressPostLeapReplacement(screen) ||
                ExperimentSolverModule.INSTANCE.ownsScreen(screen)
            ) return

            val mouseX = client.mouseHandler.getScaledXPos(client.window).toInt()
            val mouseY = client.mouseHandler.getScaledYPos(client.window).toInt()
            if (DungeonOdinAssistModule.INSTANCE.renderSpiritLeapScreen(
                    graphics, client.font, graphics.guiWidth(), graphics.guiHeight(), screen, mouseX, mouseY,
                )
            ) {
                SpiritLeapSurfaceOwner.acquireAfterValidatedRender(screen)
                EARLY_RENDER.mark(screen)
            }
        }

        /** Returns true once only when this exact screen was extracted before vanilla HUD rendering. */
        @JvmStatic
        fun consumeEarlyRender(screen: Screen?): Boolean = EARLY_RENDER.consume(screen)
    }
}
