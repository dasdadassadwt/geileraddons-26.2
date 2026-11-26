package geiler.addons.client.mixin;

import java.util.Map;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.SkullBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Armor Transparency: the vanilla skull texture per skull type, needed to rebuild a skull's render type as translucent. */
@Mixin(SkullBlockRenderer.class)
public interface SkullBlockRendererAccessor {
	@Accessor("SKIN_BY_TYPE")
	static Map<SkullBlock.Type, Identifier> geileraddons$skinByType() {
		throw new AssertionError();
	}
}
