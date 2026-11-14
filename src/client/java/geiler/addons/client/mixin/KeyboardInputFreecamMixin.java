package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import geiler.addons.client.module.impl.FreecamModule;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Gives the movement keys to the Freecam camera and hands the player an empty input. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputFreecamMixin {
	@WrapOperation(method = "tick", at = @At(value = "NEW", target = "Lnet/minecraft/world/entity/player/Input;"))
	private Input geileraddons$feedFreecam(boolean forward, boolean backward, boolean left, boolean right,
		boolean jump, boolean shift, boolean sprint, Operation<Input> original) {
		if (!FreecamModule.INSTANCE.isActive()) return original.call(forward, backward, left, right, jump, shift, sprint);
		FreecamModule.INSTANCE.captureKeys(forward, backward, left, right, jump, shift);
		return original.call(false, false, false, false, false, false, false);
	}
}
