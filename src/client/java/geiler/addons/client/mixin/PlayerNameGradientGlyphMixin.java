package geiler.addons.client.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import geiler.addons.client.presentation.PlayerNameGradientRenderState;
import net.minecraft.network.chat.Style;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Emits a left-to-right vertex-color ramp across each replacement glyph's quad. */
@Mixin(targets = "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph")
public abstract class PlayerNameGradientGlyphMixin {
	@Inject(method = "renderChar(Lnet/minecraft/client/gui/font/glyphs/BakedSheetGlyph$GlyphInstance;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZ)V",
		at = @At("HEAD"))
	private void geileraddons$beginGradientGlyph(@Coerce Object glyphInstance, Matrix4fc pose,
		VertexConsumer vertices, int packedLight, boolean seeThrough, CallbackInfo ci) {
		PlayerNameGradientGlyphInstanceAccessor access = (PlayerNameGradientGlyphInstanceAccessor) glyphInstance;
		Style style = access.geileraddons$getStyle();
		PlayerNameGradientRenderState.beginGlyph(style, access.geileraddons$getShadowColor());
	}

	@Inject(method = "renderChar(Lnet/minecraft/client/gui/font/glyphs/BakedSheetGlyph$GlyphInstance;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZ)V",
		at = @At("RETURN"))
	private void geileraddons$endGradientGlyph(@Coerce Object glyphInstance, Matrix4fc pose,
		VertexConsumer vertices, int packedLight, boolean seeThrough, CallbackInfo ci) {
		PlayerNameGradientRenderState.endGlyph();
	}

	@Inject(method = "render(ZFFFLorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZI)V",
		at = @At("HEAD"))
	private void geileraddons$beginGlyphPass(boolean italic, float x, float y, float depth,
		Matrix4fc pose, VertexConsumer vertices, int color, boolean bold, int packedLight, CallbackInfo ci) {
		PlayerNameGradientRenderState.beginRenderPass();
	}

	@ModifyArg(method = "render(ZFFFLorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZI)V",
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
			ordinal = 0), index = 0)
	private int geileraddons$gradientTopLeft(int color) {
		return PlayerNameGradientRenderState.vertexColor(color, false);
	}

	@ModifyArg(method = "render(ZFFFLorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZI)V",
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
			ordinal = 1), index = 0)
	private int geileraddons$gradientBottomLeft(int color) {
		return PlayerNameGradientRenderState.vertexColor(color, false);
	}

	@ModifyArg(method = "render(ZFFFLorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZI)V",
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
			ordinal = 2), index = 0)
	private int geileraddons$gradientBottomRight(int color) {
		return PlayerNameGradientRenderState.vertexColor(color, true);
	}

	@ModifyArg(method = "render(ZFFFLorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZI)V",
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
			ordinal = 3), index = 0)
	private int geileraddons$gradientTopRight(int color) {
		return PlayerNameGradientRenderState.vertexColor(color, true);
	}
}
