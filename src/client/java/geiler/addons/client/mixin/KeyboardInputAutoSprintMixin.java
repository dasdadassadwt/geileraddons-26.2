package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import geiler.addons.client.module.impl.AutoSprintModule;
import geiler.addons.client.module.impl.FreecamModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Holds the sprint flag of the player input while Auto Sprint is on. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputAutoSprintMixin {
	@WrapOperation(method = "tick", at = @At(value = "NEW", target = "Lnet/minecraft/world/entity/player/Input;"))
	private Input geileraddons$holdSprint(boolean forward, boolean backward, boolean left, boolean right,
		boolean jump, boolean shift, boolean sprint, Operation<Input> original) {
		boolean screenOpen = Minecraft.getInstance().gui.screen() != null;
		return original.call(forward, backward, left, right, jump, shift,
			AutoSprintModule.INSTANCE.resolveSprint(sprint, screenOpen, FreecamModule.INSTANCE.isActive()));
	}
}
