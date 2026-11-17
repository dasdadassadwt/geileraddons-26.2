package geiler.addons.client.mining;

import java.util.List;

/** Offline checks for the Heart of the Mountain perk costs and menu text. */
public final class HotmChecks {
	private HotmChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		HotmPerks.Perk speed = HotmPerks.byName("Mining Speed");
		require(speed != null && speed.getMaxLevel() == 50 && "Mithril".equals(speed.getPowder()), "Mining Speed is a 50-level Mithril perk");
		require(HotmPerks.byName("  mining speed ") == speed, "names match ignoring case and spaces");
		require(HotmPerks.byName("Nothing") == null, "an unknown perk is not guessed");
		require(HotmPerks.cost(speed, 2) == 27 && HotmPerks.cost(speed, 3) == 64, "cost of a level is (level+1)^3 for Mining Speed");
		require(HotmPerks.cost(speed, 1) == 0, "level one is free");
		require(HotmPerks.totalCost(speed, 1) == 0, "nothing is spent at level one");
		require(HotmPerks.totalCost(speed, 50) == 1758267L, "total to max is the sum of the cubes");
		HotmPerks.Upgrade ten = HotmPerks.next(speed, 1, 10);
		require(ten.getLevels() == 10 && ten.getCost() == 6075L, "ten levels from level one cost 6075");
		HotmPerks.Upgrade nearMax = HotmPerks.next(speed, 47, 10);
		require(nearMax.getLevels() == 3, "fewer than ten levels left shortens the count");
		require(nearMax.getCost() == HotmPerks.toMax(speed, 47), "a shortened count costs exactly the remainder");
		require(HotmPerks.next(speed, 50, 10).getLevels() == 0 && HotmPerks.next(speed, 50, 10).getCost() == 0L, "a maxed perk has nothing left");
		HotmPerks.Perk ability = HotmPerks.byName("Pickobulus");
		require(ability != null && !ability.hasCost() && HotmPerks.totalCost(ability, 3) == 0L, "abilities cost no powder");

		List<String> lore = List.of("Level 12/50", "Gain mining speed", "Cost", "2,197 Mithril Powder", "ENABLED");
		HotmText.Reading reading = HotmText.read("Mining Speed", lore);
		require(reading != null && reading.getLevel() == 12 && reading.getUnlocked() && Boolean.TRUE.equals(reading.getEnabled()), "level and enabled are read");
		require("12".equals(HotmText.slotText(reading)), "the slot shows the level number");
		HotmText.Reading locked = HotmText.read("Mining Speed", List.of("Level 1/50", "Requires Tier 2"));
		require(locked != null && !locked.getUnlocked() && locked.getLevel() == 0 && HotmText.slotText(locked).isEmpty(), "a locked perk has no number");
		HotmText.Reading disabled = HotmText.read("Mining Fortune", List.of("Level 5/50", "DISABLED"));
		require(disabled != null && Boolean.FALSE.equals(disabled.getEnabled()), "DISABLED reads as off");
		require(HotmText.highlight(reading) != HotmText.highlight(disabled), "enabled and disabled highlight differently");
		HotmText.Reading maxed = HotmText.read("Mining Speed", List.of("MAXED"));
		require(maxed != null && maxed.maxed() && maxed.getLevel() == 50, "no level line on an unlocked perk means max");
		require(HotmText.read("Unknown Thing", lore) == null, "unknown items are ignored");
		require(Integer.valueOf(15).equals(HotmText.tokens(List.of("Token of the Mountain: 15"))), "heart tokens are read");

		HotmText.Options all = new HotmText.Options(true, true, true, true, 10);
		List<String> lines = HotmText.tooltipLines(reading, all, 5000L);
		require(lines.size() == 4, "spent, next ten, to max and holding make four lines, got " + lines.size());
		require(lines.get(1).contains("for 10 levels"), "the next-ten line names the level count");
		require(lines.get(3).contains("5,000"), "the holding line shows the powder held");
		require(HotmText.tooltipLines(maxed, all, 5000L).size() == 1, "a maxed perk only shows what was spent");
		require(HotmText.tooltipLines(locked, all, 5000L).isEmpty(), "a locked perk adds nothing");
		require(HotmText.tooltipLines(reading, all, null).size() == 3, "unknown holdings skip the holding line");
		System.out.println("HotM checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
