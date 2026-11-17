package geiler.addons.client.fishing;

import java.util.List;

/** Offline checks for fishing bait reading, display text and the change / no-bait warning state machine. */
public final class FishingBaitChecks {
	private FishingBaitChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		checkAmountParsing();
		checkDisplayText();
		checkChangeWarning();
		checkNoBaitWarning();
		checkReset();
		System.out.println("Fishing bait checks passed");
	}

	private static void checkAmountParsing() {
		require(Integer.valueOf(1234).equals(FishingBaitRules.amount(List.of("Bait Remaining: 1,234", "x"))),
			"the amount is read from the Bait Remaining line, thousands separators included");
		require(Integer.valueOf(7).equals(FishingBaitRules.amount(List.of("A line", "Bait Remaining: 7"))),
			"the amount line need not be first");
		require(FishingBaitRules.amount(List.of("Some other lore", "Remaining: 5")) == null,
			"lore without a Bait Remaining line is not bait");
		require(FishingBaitRules.amount(List.of()) == null, "empty lore is not bait");
	}

	private static void checkDisplayText() {
		require("§b1,234x Whale Bait".equals(FishingBaitRules.displayText("Whale Bait", 1234)),
			"the display shows an aqua amount, then the name");
		require("§b7x Spooky Bait".equals(FishingBaitRules.displayText("Spooky Bait", 7)),
			"small amounts show without separators");
		require("§cNo Bait".equals(FishingBaitRules.displayText(null, null)), "no bait reads No Bait in red");
		require("Whale Bait".equals(FishingBaitRules.displayText("Whale Bait", null)),
			"a missing amount drops the amount and keeps the name");
	}

	private static void checkChangeWarning() {
		FishingBaitRules.Warnings state = new FishingBaitRules.Warnings();
		require(state.onUpdate(true, "Minnow") == null, "the first bait seen is not a change");
		require(state.onUpdate(true, "Minnow") == null, "the same bait again is not a change");
		FishingBaitRules.Warning change = state.onUpdate(true, "Whale");
		require(change != null && change.kind() == FishingBaitRules.Kind.CHANGED
				&& "§eBait changed!".equals(change.title())
				&& "Fishing Bait changed: Minnow §e-> Whale".equals(change.chat()),
			"switching bait warns with both names");
		FishingBaitRules.Warning toNone = state.onUpdate(true, null);
		require(toNone != null && "Fishing Bait changed: Whale §e-> None".equals(toNone.chat()),
			"running out of bait warns with None");
		FishingBaitRules.Warnings fresh = new FishingBaitRules.Warnings();
		fresh.onUpdate(true, null);
		require(fresh.onUpdate(true, "Minnow") == null, "going from no bait to bait does not warn");
	}

	private static void checkNoBaitWarning() {
		FishingBaitRules.Warnings state = new FishingBaitRules.Warnings();
		require(state.onCast() == null, "a fresh state assumes bait, so the first cast does not warn");
		state.onUpdate(true, null);
		FishingBaitRules.Warning none = state.onCast();
		require(none != null && none.kind() == FishingBaitRules.Kind.NO_BAIT
				&& "§cNo bait is used!".equals(none.title())
				&& "You're not using any fishing baits!".equals(none.chat()),
			"casting with no bait warns");
		state.onUpdate(true, "Minnow");
		require(state.onCast() == null, "casting with bait does not warn");
	}

	private static void checkReset() {
		FishingBaitRules.Warnings state = new FishingBaitRules.Warnings();
		state.onUpdate(true, "Minnow");
		state.onUpdate(false, null);
		require(state.onUpdate(true, "Whale") == null, "putting the rod away forgets the last bait");
		require(state.onCast() == null, "holding bait after a rod swap is not no-bait");
		state.onUpdate(false, null);
		require(state.onCast() != null, "a rod put away leaves the next cast flagged until bait is seen");
		state.reset();
		require(state.onCast() == null, "a world change returns to the assume-bait state");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
