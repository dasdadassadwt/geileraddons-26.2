package geiler.addons.client.mixin;

import geiler.addons.client.chat.ChatChannelsBackend;
import geiler.addons.client.chat.ChatNativeSizeRules;
import geiler.addons.client.module.impl.ChatModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the native chat renderer, dimensions, and per-channel filtering under one chat component. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentChannelsMixin {
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
		at = @At("HEAD"))
	private void geileraddons$beginNativeMessageInsertion(Component component, MessageSignature signature,
		GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
		ChatChannelsBackend.beginNativeMessageInsertion((ChatComponent) (Object) this);
	}

	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
		at = @At("HEAD"))
	private void geileraddons$captureChannelMessage(Component component, MessageSignature signature,
		GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
		// Keep the exact preformatted Component so the channel view preserves its text runs and styles.
		ChatChannelsBackend.onIncoming(component, signature, source);
	}

	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
		at = @At("TAIL"))
	private void geileraddons$finishNativeMessageInsertion(Component component, MessageSignature signature,
		GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
		ChatChannelsBackend.finishNativeMessageInsertion((ChatComponent) (Object) this);
	}

	@Inject(method = "deleteMessage(Lnet/minecraft/network/chat/MessageSignature;)V", at = @At("HEAD"))
	private void geileraddons$removeDeletedChannelMessage(MessageSignature signature, CallbackInfo ci) {
		ChatChannelsBackend.onDelete(signature);
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
		at = @At("HEAD"))
	private void geileraddons$refreshNativeChannelFilter(GuiGraphicsExtractor graphics,
		Font font, int x, int y, int width,
		ChatComponent.DisplayMode displayMode, boolean focused, CallbackInfo ci) {
		ChatChannelsBackend.refreshVisibleMessageFilter((ChatComponent) (Object) this);
	}

	@Inject(method = "getWidth()I", at = @At("RETURN"), cancellable = true)
	private void geileraddons$addNativeChatWidth(CallbackInfoReturnable<Integer> cir) {
		ChatModule module = ChatModule.INSTANCE;
		if (!module.isEnabled()) return;
		Minecraft mc = Minecraft.getInstance();
		double scale = ((ChatComponentPresentationAccessor) (Object) this).geileraddons$scale();
		int availableWidth = ChatNativeSizeRules.availableWidth(mc.getWindow().getGuiScaledWidth(), scale);
		cir.setReturnValue(ChatNativeSizeRules.effectiveWidth(cir.getReturnValue(),
			module.extraWidth(), availableWidth));
	}

	@Inject(method = "getHeight()I", at = @At("RETURN"), cancellable = true)
	private void geileraddons$addNativeChatHeight(CallbackInfoReturnable<Integer> cir) {
		ChatModule module = ChatModule.INSTANCE;
		if (!module.isEnabled()) return;
		Minecraft mc = Minecraft.getInstance();
		ChatComponent chat = (ChatComponent) (Object) this;
		boolean focused = chat.isChatFocused();
		int extra = focused ? module.extraFocusedHeight() : module.extraUnfocusedHeight();
		double scale = ((ChatComponentPresentationAccessor) (Object) this).geileraddons$scale();
		int availableHeight = Math.max(1, mc.getWindow().getGuiScaledHeight() - 40);
		cir.setReturnValue(ChatNativeSizeRules.effectiveHeight(cir.getReturnValue(), extra,
			availableHeight, scale));
	}
}
