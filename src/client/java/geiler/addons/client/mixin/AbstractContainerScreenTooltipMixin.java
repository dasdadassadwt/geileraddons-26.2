package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PlayerPresentationModule;
import geiler.addons.client.presentation.TooltipComponentMapper;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Applies configured player-name presentation to rendered container-item tooltip lines. */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenTooltipMixin {
	@Inject(method = "getTooltipFromContainerItem(Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
		at = @At("RETURN"), cancellable = true)
	private void geileraddons$presentContainerTooltipNames(ItemStack stack,
		CallbackInfoReturnable<List<Component>> cir) {
		List<Component> original = cir.getReturnValue();
		List<Component> presented = TooltipComponentMapper.mapComponents(original,
			PlayerPresentationModule.INSTANCE::replaceKnownRenderedName);
		if (presented != original) cir.setReturnValue(presented);
	}
}
