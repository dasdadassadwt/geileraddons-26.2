package geiler.addons.client.enchanting;

import java.util.List;
import java.util.Set;

/** Offline checks for enchant line parsing, level tiers, item type detection and the missing-enchant list. */
public final class EnchantRulesChecks {
	private static final String DATA = "{\"enchants\":{"
		+ "\"sharpness\":{\"id\":\"sharpness\",\"name\":\"Sharpness\",\"good\":5,\"max\":7,\"kind\":\"NORMAL\"},"
		+ "\"smite\":{\"id\":\"smite\",\"name\":\"Smite\",\"good\":5,\"max\":7,\"kind\":\"NORMAL\"},"
		+ "\"critical\":{\"id\":\"critical\",\"name\":\"Critical\",\"good\":5,\"max\":7,\"kind\":\"NORMAL\"},"
		+ "\"bane of arthropods\":{\"id\":\"bane_of_arthropods\",\"name\":\"Bane of Arthropods\",\"good\":5,\"max\":7,\"kind\":\"NORMAL\"},"
		+ "\"wise\":{\"id\":\"ultimate_wise\",\"name\":\"Wise\",\"good\":0,\"max\":5,\"kind\":\"ULTIMATE\"},"
		+ "\"swarm\":{\"id\":\"ultimate_swarm\",\"name\":\"Swarm\",\"good\":0,\"max\":5,\"kind\":\"ULTIMATE\"},"
		+ "\"compact\":{\"id\":\"compact\",\"name\":\"Compact\",\"good\":0,\"max\":10,\"kind\":\"STACKING\"}},"
		+ "\"pools\":{\"SWORD\":[\"sharpness\",\"smite\",\"critical\",\"bane_of_arthropods\",\"ultimate_wise\",\"ultimate_swarm\"],"
		+ "\"FARMING_TOOL\":[\"compact\"],\"LONGSWORD\":[\"critical\"]},"
		+ "\"exclusive\":[[\"sharpness\",\"smite\",\"bane_of_arthropods\"],[\"ultimate_wise\",\"ultimate_swarm\"]]}";

	private EnchantRulesChecks() { }

	public static void run() {
		EnchantRules.Data data = EnchantRules.load(DATA);

		String line = "Sharpness VII, Bane of Arthropods V, Compact X 5,000, Unknown Thing III, Critical 6";
		List<EnchantRules.Token> tokens = EnchantRules.tokens(line, data);
		check(tokens.size() == 4, "known enchants are found and unknown names are ignored");
		check(line.substring(tokens.get(0).getStart(), tokens.get(0).getEnd()).equals("Sharpness VII") && tokens.get(0).getLevel() == 7,
			"a token spans the name and its roman level");
		check(line.substring(tokens.get(1).getStart(), tokens.get(1).getEnd()).equals("Bane of Arthropods V") && tokens.get(1).getLevel() == 5,
			"multi-word enchant names are matched whole");
		check(line.substring(tokens.get(2).getStart(), tokens.get(2).getEnd()).equals("Compact X") && tokens.get(2).getLevel() == 10,
			"a stacking counter after the level stays outside the token");
		check(tokens.get(3).getLevel() == 6 && line.substring(tokens.get(3).getStart(), tokens.get(3).getEnd()).equals("Critical 6"),
			"arabic levels are read too");
		check(EnchantRules.tokens("Grants +5 damage, to nearby mobs", data).isEmpty(), "ordinary lore is left alone");

		EnchantRules.Enchant sharp = data.getEnchants().get("sharpness");
		check(EnchantRules.tier(sharp, 7) == EnchantRules.Tier.PERFECT && EnchantRules.tier(sharp, 8) == EnchantRules.Tier.PERFECT,
			"max level or more is perfect");
		check(EnchantRules.tier(sharp, 6) == EnchantRules.Tier.GREAT && EnchantRules.tier(sharp, 5) == EnchantRules.Tier.GOOD
			&& EnchantRules.tier(sharp, 4) == EnchantRules.Tier.POOR, "levels between good and max are great, the good level is good, below is poor");
		check(EnchantRules.tier(data.getEnchants().get("wise"), 1) == EnchantRules.Tier.ULTIMATE, "ultimates have their own tier");

		check("SWORD".equals(EnchantRules.itemType(List.of("Some lore", "LEGENDARY DUNGEON SWORD"), data)), "a dungeon sword is a sword");
		check("LONGSWORD".equals(EnchantRules.itemType(List.of("EPIC LONGSWORD"), data)), "the longest matching item type wins");
		check("FARMING TOOL".equals(EnchantRules.itemType(List.of("x", "RARE FARMING TOOL", "SOULBOUND"), data)),
			"underscores in pool names match words and a footer line below the type is skipped");
		check(EnchantRules.itemType(List.of("just text"), data) == null, "no type line gives no item type");

		List<String> missing = EnchantRules.missing("SWORD", Set.of("sharpness"), data);
		check(missing.equals(List.of("Critical", "Ultimate")), "exclusive enchants and a whole ultimate group drop out: " + missing);
		check(EnchantRules.missing("SWORD", Set.of("ultimate_wise"), data).equals(List.of("Sharpness", "Smite", "Critical", "Bane of Arthropods")),
			"an owned ultimate removes the Ultimate entry");
		check(EnchantRules.missing("BOW", Set.of(), data).isEmpty(), "an unknown item type has nothing missing");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
