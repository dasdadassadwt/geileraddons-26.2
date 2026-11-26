package geiler.addons.client.module.impl;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.Setting;

import java.util.List;

/** Offline checks for Legacy Textures' rules and its module declaration. */
public final class LegacyTexturesChecks {
	private LegacyTexturesChecks() { }

	public static void run() {
		// Pack models: a model outside minecraft (and outside this mod) or any custom model data marks a pack item.
		check(LegacyTexturesRules.isPackModel("hypixel", false) && LegacyTexturesRules.isPackModel("skyblock", false),
			"a model in a server pack namespace is a pack model");
		check(!LegacyTexturesRules.isPackModel("minecraft", false) && !LegacyTexturesRules.isPackModel(null, false)
			&& !LegacyTexturesRules.isPackModel("geileraddons", false), "vanilla, absent and this mod's own models are not pack models");
		check(LegacyTexturesRules.isPackModel("minecraft", true) && LegacyTexturesRules.isPackModel(null, true),
			"custom model data marks a pack item even with a vanilla model");

		// Server address: Hypixel or a subdomain, port ignored, no look-alike hosts.
		check(LegacyTexturesRules.isHypixelAddress("mc.hypixel.net") && LegacyTexturesRules.isHypixelAddress("HYPIXEL.NET:25565")
			&& LegacyTexturesRules.isHypixelAddress("hypixel.net"), "Hypixel addresses are recognised");
		check(!LegacyTexturesRules.isHypixelAddress("hypixel.net.evil.com") && !LegacyTexturesRules.isHypixelAddress("nothypixel.net")
			&& !LegacyTexturesRules.isHypixelAddress(null) && !LegacyTexturesRules.isHypixelAddress(""),
			"look-alike and empty addresses are not Hypixel");

		// Groups: heads before armor (a skull is also equippable), then weapons and tools, then everything else.
		check(LegacyTexturesRules.groupOf(true, true, false) == LegacyTexturesRules.Group.HEADS
			&& LegacyTexturesRules.groupOf(true, false, false) == LegacyTexturesRules.Group.ARMOR
			&& LegacyTexturesRules.groupOf(false, false, true) == LegacyTexturesRules.Group.WEAPONS
			&& LegacyTexturesRules.groupOf(false, false, false) == LegacyTexturesRules.Group.OTHER, "items sort into four groups");

		// Fallbacks: only a placeholder base item gets a matching vanilla look chosen from the SkyBlock id.
		check(LegacyTexturesRules.fallbackItem("HYPERION_SWORD", "minecraft:barrier").equals("minecraft:iron_sword")
			&& LegacyTexturesRules.fallbackItem("DIVAN_HELMET", "minecraft:barrier").equals("minecraft:iron_helmet")
			&& LegacyTexturesRules.fallbackItem("DIVAN_DRILL", "minecraft:barrier").equals("minecraft:iron_pickaxe")
			&& LegacyTexturesRules.fallbackItem("SOMETHING_ODD", "minecraft:barrier").equals("minecraft:paper"),
			"a placeholder item takes a vanilla look matching its SkyBlock id, else paper");
		check(LegacyTexturesRules.fallbackItem("HYPERION_SWORD", "minecraft:iron_sword") == null
			&& LegacyTexturesRules.fallbackItem("", "minecraft:barrier") == null, "a real base item or an empty id gets no fallback");

		// Module declaration: off by default, six switches that default on.
		LegacyTexturesModule module = LegacyTexturesModule.INSTANCE;
		check("Legacy Textures".equals(module.name()) && module.category() == Category.VISUAL && !module.defaultEnabled(),
			"Legacy Textures is a Visual module that is off by default");
		check(List.of("Block Server Pack", "Old Item Models", "Weapons And Tools", "Armor", "Heads", "Other Items")
				.equals(module.booleanSettings().stream().map(Setting::name).toList())
			&& module.booleanSettings().stream().allMatch(s -> s.value()), "six switches, all on");
		check(!module.shouldBlockPack("mc.hypixel.net") && !module.oldModelsActive(), "the module changes nothing while it is off");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("LegacyTexturesChecks: " + message);
	}
}
