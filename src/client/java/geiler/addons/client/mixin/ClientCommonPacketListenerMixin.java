package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.DungeonAssistModule;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the server ping packet stream separately from local END_CLIENT_TICK events. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
	/**
	 * Terminal Simulator firewall: while a simulator screen is open, container packets are dropped before they are
	 * sent, so the server can never see a simulator click or close.
	 */
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void geileraddons$termSimFirewall(net.minecraft.network.protocol.Packet<?> packet, CallbackInfo ci) {
		if (geiler.addons.client.termsim.TermSimGuard.shouldDrop(packet)
			|| geiler.addons.client.sim.DeviceSimGuard.shouldDrop(packet)) ci.cancel();
	}

	/**
	 * Legacy Textures: a Hypixel resource pack push is answered as if it had been accepted, downloaded and loaded, so the
	 * server carries on, but the pack is never fetched or applied.
	 */
	@Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
	private void geileraddons$declineServerPack(net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket packet,
		CallbackInfo ci) {
		net.minecraft.client.multiplayer.ServerData server = net.minecraft.client.Minecraft.getInstance().getCurrentServer();
		String address = server == null ? null : server.ip;
		geiler.addons.client.module.impl.LegacyTexturesModule legacy = geiler.addons.client.module.impl.LegacyTexturesModule.INSTANCE;
		if (!legacy.shouldBlockPack(address)) return;
		ClientCommonPacketListenerImpl self = (ClientCommonPacketListenerImpl) (Object) this;
		for (net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action action : new net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action[] {
			net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action.ACCEPTED,
			net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action.DOWNLOADED,
			net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED }) {
			self.send(new net.minecraft.network.protocol.common.ServerboundResourcePackPacket(packet.id(), action));
		}
		legacy.notePackBlocked(packet.id(), address);
		ci.cancel();
	}

	@Inject(method = "handlePing", at = @At("TAIL"))
	private void geileraddons$countServerPingPacket(ClientboundPingPacket packet, CallbackInfo ci) {
		DungeonAssistModule.INSTANCE.onServerPingPacket(packet.getId());
		geiler.addons.client.module.impl.TunerSolverModule.INSTANCE.onServerPing(packet.getId());
	}
}
