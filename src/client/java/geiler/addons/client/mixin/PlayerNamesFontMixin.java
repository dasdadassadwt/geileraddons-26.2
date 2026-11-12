package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PlayerNamesModule;
import geiler.addons.client.presentation.PlayerNameFontTransform;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Applies local player-name presentation at Minecraft's shared text preparation and measurement boundary. */
@Mixin(Font.class)
public abstract class PlayerNamesFontMixin {
	@Inject(method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
		at = @At("HEAD"), cancellable = true)
	private void geileraddons$presentPreparedString(String text, float x, float y, int color,
		boolean dropShadow, int backgroundColor, CallbackInfoReturnable<Font.PreparedText> cir) {
		FormattedCharSequence transformed = PlayerNameFontTransform.transformLegacyString(
			text, PlayerNamesModule.PATH_PREPARE_STRING);
		if (transformed != null) {
			Font font = (Font) (Object) this;
			cir.setReturnValue(font.prepareText(transformed, x, y, color, dropShadow, false, backgroundColor));
		}
	}

	@ModifyVariable(method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private FormattedCharSequence geileraddons$presentPreparedSequence(FormattedCharSequence text) {
		return PlayerNamesModule.INSTANCE.replaceFormattedSequence(text, PlayerNamesModule.PATH_PREPARE_SEQUENCE);
	}

	@ModifyVariable(method = "prepare8xTextOutline(Lnet/minecraft/util/FormattedCharSequence;FFI)Lnet/minecraft/client/gui/Font$PreparedText;",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private FormattedCharSequence geileraddons$presentDrawOutline(FormattedCharSequence text) {
		return PlayerNamesModule.INSTANCE.replaceFormattedSequence(text, PlayerNamesModule.PATH_DRAW_OUTLINE);
	}

	@Inject(method = "split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;", at = @At("RETURN"))
	private void geileraddons$rememberPresentedSplit(FormattedText text, int width,
		CallbackInfoReturnable<List<FormattedCharSequence>> cir) {
		PlayerNamesModule.INSTANCE.markPresentedSequences(cir.getReturnValue(), PlayerNamesModule.PATH_SPLIT_OUTPUT);
	}

	@ModifyVariable(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private String geileraddons$presentStringWidth(String text) {
		return PlayerNamesModule.INSTANCE.replaceFontString(text, PlayerNamesModule.PATH_WIDTH_STRING);
	}

	@ModifyVariable(method = "width(Lnet/minecraft/network/chat/FormattedText;)I",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private FormattedText geileraddons$presentTextWidth(FormattedText text) {
		return PlayerNamesModule.INSTANCE.replaceFormattedText(text, PlayerNamesModule.PATH_WIDTH_TEXT);
	}

	@ModifyVariable(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private FormattedCharSequence geileraddons$presentSequenceWidth(FormattedCharSequence text) {
		return PlayerNamesModule.INSTANCE.replaceFormattedSequence(text, PlayerNamesModule.PATH_WIDTH_SEQUENCE);
	}

	@ModifyVariable(method = "plainSubstrByWidth(Ljava/lang/String;IZ)Ljava/lang/String;",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private String geileraddons$presentNarrowString(String text) {
		return PlayerNamesModule.INSTANCE.replaceFontString(text, PlayerNamesModule.PATH_WIDTH_STRING);
	}

	@ModifyVariable(method = "plainSubstrByWidth(Ljava/lang/String;I)Ljava/lang/String;",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private String geileraddons$presentNarrowStringDefault(String text) {
		return PlayerNamesModule.INSTANCE.replaceFontString(text, PlayerNamesModule.PATH_WIDTH_STRING);
	}

	@ModifyVariable(method = "substrByWidth(Lnet/minecraft/network/chat/FormattedText;I)Lnet/minecraft/network/chat/FormattedText;",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private FormattedText geileraddons$presentNarrowText(FormattedText text) {
		return PlayerNamesModule.INSTANCE.replaceFormattedText(text, PlayerNamesModule.PATH_WIDTH_TEXT);
	}

	@ModifyVariable(method = "wordWrapHeight(Lnet/minecraft/network/chat/FormattedText;I)I",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private FormattedText geileraddons$presentWrappedHeightText(FormattedText text) {
		return PlayerNamesModule.INSTANCE.replaceFormattedText(text, PlayerNamesModule.PATH_WIDTH_TEXT);
	}

	@ModifyVariable(method = "split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private FormattedText geileraddons$presentSplitText(FormattedText text) {
		return PlayerNamesModule.INSTANCE.replaceFormattedText(text, PlayerNamesModule.PATH_SPLIT_TEXT);
	}

	@ModifyVariable(method = "splitIgnoringLanguage(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;",
		at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private FormattedText geileraddons$presentSplitIgnoringLanguage(FormattedText text) {
		return PlayerNamesModule.INSTANCE.replaceFormattedText(text, PlayerNamesModule.PATH_SPLIT_TEXT);
	}
}
