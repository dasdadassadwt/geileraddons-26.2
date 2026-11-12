package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import geiler.addons.client.module.impl.SnapTapModule;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Applies Snap Tap last-input-priority to the four movement keys of the player input. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputSnapTapMixin {
	@WrapOperation(method = "tick", at = @At(value = "NEW", target = "Lnet/minecraft/world/entity/player/Input;"))
	private Input geileraddons$resolveOppositeKeys(boolean forward, boolean backward, boolean left, boolean right,
		boolean jump, boolean shift, boolean sprint, Operation<Input> original) {
		int keys = SnapTapModule.INSTANCE.resolveMovement(forward, backward, left, right);
		return original.call((keys & SnapTapModule.FORWARD) != 0, (keys & SnapTapModule.BACKWARD) != 0,
			(keys & SnapTapModule.LEFT) != 0, (keys & SnapTapModule.RIGHT) != 0, jump, shift, sprint);
	}
}
