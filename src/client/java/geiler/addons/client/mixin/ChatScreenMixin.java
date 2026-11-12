package geiler.addons.client.mixin;

import geiler.addons.client.chat.ChatChannelTabs;
import geiler.addons.client.chat.ChatChannelsBackend;
import geiler.addons.client.module.impl.ChatChannelsModule;
import geiler.addons.client.module.impl.ChatModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Clickable, translucent channel tabs attached to vanilla chat input. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@Shadow protected EditBox input;

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void geileraddons$drawChannelTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
		float partialTick, CallbackInfo ci) {
		if (!ChatChannelsModule.INSTANCE.isEnabled() || input == null) return;
		Font font = Minecraft.getInstance().font;
		int x = input.getX();
		int y = input.getY() - 13;
		for (ChatChannelTabs.Tab tab : ChatChannelTabs.tabs()) {
			int width = Math.max(28, font.width(tab.label()) + 10);
			boolean selected = ChatChannelTabs.selected() == tab;
			graphics.fill(x, y, x + width, y + 11, selected ? 0xB05B83A6 : 0x70303030);
			graphics.text(font, tab.label(), x + (width - font.width(tab.label())) / 2, y + 1,
				selected ? 0xFFFFFFFF : 0xFFD0D0D0);
			x += width + 3;
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void geileraddons$selectChannelTab(MouseButtonEvent event, boolean doubleClick,
		CallbackInfoReturnable<Boolean> cir) {
		if (event.button() != 0 || !ChatChannelsModule.INSTANCE.isEnabled() || input == null) return;
		Font font = Minecraft.getInstance().font;
		int x = input.getX();
		int y = input.getY() - 13;
		int mouseX = (int) event.x();
		int mouseY = (int) event.y();
		if (mouseY < y || mouseY >= y + 11) return;
		for (ChatChannelTabs.Tab tab : ChatChannelTabs.tabs()) {
			int width = Math.max(28, font.width(tab.label()) + 10);
			if (mouseX >= x && mouseX < x + width) {
				if (ChatChannelTabs.select(tab)) {
					ChatChannelsBackend.selectChannel(tab.channelId());
					ChatModule.INSTANCE.logTabSelection(tab.channelId());
				}
				cir.setReturnValue(true);
				return;
			}
			x += width + 3;
		}
	}

	@ModifyVariable(method = "handleChatInput", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private String geileraddons$routeSelectedChannelMessage(String input) {
		if (!ChatChannelsModule.INSTANCE.isEnabled()) return input;
		ChatChannelTabs.Tab selected = ChatChannelTabs.selected();
		boolean command = input != null && input.stripLeading().startsWith("/");
		String routed = ChatChannelTabs.routeInput(selected, input);
		ChatModule.INSTANCE.logOutgoingRouting(selected.channelId(), command ? null : selected.messagePrefix(), command);
		return routed;
	}
}
