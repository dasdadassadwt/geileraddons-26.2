package geiler.addons.client.macros.sim;

import geiler.addons.client.macros.module.EtherwarpModule;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.TextSetting;

/**
 * Covers the Etherwarp module: its settings carry Odin's names and the defaults of the Odin configuration this was ported
 * for, the item and eye-height rules follow Odin, and the cooldown lets one warp through per two ticks.
 */
public final class EtherwarpChecks {
	private EtherwarpChecks() { }

	public static void run() {
		checkSettings();
		checkRules();
		checkCooldown();
	}

	private static BooleanSetting bool(String name) {
		return EtherwarpModule.INSTANCE.booleanSettings().stream().filter(s -> s.name().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError("missing toggle " + name));
	}

	private static ColorSetting color(String name) {
		return EtherwarpModule.INSTANCE.colorSettings().stream().filter(s -> s.name().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError("missing colour " + name));
	}

	private static NumberSetting number(String name) {
		return EtherwarpModule.INSTANCE.numberSettings().stream().filter(s -> s.name().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError("missing number " + name));
	}

	private static void checkSettings() {
		EtherwarpModule module = EtherwarpModule.INSTANCE;
		check("Etherwarp".equals(module.name()) && module.category() == Category.VISUAL, "a normal module named Etherwarp in Visual");
		check(!module.defaultEnabled(), "off on a fresh install");

		check(bool("Show Guess").value(), "Show Guess is on");
		ColorSetting success = color("Success Color");
		check(success.red() == 0x00 && success.green() == 0xFF && success.blue() == 0x14 && success.alpha() == 0x7F && success.alphaEditable(),
			"Success Color is #00FF147F");
		check(bool("Show when failed").value(), "Show when failed is on");
		ColorSetting fail = color("Fail Color");
		check(fail.red() == 0xFF && fail.green() == 0x55 && fail.blue() == 0x55 && fail.alpha() == 0x7F, "Fail Color is #FF55557F");
		ChoiceSetting style = module.choiceSettings().stream().filter(s -> s.name().equals("Box Style")).findFirst().orElseThrow();
		check("FILLED".equals(style.value()) && style.choices().containsAll(java.util.List.of("FILLED", "OUTLINE", "FILLED_OUTLINE")),
			"Box Style is FILLED with Odin's three styles");
		check(!bool("Use Server Position").value() && !bool("Full Block").value() && !bool("Depth").value(),
			"Use Server Position, Full Block and Depth are off");
		check(bool("Custom Sounds").value(), "Custom Sounds is on");
		TextSetting sound = module.textSettings().stream().filter(s -> s.name().equals("Etherwarp Sound")).findFirst().orElseThrow();
		check("entity.experience_orb.pickup".equals(sound.value()), "the sound is the experience orb pickup");
		check(number("Pitch").value() == 0.1f && number("Volume").value() == 1.0f, "pitch 0.1, volume 1.0");
	}

	private static void checkRules() {
		check(EtherwarpRules.eyeHeight(false, false) == 1.62, "standing eye height is 1.62");
		check(EtherwarpRules.eyeHeight(false, true) == 1.27, "sneaking eye height is 1.27");
		check(EtherwarpRules.eyeHeight(true, true) == 0.4 && EtherwarpRules.eyeHeight(true, false) == 0.4, "swimming eye height is 0.4");

		check(EtherwarpRules.isEtherwarpItem("ASPECT_OF_THE_VOID", 1), "an item with ethermerge 1 etherwarps");
		check(!EtherwarpRules.isEtherwarpItem("ASPECT_OF_THE_VOID", 0), "without ethermerge it does not");
		check(EtherwarpRules.isEtherwarpItem("ETHERWARP_CONDUIT", 0), "the Etherwarp Conduit always does");
		check(!EtherwarpRules.isEtherwarpItem("", 0) && !EtherwarpRules.isEtherwarpItem(null, 0), "an item with no id and no ethermerge never does");
		check(EtherwarpRules.isConduit("ETHERWARP_CONDUIT") && !EtherwarpRules.isConduit("ASPECT_OF_THE_VOID"), "only the conduit skips the sneak");

		check(EtherwarpRules.distance(0) == 57.0 && EtherwarpRules.distance(3) == 60.0 && EtherwarpRules.distance(-2) == 57.0,
			"range is 57 plus the tuned transmission");
		check(Math.abs(EtherwarpRules.landY(64) - 65.05) < 1e-9, "the landing is 1.05 above the block");
	}

	private static void checkCooldown() {
		EtherwarpCooldown cooldown = new EtherwarpCooldown();
		check(cooldown.tryWarp(100), "the first warp goes through");
		check(!cooldown.tryWarp(100), "a second click in the same tick is dropped");
		check(!cooldown.tryWarp(101), "a click in the next tick is dropped too");
		check(cooldown.tryWarp(102), "one full tick between two warps is enough");
		check(!cooldown.tryWarp(103), "and the cooldown starts again");
		check(cooldown.tryWarp(105), "a long pause is fine");
		cooldown.reset();
		check(cooldown.tryWarp(105), "reset forgets the last warp");
		check(cooldown.tryWarp(10), "a tick counter that restarted (new world) does not lock the module");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
