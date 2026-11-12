package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PlayerPresentationModule;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Covers team-formatted player names, including scoreboard/sidebar score owners. */
@Mixin(PlayerTeam.class)
public abstract class PlayerTeamNameMixin {
	@Inject(method = "formatNameForTeam(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;",
		at = @At("RETURN"), cancellable = true)
	private static void geileraddons$replaceFormattedPlayerName(Team team, Component name,
		CallbackInfoReturnable<MutableComponent> cir) {
		MutableComponent result = cir.getReturnValue();
		if (result == null) return;
		Component replacement = PlayerPresentationModule.INSTANCE.replaceKnownRenderedName(result);
		if (replacement != result) cir.setReturnValue((MutableComponent) replacement);
	}
}
