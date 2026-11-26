package geiler.addons.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import geiler.addons.client.module.impl.ArmorTransparencyModule;
import geiler.addons.client.module.impl.ArmorTransparencyRules;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Armor Transparency: for a player's head, chest, legs or feet armor layer, hides it (opacity 0) or draws it with the
 * translucent armor render type and a scaled alpha. Elytra and other layer types are left alone. Armor is submitted
 * on the render thread one layer at a time, so the opacity of the layer being drawn lives in a plain static.
 */
@Mixin(EquipmentLayerRenderer.class)
public abstract class EquipmentLayerRendererArmorTransparencyMixin {
	private static final String RENDER_LAYERS = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;"
		+ "Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
		+ "Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;"
		+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V";

	/** Opacity of the layer being drawn; 1 outside an affected armor layer. */
	private static float geileraddons$opacity = 1.0f;

	@Inject(method = RENDER_LAYERS, at = @At("HEAD"), cancellable = true)
	private <S> void geileraddons$armorOpacity(EquipmentClientInfo.LayerType type, ResourceKey<EquipmentAsset> asset,
		Model<? super S> model, S state, ItemStack stack, PoseStack pose, SubmitNodeCollector collector, int light,
		Identifier texture, int outlineColor, int order, CallbackInfo ci) {
		geileraddons$opacity = 1.0f;
		if (!(state instanceof AvatarRenderState player)) return;
		if (type != EquipmentClientInfo.LayerType.HUMANOID && type != EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS
			&& type != EquipmentClientInfo.LayerType.HUMANOID_BABY) return;
		EquipmentSlot slot = ArmorTransparencyRules.slotOf(stack, player.headEquipment, player.chestEquipment,
			player.legsEquipment, player.feetEquipment);
		if (slot == null) return;
		float opacity = ArmorTransparencyModule.INSTANCE.opacityFor(slot, player.id);
		if (opacity <= 0.0f) {
			ci.cancel();
			return;
		}
		geileraddons$opacity = opacity;
	}

	@Inject(method = RENDER_LAYERS, at = @At("RETURN"))
	private void geileraddons$armorOpacityDone(EquipmentClientInfo.LayerType type, ResourceKey<EquipmentAsset> asset,
		Model<?> model, Object state, ItemStack stack, PoseStack pose, SubmitNodeCollector collector, int light,
		Identifier texture, int outlineColor, int order, CallbackInfo ci) {
		geileraddons$opacity = 1.0f;
	}

	/** A faded layer needs the translucent armor render type; cutout would ignore the alpha. */
	@WrapOperation(method = RENDER_LAYERS, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;armorCutoutNoCull(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
	private RenderType geileraddons$translucentArmor(Identifier texture, Operation<RenderType> original) {
		return geileraddons$opacity < 1.0f ? RenderTypes.armorTranslucent(texture) : original.call(texture);
	}

	@WrapOperation(method = RENDER_LAYERS, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer;getColorForLayer(Lnet/minecraft/client/resources/model/EquipmentClientInfo$Layer;I)I"))
	private int geileraddons$fadeLayerColor(EquipmentClientInfo.Layer layer, int dyed, Operation<Integer> original) {
		int color = original.call(layer, dyed);
		return geileraddons$opacity < 1.0f ? ArmorTransparencyRules.fade(color, geileraddons$opacity) : color;
	}

	/** The enchantment glint would stay fully opaque on a faded layer, so a faded layer draws none. */
	@WrapOperation(method = RENDER_LAYERS, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/item/ItemStack;hasFoil()Z"))
	private boolean geileraddons$noGlintWhileFaded(ItemStack stack, Operation<Boolean> original) {
		return geileraddons$opacity >= 1.0f && original.call(stack);
	}
}
