package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PlayerNamesModule;
import geiler.addons.client.presentation.PlayerNameFontTransform;
import geiler.addons.client.presentation.PlayerNameTextTransform;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents a targeted component alias from being transformed again after visual-order conversion. */
@Mixin(MutableComponent.class)
public abstract class PlayerNameComponentVisualOrderMixin {
	@Inject(method = "getVisualOrderText()Lnet/minecraft/util/FormattedCharSequence;", at = @At("RETURN"),
		cancellable = true)
	private void geileraddons$rememberPresentedVisualOrder(CallbackInfoReturnable<FormattedCharSequence> cir) {
		Component component = (Component) (Object) this;
		Component original = PlayerNameTextTransform.restorePresentedRoot(component);
		if (original == component) return;
		PlayerNamesModule module = PlayerNamesModule.INSTANCE;
		if (!module.isEnabled()) {
			cir.setReturnValue(original.getVisualOrderText());
			return;
		}
		long startedAtNanos = module.beginFontTransformTiming(PlayerNamesModule.PATH_VISUAL_ORDER);
		FormattedCharSequence raw = original.getVisualOrderText();
		FormattedCharSequence presented = PlayerNameFontTransform.transform(raw, PlayerNamesModule.PATH_PREPARE_SEQUENCE);
		module.recordFontTransform(PlayerNamesModule.PATH_VISUAL_ORDER, presented != raw,
			startedAtNanos, false, presented == raw);
		cir.setReturnValue(presented);
	}
}
