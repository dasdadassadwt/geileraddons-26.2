package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PlayerPresentationModule;
import geiler.addons.client.module.impl.PlayerNamesModule;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Reapplies player presentation while stored chat lines are read for rendering. */
@Mixin(GuiMessage.class)
public abstract class GuiMessagePresentationMixin {
	@Inject(method = "content()Lnet/minecraft/network/chat/Component;", at = @At("RETURN"), cancellable = true)
	private void geileraddons$applyPlayerPresentationToChat(CallbackInfoReturnable<Component> cir) {
		Component original = cir.getReturnValue();
		Component replacement = PlayerPresentationModule.INSTANCE.replaceKnownRenderedName(original);
		if (replacement != original) cir.setReturnValue(replacement);
	}

	/** Applies aliases/styles while vanilla rebuilds its cached wrapped lines. */
	@Redirect(method = "splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;",
		at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage;content:Lnet/minecraft/network/chat/Component;",
			opcode = Opcodes.GETFIELD))
	private Component geileraddons$presentContentForLineWrapping(GuiMessage message) {
		Component raw = ((GuiMessagePresentationAccessor) (Object) message).geileraddons$rawContent();
		return PlayerPresentationModule.INSTANCE.replaceKnownRenderedName(raw);
	}

	@Inject(method = "splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;", at = @At("RETURN"))
	private void geileraddons$rememberPresentedChatLines(net.minecraft.client.gui.Font font, int width,
		CallbackInfoReturnable<List<FormattedCharSequence>> cir) {
		PlayerNamesModule.INSTANCE.markPresentedSequences(cir.getReturnValue(), PlayerNamesModule.PATH_CHAT_SPLIT_OUTPUT);
	}
}
