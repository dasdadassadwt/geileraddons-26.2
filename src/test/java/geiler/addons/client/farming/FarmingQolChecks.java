package geiler.addons.client.farming;

import geiler.addons.client.module.impl.MouseLockModule;
import geiler.addons.client.module.impl.VisitorMessageHiderModule;

/** Offline checks for Mouse Lock and the Visitor Message Hider. */
public final class FarmingQolChecks {
	private FarmingQolChecks() { }

	public static void run() {
		check(VisitorRules.isArrivalLine("NEW VISITOR Spaceman has arrived on your Garden!")
			&& VisitorRules.isArrivalLine("NEW VISITOR [MVP+] Jacob has arrived on your Garden!"), "the arrival line is recognised, rank or not");
		check(!VisitorRules.isArrivalLine("Party > NEW VISITOR x has arrived on your Garden!")
			&& !VisitorRules.isArrivalLine("NEW VISITOR has arrived on your Garden!")
			&& !VisitorRules.isArrivalLine("You have 3 visitors"), "a quoted line, a nameless line and other chat are not arrivals");

		MouseLockModule lock = MouseLockModule.INSTANCE;
		check(!lock.defaultEnabled() && !lock.captureTurn(), "Mouse Lock is off by default and lets the mouse turn the view");
		VisitorMessageHiderModule hider = VisitorMessageHiderModule.INSTANCE;
		check(!hider.defaultEnabled() && !hider.shouldHide("NEW VISITOR Spaceman has arrived on your Garden!"),
			"the hider is off by default and hides nothing while disabled");

		checkRareCrop();
		checkComposter();
	}

	private static void checkRareCrop() {
		check("Burrowing Spores".equals(RareCropRules.name("VERY RARE CROP! Burrowing Spores")) && RareCropRules.isVeryRare("VERY RARE CROP! Burrowing Spores"),
			"a very rare crop line gives the item name");
		check("Cropie".equals(RareCropRules.name("RARE CROP! Cropie")) && !RareCropRules.isVeryRare("RARE CROP! Cropie"), "a rare crop line is not very rare");
		check("Squash".equals(RareCropRules.name("RARE CROP! Squash (x2)")), "a trailing bracket is dropped from the name");
		check(RareCropRules.name("Party > Sam: RARE CROP! Cropie") == null && RareCropRules.name("RARE CROP!") == null && RareCropRules.name("You found a rare crop") == null,
			"quoted, nameless and other lines are not drops");
	}

	private static void checkComposter() {
		java.util.List<String> tab = java.util.List.of("Area: Garden", "Composter:", " Organic Matter: 12.5k", " Fuel: 3,400", " Time Left: 7m 30s", " Stored Compost: 1,234", "", "Visitors: (3)");
		ComposterRules.Data data = ComposterRules.parse(tab);
		check(data != null && data.getOrganicMatter() == 12_500L && data.getFuel() == 3_400L && "7m 30s".equals(data.getTimeLeft()) && "1,234".equals(data.getStored()),
			"the composter block is read with k suffixes and commas");
		check(ComposterRules.parse(java.util.List.of("Composter:", " Organic Matter: §a900", " Fuel: 10k", " Time Left: INACTIVE")).getTimeLeft().equals("INACTIVE"),
			"colour codes are ignored and the block may end at the list end");
		check(ComposterRules.parse(java.util.List.of("Area: Garden", "Visitors: (3)")) == null && ComposterRules.parse(java.util.List.of()) == null,
			"no composter block means no data");
		check("Organic Matter".equals(ComposterRules.lowResource(data, 20_000L, 1_000L)) && "Fuel".equals(ComposterRules.lowResource(data, 1_000L, 5_000L))
			&& ComposterRules.lowResource(data, 1_000L, 1_000L) == null && ComposterRules.lowResource(data, 0L, 0L) == null,
			"low-resource warning names the first resource under its threshold, and zero thresholds disable it");
		check(ComposterRules.lines(data).size() == 4 && ComposterRules.lines(data).get(2).contains("12,500"), "the HUD shows four lines");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError("FarmingQolChecks: " + message);
	}
}
