package geiler.addons.client.presentation;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.impl.PlayerNamesModule;
import geiler.addons.client.module.impl.PlayerPresentationModule;
import geiler.addons.client.module.impl.PlayerSizeModule;
import net.minecraft.network.chat.Component;

/** Focused checks for the legacy Java-facing Player Presentation bridge. */
public final class PlayerPresentationModuleChecks {
	private PlayerPresentationModuleChecks() { }

	public static void run() {
		PlayerSizeOverrideChecks.run();
		PlayerPresentationModule module = PlayerPresentationModule.INSTANCE;
		check(module.name().equals("Player Presentation")
			&& module.description().equals("Legacy settings bridge for Player Names and Player Size.")
			&& module.category() == Category.VISUAL
			&& module.configName().equals("Player Presentation"),
			"the compatibility module keeps its identity and config name");
		check(!module.defaultEnabled() && !module.isEnabled()
			&& !module.showsToggleControl() && !module.showsKeybindControl(),
			"the legacy bridge remains hidden and disabled by default");

		check(module.replaceNamesSetting() == PlayerNamesModule.INSTANCE.replaceNamesSetting()
			&& module.applyNameStylesSetting() == PlayerNamesModule.INSTANCE.applyNameStylesSetting()
			&& module.applySizesSetting() == PlayerSizeModule.INSTANCE.useRemoteSizesSetting(),
			"legacy settings accessors return the feature modules' existing setting objects");

		Component input = Component.literal("unmodified name");
		check(module.replaceRenderedName(null, input) == PlayerNamesModule.INSTANCE.replaceRenderedName(null, input)
			&& module.replaceKnownRenderedName(null) == PlayerNamesModule.INSTANCE.replaceKnownRenderedName(null),
			"nullable name rendering calls remain direct delegates");
		check(module.sizeMultiplier(null) == PlayerSizeModule.INSTANCE.sizeMultiplier(null)
			&& module.hasEnabledNameStyle(null) == PlayerNamesModule.INSTANCE.hasEnabledNameStyle(null)
			&& java.util.Objects.equals(module.effectMetadata(null), PlayerNamesModule.INSTANCE.effectMetadata(null)),
			"nullable player lookups remain direct delegates");
		check(module.chatLayoutPresentationRevision() == PlayerNamesModule.INSTANCE.chatLayoutPresentationRevision()
			&& module.chatComponentPresentationRevision() == PlayerNamesModule.INSTANCE.chatComponentPresentationRevision()
			&& module.chatAnimationFrameRevision() == PlayerNamesModule.INSTANCE.chatAnimationFrameRevision()
			&& module.chatMessagePresentationRevision(null)
				== PlayerNamesModule.INSTANCE.chatMessagePresentationRevision(null)
			&& module.chatMessageHasNamePresentation(null)
				== PlayerNamesModule.INSTANCE.chatMessageHasNamePresentation(null)
			&& module.chatMessageHasAnimatedNameStyle(null)
				== PlayerNamesModule.INSTANCE.chatMessageHasAnimatedNameStyle(null)
			&& module.hasActiveNamePresentationProfiles()
				== PlayerNamesModule.INSTANCE.hasActiveNamePresentationProfiles(),
			"chat and profile state accessors remain direct delegates");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError("PlayerPresentationModuleChecks: " + message);
	}
}
