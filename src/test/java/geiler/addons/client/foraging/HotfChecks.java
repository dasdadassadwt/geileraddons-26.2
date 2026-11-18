package geiler.addons.client.foraging;

import geiler.addons.client.mining.HotmPerks;
import geiler.addons.client.mining.HotmText;
import java.util.List;
import java.util.Map;

/** Offline checks for the Heart of the Forest perk costs and menu text. */
public final class HotfChecks {
	private HotfChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		HotmPerks.Perk sweep = HotfPerks.byName("Sweep");
		require(sweep != null && sweep.getMaxLevel() == 50 && "Whispers".equals(sweep.getPowder()), "Sweep is a 50-level Whispers perk");
		require(HotfPerks.byName("  sweep ") == sweep, "names match ignoring case and spaces");
		require(HotfPerks.byName("Heart of the Forest") == null && HotfPerks.byName("Mining Speed") == null, "only forest perks are known");
		require(HotmPerks.cost(sweep, 2) == 27 && HotmPerks.totalCost(sweep, 50) == 1758267L, "Sweep costs (level+1)^3 per level");
		HotmPerks.Perk wishes = HotfPerks.byName("Daily Wishes");
		require(wishes != null && HotmPerks.cost(wishes, 2) == 236 && HotmPerks.cost(wishes, 100) == 2000, "Daily Wishes costs 200 + 18 per level");
		require(HotmPerks.totalCost(wishes, 100) == 110682L, "Daily Wishes total to max is the linear sum");
		HotmPerks.Perk ability = HotfPerks.byName("Maniac Slicer");
		require(ability != null && !ability.hasCost() && HotmPerks.totalCost(ability, 2) == 0L, "abilities cost no whispers");
		HotmPerks.Perk core = HotfPerks.byName("Center of the Forest");
		require(core != null && core.getMaxLevel() == 5 && !core.hasCost(), "Center of the Forest is the 5-level core");

		List<String> lore = List.of("Level 12/50", "Gain sweep", "Cost", "2,197 Forest Whispers", "ENABLED");
		HotmText.Reading reading = HotfPerks.read("Sweep", lore);
		require(reading != null && reading.getLevel() == 12 && reading.getUnlocked() && Boolean.TRUE.equals(reading.getEnabled()), "level and enabled are read");
		require("12".equals(HotmText.slotText(reading)), "the slot shows the level number");
		HotmText.Reading locked = HotfPerks.read("Sweep", List.of("Level 1/50", "Requires Strength Boost"));
		require(locked != null && !locked.getUnlocked() && locked.getLevel() == 0, "a locked perk reads as level zero");
		HotmText.Reading forestLocked = HotfPerks.read("Sweep", List.of("Unlock the Forest!"));
		require(forestLocked != null && !forestLocked.getUnlocked(), "a line ending in Forest! locks the perk");
		HotmText.Reading coreOpen = HotfPerks.read("Center of the Forest", List.of("Level 2/5", "Unlock the Forest!"));
		require(coreOpen != null && coreOpen.getUnlocked() && coreOpen.getLevel() == 2, "the core is never locked by the Forest! line");
		HotmText.Reading disabled = HotfPerks.read("Foraging Fortune", List.of("Level 5/50", "DISABLED"));
		require(disabled != null && Boolean.FALSE.equals(disabled.getEnabled()), "DISABLED reads as off");
		require(HotmText.highlight(reading) != HotmText.highlight(disabled), "enabled and disabled highlight differently");
		HotmText.Reading maxed = HotfPerks.read("Sweep", List.of("Level 50", "SELECTED"));
		require(maxed != null && maxed.maxed() && maxed.getLevel() == 50, "a perk with no max on its level line is maxed");
		require(HotfPerks.read("Unknown Thing", lore) == null, "unknown items are ignored");

		require(Integer.valueOf(7).equals(HotmText.tokens(List.of("Tokens of the Forest: 7"))), "forest tokens are read");
		require(Integer.valueOf(15).equals(HotmText.tokens(List.of("Token of the Mountain: 15"))), "mountain tokens still read");
		Map<String, Long> held = HotfPerks.whispers(List.of("Forest Whispers: 25,271", "Desert Whispers: 900"));
		require(Long.valueOf(25271L).equals(held.get("Forest Whispers")) && Long.valueOf(900L).equals(held.get("Desert Whispers")), "both held whispers are read, commas included");
		require(HotfPerks.whispers(List.of("Tokens of the Forest: 7")).isEmpty(), "no whispers line reads as nothing held");
		require("Desert Whispers".equals(HotfPerks.currency("1,234 Desert Whispers")) && "Forest Whispers".equals(HotfPerks.currency(" 56 Forest Whispers ")), "the cost line names the currency");
		require(HotfPerks.currency("2,197 Mithril Powder") == null && HotfPerks.currency("Cost") == null, "other lines name no currency");

		HotmText.Options all = new HotmText.Options(true, true, true, true, 10);
		List<String> lines = HotmText.tooltipLines(reading, all, 5000L, "Desert Whispers");
		require(lines.size() == 4, "spent, next ten, to max and holding make four lines, got " + lines.size());
		require(lines.get(0).contains("Desert Whispers spent"), "the spent line names the currency");
		require(lines.get(1).contains("Desert Whispers for 10 levels"), "the next-ten line names the currency");
		require(lines.get(3).contains("5,000") && lines.get(3).contains("Desert Whispers") && !lines.get(3).contains("Powder"), "the holding line shows the currency");
		require(HotmText.tooltipLines(maxed, all, 5000L, "Forest Whispers").size() == 1, "a maxed perk only shows what was spent");
		require(HotmText.tooltipLines(locked, all, 5000L, "Forest Whispers").isEmpty(), "a locked perk adds nothing");
		require(HotmText.tooltipLines(reading, all, null, "Forest Whispers").size() == 3, "unknown holdings skip the holding line");
		System.out.println("HotF checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
