package geiler.addons.client.gui

import geiler.addons.client.module.impl.SlotIdsModule
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.AbstractContainerMenu
import java.util.function.ToIntFunction

/** Draws the runtime menu slot id directly on the corresponding slot. */
class SlotIdOverlay private constructor() {
    companion object {
        private var cachedMenu: AbstractContainerMenu? = null
        private var cachedLeftPos = 0
        private var cachedTopPos = 0
        private var cachedFont: Font? = null
        private var cachedBadges: List<SlotIdBadgeLayout.Badge> = java.util.List.of()

        @JvmStatic
        fun render(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor) {
            // extractSlots runs inside extractContents' translate(leftPos, topPos); slot.x/y are already relative to it.
            renderMenu(screen.menu, graphics, Minecraft.getInstance().font, 0, 0)
        }

        /** Draws badges for every slot in the active inventory or container menu. */
        @JvmStatic
        fun renderMenu(
            menu: AbstractContainerMenu,
            graphics: GuiGraphicsExtractor,
            font: Font,
            leftPos: Int,
            topPos: Int,
        ) {
            val labelColor = SlotIdsModule.INSTANCE.labelTextColor().argb()
            for (badge in badges(menu, leftPos, topPos, font)) {
                graphics.pose().pushMatrix()
                graphics.pose().translate(badge.textX.toFloat(), badge.textY.toFloat())
                graphics.pose().scale(badge.scale, badge.scale)
                graphics.text(font, badge.label, 0, 0, labelColor)
                graphics.pose().popMatrix()
            }
        }

        private fun badges(
            menu: AbstractContainerMenu,
            leftPos: Int,
            topPos: Int,
            font: Font,
        ): List<SlotIdBadgeLayout.Badge> {
            if (menu === cachedMenu && leftPos == cachedLeftPos && topPos == cachedTopPos && font === cachedFont) {
                return cachedBadges
            }

            val positions = ArrayList<SlotIdBadgeLayout.SlotPosition>(menu.slots.size)
            for (slot in menu.slots) {
                positions.add(SlotIdBadgeLayout.SlotPosition(slot.index, slot.x, slot.y))
            }
            val result = SlotIdBadgeLayout.layout(
                positions,
                leftPos,
                topPos,
                ToIntFunction<String> { label -> font.width(label) },
            )
            cachedMenu = menu
            cachedLeftPos = leftPos
            cachedTopPos = topPos
            cachedFont = font
            cachedBadges = result
            return cachedBadges
        }
    }
}
