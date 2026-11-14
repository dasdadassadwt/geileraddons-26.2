package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.NoRenderModule;
import geiler.addons.client.module.impl.NoRenderModule.Flag;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render: the recipe book button. */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class AbstractRecipeBookScreenNoRenderMixin {
	@Inject(method = "initButton", at = @At("HEAD"), cancellable = true)
	private void geileraddons$noRecipeBookButton(CallbackInfo ci) {
		if (NoRenderModule.INSTANCE.active(Flag.RECIPE_BOOK)) ci.cancel();
	}
}
