package geiler.addons.client.dungeon.f7;

import java.util.List;
import java.util.Set;

/** Offline checks for Auto Croesus: the list, preview and chest decisions of a claim run. */
public final class AutoCroesusChecks {
	private AutoCroesusChecks() { }

	public static void main(String[] args) {
		run();
	}

	private static CroesusRules.ChestResult chest(String name, double profit, int slot) {
		return new CroesusRules.ChestResult(name, List.of(), profit, slot);
	}

	private static AutoCroesusRules.Settings settings(double minProfit, boolean keys, boolean kismet, String floors) {
		return new AutoCroesusRules.Settings(minProfit, keys, 500_000, 1_000_000, kismet, 2_000_000, floors);
	}

	public static void run() {
		checkMenu();
		checkFloors();
		checkClaimFlow();
		checkSkipAndKismet();
	}

	private static void checkMenu() {
		List<AutoCroesusRules.MenuRun> runs = List.of(new AutoCroesusRules.MenuRun(10, false), new AutoCroesusRules.MenuRun(11, true),
			new AutoCroesusRules.MenuRun(12, true));
		AutoCroesusRules.Decision d = AutoCroesusRules.menu(runs, Set.of(), 53);
		require(d.getAction() == AutoCroesusRules.Action.RUN && d.getSlot() == 11, "the first fresh run is opened");
		d = AutoCroesusRules.menu(runs, Set.of(11), 53);
		require(d.getSlot() == 12, "a skipped run is passed over");
		d = AutoCroesusRules.menu(runs, Set.of(11, 12), 53);
		require(d.getAction() == AutoCroesusRules.Action.PAGE && d.getSlot() == 53, "then the next page");
		d = AutoCroesusRules.menu(runs, Set.of(11, 12), -1);
		require(d.getAction() == AutoCroesusRules.Action.DONE, "no fresh runs and no next page is done");
		d = AutoCroesusRules.menu(List.of(new AutoCroesusRules.MenuRun(14, true, true)), Set.of(), -1);
		require(AutoCroesusRules.Run.from(d).getKismetted(), "a run whose Kismet Feather is struck is never rerolled again");
		d = AutoCroesusRules.menu(List.of(new AutoCroesusRules.MenuRun(15, true)), Set.of(), -1);
		require(!AutoCroesusRules.Run.from(d).getKismetted(), "an untouched run may still be rerolled");
	}

	private static void checkFloors() {
		require("M7".equals(AutoCroesusRules.floorCode("Master Catacombs - Floor VII")), "master floor 7");
		require("F3".equals(AutoCroesusRules.floorCode("Catacombs - Floor III")), "floor 3");
		require("E".equals(AutoCroesusRules.floorCode("Catacombs - Entrance")), "the entrance");
		require(AutoCroesusRules.floorAllowed("M7", "m6, M7"), "a listed floor is allowed, any case");
		require(!AutoCroesusRules.floorAllowed("F7", "M7"), "an unlisted floor is not");
		require(AutoCroesusRules.floorAllowed("F1", " "), "an empty list allows every floor");
	}

