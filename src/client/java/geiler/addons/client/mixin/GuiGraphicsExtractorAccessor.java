package geiler.addons.client.mixin;

import geiler.addons.client.gui.access.GuiGraphicsExtractorRenderStateAccess;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to the frame's GUI element collector for custom batched shapes. */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor extends GuiGraphicsExtractorRenderStateAccess {
	@Override
	@Accessor("guiRenderState")
	GuiRenderState geileraddons$getGuiRenderState();
}
