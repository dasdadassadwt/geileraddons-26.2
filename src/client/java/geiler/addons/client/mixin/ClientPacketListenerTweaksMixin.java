package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import geiler.addons.client.module.impl.AnimationFixModule;
import geiler.addons.client.module.impl.DisconnectFixModule;
import java.util.List;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Animation Fix and Disconnect Fix: both act on the entity-data update right before it is assigned. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTweaksMixin {
	private static final String ASSIGN_VALUES = "Lnet/minecraft/network/syncher/SynchedEntityData;assignValues(Ljava/util/List;)V";

	@Inject(method = "handleSetEntityData", at = @At(value = "INVOKE", target = ASSIGN_VALUES))
	private void geileraddons$dropOwnPose(ClientboundSetEntityDataPacket packet, CallbackInfo ci,
		@Local Entity entity) {
		AnimationFixModule.INSTANCE.onEntityData(entity, packet.packedItems());
	}

	@WrapOperation(method = "handleSetEntityData", at = @At(value = "INVOKE", target = ASSIGN_VALUES))
	private void geileraddons$swallowCorruptedData(SynchedEntityData data,
		List<SynchedEntityData.DataValue<?>> items, Operation<Void> original) {
		if (!DisconnectFixModule.INSTANCE.isEnabled()) {
			original.call(data, items);
			return;
		}
		try {
			original.call(data, items);
		} catch (Exception exception) {
			DisconnectFixModule.INSTANCE.onCaught(exception);
		}
	}
}
