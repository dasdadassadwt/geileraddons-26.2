package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.DungeonOdinAssistModule;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Recolors the recognized correct Quiz option marker without replacing the chat message. */
@Mixin(ChatComponent.class)
public abstract class DungeonQuizChatStyleMixin {
	@ModifyVariable(
		method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0
	)
	private Component geileraddons$highlightCorrectQuizOption(Component component) {
		return DungeonOdinAssistModule.INSTANCE.styleQuizChatOption(component);
	}
}