	private static void checkClaimFlow() {
		List<CroesusRules.ChestResult> chests = List.of(chest("Bedrock", 5_000_000, 15), chest("Obsidian", 3_000_000, 14),
			chest("Diamond", 100_000, 13));
		AutoCroesusRules.Run run = new AutoCroesusRules.Run();
		AutoCroesusRules.Settings s = settings(0, true, false, "");
		AutoCroesusRules.Decision d = AutoCroesusRules.preview(chests, run, s, "M7", 49);
		require(d.getAction() == AutoCroesusRules.Action.CHEST && d.getSlot() == 15, "the best chest is opened first");

		d = AutoCroesusRules.chest(run, 15, 31, 50, true, 49);
		require(d.getAction() == AutoCroesusRules.Action.CLAIM && d.getSlot() == 31, "the chest is claimed");
		AutoCroesusRules.afterClick(run, d);
		require(run.getClaimedSlot() == 15 && run.getGoal() == AutoCroesusRules.Goal.NONE, "the claim is remembered");
		d = AutoCroesusRules.chest(run, 15, 31, 50, true, 49);
		require(d.getAction() == AutoCroesusRules.Action.BACK && d.getSlot() == 49, "after claiming, back to the preview");

		d = AutoCroesusRules.preview(chests, run, s, "M7", 49);
		require(d.getAction() == AutoCroesusRules.Action.CHEST && d.getSlot() == 14, "the key chest is the next best above the key minimum");
		d = AutoCroesusRules.chest(run, 14, 31, 50, true, 49);
		AutoCroesusRules.afterClick(run, d);
		require(run.getKeyed() && run.getClaimedSlot() == 15, "the key chest does not replace the claimed chest");
		d = AutoCroesusRules.preview(chests, run, s, "M7", 49);
		require(d.getAction() == AutoCroesusRules.Action.BACK, "with both opened the run is done");

		AutoCroesusRules.Run noKeys = new AutoCroesusRules.Run();
		noKeys.setClaimedSlot(15);
		d = AutoCroesusRules.preview(chests, noKeys, settings(0, false, false, ""), "M7", 49);
		require(d.getAction() == AutoCroesusRules.Action.BACK, "without keys one chest per run");
		AutoCroesusRules.Run cheap = new AutoCroesusRules.Run();
		cheap.setClaimedSlot(15);
		d = AutoCroesusRules.preview(List.of(chest("Bedrock", 5_000_000, 15), chest("Gold", 1_200_000, 12)), cheap, s, "M7", 49);
		require(d.getAction() == AutoCroesusRules.Action.BACK, "a key chest below the key minimum after the key price is left");
	}

	private static void checkSkipAndKismet() {
		AutoCroesusRules.Decision d = AutoCroesusRules.preview(List.of(chest("Wood", 20_000, 10)), new AutoCroesusRules.Run(),
			settings(100_000, false, false, ""), "F1", 49);
		require(d.getAction() == AutoCroesusRules.Action.SKIP && d.getSlot() == 49, "a run below the minimum is skipped");

		List<CroesusRules.ChestResult> chests = List.of(chest("Bedrock", 900_000, 15), chest("Obsidian", 300_000, 14));
		AutoCroesusRules.Run run = new AutoCroesusRules.Run();
		AutoCroesusRules.Settings s = settings(0, false, true, "M7");
		d = AutoCroesusRules.preview(chests, run, s, "F7", 49);
		require(d.getSlot() == 15 && run.getGoal() == AutoCroesusRules.Goal.CLAIM, "no kismet on a floor that is not listed");

		run = new AutoCroesusRules.Run();
		d = AutoCroesusRules.preview(chests, run, s, "M7", 49);
		require(run.getGoal() == AutoCroesusRules.Goal.KISMET && d.getSlot() == 15, "a poor bedrock on a listed floor is rerolled");
		d = AutoCroesusRules.chest(run, 15, 31, 50, true, 49);
		require(d.getAction() == AutoCroesusRules.Action.KISMET && d.getSlot() == 50, "the kismet button is clicked");
		AutoCroesusRules.afterClick(run, d);
		require(run.getKismetted(), "one reroll per run");
		d = AutoCroesusRules.preview(chests, run, s, "M7", 49);
		require(run.getGoal() == AutoCroesusRules.Goal.CLAIM, "after the reroll the best chest is claimed");

		run = new AutoCroesusRules.Run();
		AutoCroesusRules.preview(chests, run, s, "M7", 49);
		d = AutoCroesusRules.chest(run, 15, 31, 50, false, 49);
		require(d.getAction() == AutoCroesusRules.Action.BACK && run.getKismetted(), "without a feather it goes back and claims instead");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
