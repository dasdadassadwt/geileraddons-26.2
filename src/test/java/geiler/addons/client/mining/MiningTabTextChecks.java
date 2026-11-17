package geiler.addons.client.mining;

import java.util.List;

/** Offline checks for the Dwarven Mines tab-list widgets: Commissions and Powders. */
public final class MiningTabTextChecks {
	private MiningTabTextChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		List<String> tab = List.of(
			"Players (24)", "Area: Dwarven Mines",
			"Commissions:", " Goblin Raid Slayer: 12.5%", " Titanium Miner: DONE", " Cliffside Veins Collector: 100%",
			"Powders:", " Mithril: 1,234", " Gemstone: 56", " Glacite: 7",
			"Pet: Wither Skeleton");
		List<MiningTabText.Commission> commissions = MiningTabText.commissions(tab);
		require(commissions.size() == 3, "three commission rows are read, got " + commissions.size());
		require(commissions.get(0).getName().equals("Goblin Raid Slayer"), "the name excludes the progress");
		require(Math.abs(commissions.get(0).getFraction() - 0.125) < 1e-9, "a percentage becomes a fraction");
		require(commissions.get(1).getDone() && commissions.get(1).getFraction() == 1.0, "DONE is complete");
		require(commissions.get(2).getDone(), "100% counts as complete");
		require(commissions.get(0).progressText().equals("12.5%"), "progress text stays readable");
		require(MiningTabText.commissions(List.of("Powders:", " Mithril: 1")).isEmpty(), "no header means no rows");
		require(MiningTabText.commissions(null).isEmpty(), "null tab is empty, not a crash");

		MiningTabText.Powders powders = MiningTabText.powders(tab);
		require(powders != null && powders.getMithril() == 1234 && powders.getGemstone() == 56 && powders.getGlacite() == 7,
			"all three powders are read");
		MiningTabText.Powders partial = MiningTabText.powders(List.of("Powders:", " Mithril: 9"));
		require(partial != null && partial.getMithril() == 9 && partial.getGemstone() == 0, "a missing powder is zero");
		require(MiningTabText.powders(List.of("Area: Hub")) == null, "no widget means unknown");

		MiningPowderSession session = new MiningPowderSession();
		session.observe(new MiningTabText.Powders(100, 0, 0), 0L);
		session.observe(new MiningTabText.Powders(160, 5, 0), 30_000_000_000L);
		require(session.gainMithril() == 60 && session.gainGemstone() == 5, "gain is current minus first reading");
		session.observe(new MiningTabText.Powders(40, 5, 0), 60_000_000_000L);
		require(session.gainMithril() == 60, "spending powder does not reduce the gain");
		session.observe(new MiningTabText.Powders(100, 5, 0), 90_000_000_000L);
		require(session.gainMithril() == 120, "mining after a spend adds on");
		require(session.perHour(session.gainMithril(), 90_000_000_000L) == 4800, "per hour scales the elapsed time");
		session.reset();
		require(session.gainMithril() == 0, "reset clears the session");

		MiningItemText.Fuel fuel = MiningItemText.drillFuel(List.of("Some line", "Fuel: 2,450/3k"));
		require(fuel != null && fuel.getCurrent() == 2450 && fuel.getMax() == 3000, "a k suffix scales the tank size");
		require(Math.abs(fuel.fraction() - 2450.0 / 3000.0) < 1e-9, "fuel fraction is current over max");
		MiningItemText.Fuel plain = MiningItemText.drillFuel(List.of("Fuel: 100/20,000"));
		require(plain != null && plain.getMax() == 20000, "a plain size is read as written");
		require(MiningItemText.drillFuel(List.of("Fuel: lots")) == null, "an unreadable fuel line is unknown");
		require(MiningItemText.drillFuel(List.of()) == null, "no lore means no fuel");

		MiningTabText.Commission menu = MiningItemText.commissionFromMenu("Mithril Miner", List.of("Mine mithril", "Progress: 31.1%"));
		require(menu != null && Math.abs(menu.getFraction() - 0.311) < 1e-9, "menu progress is read");
		MiningTabText.Commission finished = MiningItemText.commissionFromMenu("Titanium Miner", List.of("COMPLETED"));
		require(finished != null && finished.getDone(), "COMPLETED is complete");
		require(MiningItemText.commissionFromMenu("Close", List.of("Click to close")) == null, "a button is not a commission");
		List<String> shown = MiningHudFormat.commissionLines(commissions, false, true);
		require(shown.size() == 4 && shown.get(0).endsWith("Commissions"), "a header and one line per commission");
		require(shown.get(2).startsWith("§a") && shown.get(2).contains("Titanium Miner"), "completed rows are highlighted");
		require(shown.get(1).contains("12.5%") && !shown.get(1).contains("DONE"), "progress shows as a percentage");
		require(MiningHudFormat.commissionLines(commissions, true, true).size() == 2, "hide completed drops finished rows");
		require(MiningHudFormat.commissionLines(List.of(), false, true).isEmpty(), "no commissions means no panel");
		require(MiningHudFormat.count(1234567) .equals("1,234,567"), "counts use thousands separators");
		MiningPowderSession lines = new MiningPowderSession();
		lines.observe(new MiningTabText.Powders(10, 0, 0), 0L);
		require(MiningHudFormat.powderLines(new MiningTabText.Powders(10, 0, 0), lines, 0L, true, true, false).size() == 2,
			"glacite is optional");
		require(MiningHudFormat.fuelColor(0.9) != MiningHudFormat.fuelColor(0.1), "fuel color changes with the level");
		System.out.println("Mining tab text checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
