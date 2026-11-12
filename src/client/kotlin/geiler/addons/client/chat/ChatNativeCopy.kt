package geiler.addons.client.chat

import geiler.addons.client.mixin.ChatComponentPresentationAccessor
import geiler.addons.client.module.impl.ChatChannelsModule
import geiler.addons.client.module.impl.ChatModule
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.ChatVisiblity
import kotlin.math.ceil
import kotlin.math.floor

/** Hit-tests vanilla's current chat rows and copies the complete parent message. */
object ChatNativeCopy {
    @JvmStatic
    fun copyMessageAt(mouseX: Double, mouseY: Double): Boolean {
        val module = ChatModule.INSTANCE
        val mc = Minecraft.getInstance()
        if (!module.isEnabled() || !module.copyOnRightClick() || mc.gui == null ||
            mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN ||
            mc.gui.screen() != null && mc.gui.screen() !is ChatScreen
        ) return false

        val chat = mc.gui.hud.getChat()
        ChatChannelsBackend.refreshVisibleMessageFilter(chat)
        val access = chat as ChatComponentPresentationAccessor
        val scale = safeScale(access.`geileraddons$scale`())
        val width = access.`geileraddons$width`()
        val screenWidth = mc.window.guiScaledWidth
        if (mouseX < 0.0 || mouseX > minOf(screenWidth.toDouble(), width + 8.0 * scale)) return false

        val screenHeight = mc.window.guiScaledHeight
        val baseline = floor((screenHeight - 40.0) / scale).toInt()
        val logicalY = mouseY / scale
        if (logicalY >= baseline) return false

        val spacing = mc.options.chatLineSpacing().get()
        val lineHeight = (9.0 * (1.0 + spacing)).toInt()
        if (lineHeight <= 0) return false
        val rowFromBottom = ceil((baseline - logicalY) / lineHeight).toInt() - 1
        if (rowFromBottom < 0) return false

        val lines: List<GuiMessage.Line> = access.`geileraddons$trimmedMessages`()
        val scroll = maxOf(0, access.`geileraddons$scrollbarPosition`())
        val visibleRows = maxOf(0, minOf(lines.size - scroll, access.`geileraddons$linesPerPage`()))
        if (rowFromBottom >= visibleRows) return false
        val lineIndex = ChatNativeCopyRules.lineIndexFromBottom(lines.size, scroll, rowFromBottom)
        if (lineIndex < 0) return false
        val message = lines[lineIndex].parent()
        if (!chat.isChatFocused() && mc.gui.hud.getGuiTicks() - message.addedTime() >= 200) return false

        mc.keyboardHandler.setClipboard(message.content().getString())
        val channelId = if (ChatChannelsModule.INSTANCE.isEnabled()) {
            ChatChannelsBackend.selectedChannel().id()
        } else {
            ChatChannel.ALL.id()
        }
        val feedback = Component.literal("[GeilerAddons] Copied chat message.")
        ChatChannelsBackend.routeLocalMessage(feedback, channelId)
        mc.gui.hud.getChat().addClientSystemMessage(feedback)
        module.logMessageCopied(channelId)
        return true
    }

    private fun safeScale(scale: Double): Double =
        if (scale.isFinite() && scale > 0.0) scale else 1.0
}
