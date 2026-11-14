package geiler.addons.client.module.impl;

import java.util.ArrayList;
import java.util.List;

/** Offline checks for the Animation, Double Use and Disconnect fixes. */
public final class GameFixChecks {
	private GameFixChecks() { }

	public static void run() {
		check(GameFixRules.disableType(true, "") == GameFixRules.DisableType.ROD
			&& GameFixRules.disableType(true, "Ability: Attunement  RIGHT CLICK") == GameFixRules.DisableType.ROD,
			"a fishing rod always selects the rod fix");
		check(GameFixRules.disableType(false, "Ability: Attunement  RIGHT CLICK") == GameFixRules.DisableType.DAGGER,
			"a right-click ability naming Attunement marks a Blaze dagger");
		check(GameFixRules.disableType(false, "Ability: Instant Transmission  RIGHT CLICK") == GameFixRules.DisableType.NONE
			&& GameFixRules.disableType(false, "") == GameFixRules.DisableType.NONE, "other items select no fix");

		check(GameFixRules.active(true, false, false) && GameFixRules.active(true, true, true)
			&& !GameFixRules.active(true, true, false) && !GameFixRules.active(false, false, true),
			"a SkyBlock-only fix runs only on SkyBlock, and never while its module is off");

		List<String> items = new ArrayList<>(List.of("a", "pose", "b", "pose"));
		check(GameFixRules.removeFirstMatching(items, "pose"::equals) == 1 && items.equals(List.of("a", "b", "pose")),
			"only the first pose entry is removed from the packet list");
		check(GameFixRules.removeFirstMatching(items, "none"::equals) == 0 && items.size() == 3, "a list without a pose entry is unchanged");

		check(AnimationFixModule.INSTANCE.booleanSettings().size() == 1 && !AnimationFixModule.INSTANCE.booleanSettings().get(0).value()
			&& !AnimationFixModule.INSTANCE.defaultEnabled(), "Animation Fix has one Skyblock Only switch, off by default");
		check(DoubleUseFixModule.INSTANCE.booleanSettings().size() == 1 && !DoubleUseFixModule.INSTANCE.booleanSettings().get(0).value()
			&& !DoubleUseFixModule.INSTANCE.defaultEnabled(), "Double Use Fix has one Skyblock Only switch, off by default");
		check(!DisconnectFixModule.INSTANCE.hasSettings() && !DisconnectFixModule.INSTANCE.defaultEnabled(),
			"Disconnect Fix has no settings and is off by default");
		check(!AnimationFixModule.INSTANCE.active() && !DoubleUseFixModule.INSTANCE.shouldCancelItemUse()
			&& !DoubleUseFixModule.INSTANCE.redirectBlockUse(), "disabled fixes do nothing");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
