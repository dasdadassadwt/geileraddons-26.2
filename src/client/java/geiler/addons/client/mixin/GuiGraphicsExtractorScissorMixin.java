package geiler.addons.client.mixin;

import geiler.addons.client.gui.access.GuiGraphicsExtractorScissorAccess;
import geiler.addons.client.gui.access.GuiGraphicsExtractorScissorStackAccess;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Mirrors the extractor's exact active scissor after its own stack performs each change. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorScissorMixin implements GuiGraphicsExtractorScissorAccess {
	@Unique
	private ScreenRectangle geileraddons$scissorArea;

	@Redirect(method = "enableScissor", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/client/gui/GuiGraphicsExtractor$ScissorStack;push(Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V"))
	private void geileraddons$trackScissorPush(@Coerce Object scissorStack, ScreenRectangle rectangle) {
		GuiGraphicsExtractorScissorStackAccess accessor = (GuiGraphicsExtractorScissorStackAccess) scissorStack;
		accessor.geileraddons$invokePush(rectangle);
		geileraddons$scissorArea = accessor.geileraddons$invokePeek();
	}

	@Redirect(method = "disableScissor", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/client/gui/GuiGraphicsExtractor$ScissorStack;pop()V"))
	private void geileraddons$trackScissorPop(@Coerce Object scissorStack) {
		GuiGraphicsExtractorScissorStackAccess accessor = (GuiGraphicsExtractorScissorStackAccess) scissorStack;
		accessor.geileraddons$invokePop();
		geileraddons$scissorArea = accessor.geileraddons$invokePeek();
	}

	@Override
	@Unique
	public ScreenRectangle geileraddons$getScissorArea() {
		return geileraddons$scissorArea;
	}
}
