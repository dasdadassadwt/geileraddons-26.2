package geiler.addons.client.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies version-sensitive Spirit Leap hooks only to their verified dependency builds. */
public final class GeilerAddonsClientMixinPlugin implements IMixinConfigPlugin {
	@Override public void onLoad(String mixinPackage) {
		FabricRenderingContextSupport.inspectInstalledVersion();
		SkyblockerSpiritLeapSupport.inspectInstalledVersion();
		SodiumWorldRendererSpiritLeapSupport.inspectInstalledVersion();
	}

	@Override public String getRefMapperConfig() { return null; }

	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (SodiumWorldRendererSpiritLeapSupport.isHookMixin(mixinClassName)) {
			boolean apply = SodiumWorldRendererSpiritLeapSupport.shouldApplyHook(mixinClassName,
				SodiumWorldRendererSpiritLeapSupport.installedVersion());
			SodiumWorldRendererSpiritLeapSupport.recordHookDecision(apply);
			return apply;
		}
		if (SkyblockerSpiritLeapSupport.isHookMixin(mixinClassName)) {
			boolean apply = SkyblockerSpiritLeapSupport.shouldApplyHook(mixinClassName,
				SkyblockerSpiritLeapSupport.installedVersion());
			SkyblockerSpiritLeapSupport.recordHookDecision(apply);
			return apply;
		}
		if (!FabricRenderingContextSupport.isAccessorMixin(mixinClassName)) return true;
		boolean apply = FabricRenderingContextSupport.shouldApplyAccessor(mixinClassName,
			FabricRenderingContextSupport.installedVersion());
		FabricRenderingContextSupport.recordAccessorDecision(apply);
		return apply;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }

	@Override public List<String> getMixins() { return null; }

	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
		IMixinInfo mixinInfo) { }

	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
		IMixinInfo mixinInfo) { }
}
