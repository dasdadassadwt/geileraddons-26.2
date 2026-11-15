package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.GhostVisionModule;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ghost Vision: the Dwarven Mines ghosts are drawn without the charged swirl that hides them. */
@Mixin(Creeper.class)
public abstract class CreeperGhostVisionMixin {
	@Inject(method = "isPowered", at = @At("RETURN"), cancellable = true)
	private void geileraddons$ghostNotCharged(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && GhostVisionModule.INSTANCE.suppressCharge((Creeper) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
