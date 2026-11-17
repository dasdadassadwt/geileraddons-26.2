package geiler.addons.client.mining;

import java.util.List;
import java.util.Map;

/** Offline checks for Mineshaft detection, corpse helmets and the visit timer. */
public final class MineshaftChecks {
	private static final long S = 1_000_000_000L;

	private MineshaftChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		require("Topaz 1".equals(MineshaftRules.shaftName(List.of("SKYBLOCK", "⏣ TOPA_1", "Purse: 5"))), "the scoreboard code names the shaft");
		require("Aquamarine Crystal".equals(MineshaftRules.shaftName(List.of("⏣ AQUA_C"))), "crystal shafts are named");
		require(MineshaftRules.shaftName(List.of("⏣ Dwarven Mines")) == null, "an ordinary area is not a shaft");
		require(MineshaftRules.shaftName(List.of()) == null, "an empty sidebar is unknown");

		require(MineshaftRules.corpseForHelmet("LAPIS_ARMOR_HELMET") == MineshaftRules.Corpse.LAPIS, "the lapis helmet marks a lapis corpse");
		require(MineshaftRules.corpseForHelmet("MINERAL_HELMET") == MineshaftRules.Corpse.TUNGSTEN, "the mineral helmet marks tungsten");
		require(MineshaftRules.corpseForHelmet("ARMOR_OF_YOG_HELMET") == MineshaftRules.Corpse.UMBER, "the Yog helmet marks umber");
		require(MineshaftRules.corpseForHelmet("VANGUARD_HELMET") == MineshaftRules.Corpse.VANGUARD, "the vanguard helmet marks vanguard");
		require(MineshaftRules.corpseForHelmet("DIAMOND_HELMET") == null && MineshaftRules.corpseForHelmet(null) == null, "other heads are not corpses");
		require(MineshaftRules.corpseByLabel(" umber ") == MineshaftRules.Corpse.UMBER, "a tab label maps to a corpse");

		Map<String, Boolean> rows = MiningTabText.corpses(List.of("Area: Mineshaft", "Frozen Corpses:", " Lapis: NOT LOOTED", " Tungsten: LOOTED", "Pet: None"));
		require(rows.size() == 2 && Boolean.FALSE.equals(rows.get("Lapis")) && Boolean.TRUE.equals(rows.get("Tungsten")), "corpse rows carry their looted state");
		require(MiningTabText.corpses(List.of("Powders:")).isEmpty(), "no widget means no corpses");

		MineshaftTimerState timer = new MineshaftTimerState();
		require(!timer.active() && timer.elapsed(5 * S) == 0, "idle timers read zero");
		timer.enter(10 * S);
		timer.setShaft("Jade 1");
		require(timer.active() && "Jade 1".equals(timer.shaft()) && timer.elapsed(70 * S) == 60 * S, "a visit counts from entry");
		timer.enter(20 * S);
		require(timer.elapsed(70 * S) == 60 * S, "re-entering a running visit does not restart it");
		require(timer.leave(130 * S) == 120 * S && !timer.active(), "leaving returns the visit length");
		require(timer.best() == 120 * S && timer.last() == 120 * S, "the first visit is the best");
		timer.enter(200 * S);
		timer.leave(260 * S);
		require(timer.best() == 60 * S && timer.last() == 60 * S, "a shorter visit becomes the best");
		timer.enter(300 * S);
		timer.leave(500 * S);
		require(timer.best() == 60 * S && timer.last() == 200 * S, "a longer visit keeps the best");
		require(timer.leave(600 * S) == null, "leaving twice is ignored");
		timer.reset();
		require(timer.best() == null && timer.last() == null, "reset forgets the records");
		require("2:05".equals(MineshaftRules.clock(125 * S)) && "1:01:01".equals(MineshaftRules.clock(3661 * S)), "clock formats minutes and hours");
		System.out.println("Mineshaft checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
