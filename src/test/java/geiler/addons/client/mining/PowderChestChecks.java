package geiler.addons.client.mining;

import java.util.List;

/** Offline checks for the powder chest spawn rules and their ordering. */
public final class PowderChestChecks {
	private static final long S = 1_000_000_000L;

	private PowderChestChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		PowderChestTracker t = new PowderChestTracker();
		PowderChestTracker.Kind stone = PowderChestTracker.Kind.STONE;
		PowderChestTracker.Kind air = PowderChestTracker.Kind.AIR;
		PowderChestTracker.Kind chest = PowderChestTracker.Kind.CHEST;

		require(t.onBlockChange(1, 60, 1, stone, air, 0, 5, false) == PowderChestTracker.Outcome.MINED, "mined stone is remembered");
		require(t.onBlockChange(1, 60, 1, air, chest, S, 5, false) == PowderChestTracker.Outcome.ADDED, "a chest where stone was mined is a powder chest");
		require(t.size() == 1, "one chest is tracked");
		require(t.onBlockChange(9, 60, 9, air, chest, 2 * S, 5, false) == PowderChestTracker.Outcome.REJECTED, "a chest from air with no mining is not a powder chest");
		t.onLevelUpSound(2 * S);
		require(t.onBlockChange(9, 60, 9, air, chest, 2 * S + 50_000_000L, 5, false) == PowderChestTracker.Outcome.ADDED, "the level-up sound vouches for a chest");
		require(t.onBlockChange(40, 60, 40, stone, air, 3 * S, 30, false) == PowderChestTracker.Outcome.IGNORED, "far changes are ignored");
		t.onBlockChange(3, 60, 3, stone, air, 3 * S, 5, true);
		require(t.onBlockChange(3, 60, 3, air, chest, 4 * S, 5, true) == PowderChestTracker.Outcome.REJECTED, "nearby players make an unvouched chest suspect");

		List<PowderChestTracker.Chest> chests = t.chests();
		require(chests.size() == 2 && chests.get(0).getX() == 1 && chests.get(1).getX() == 9, "chests stay in spawn order, oldest first");
		require(chests.get(0).ageNanos(11 * S) == 10 * S, "age counts from the spawn");
		require(chests.get(0).remainingNanos(11 * S) == 50 * S, "remaining is sixty seconds minus age");
		require(t.expire(30 * S) == 0, "nothing expires early");
		require(t.expire(62 * S) == 1 && t.size() == 1, "a chest expires after sixty seconds");
		require(t.onBlockChange(9, 60, 9, chest, air, 63 * S, 5, false) == PowderChestTracker.Outcome.REMOVED, "an opened chest is removed");
		require(t.size() == 0, "all gone");
		t.onBlockChange(1, 60, 1, stone, air, 64 * S, 5, false);
		t.onBlockChange(1, 60, 1, air, chest, 65 * S, 5, false);
		require(t.remove(1, 60, 1) && t.size() == 0, "a chest can be removed directly");
		t.onBlockChange(2, 60, 2, stone, air, 66 * S, 5, false);
		t.onBlockChange(2, 60, 2, air, chest, 67 * S, 5, false);
		t.clear();
		require(t.size() == 0, "clear empties the tracker");

		require(PowderChestTracker.timeColor(60 * S) == 0xFF00FF00, "a fresh chest is green");
		require(PowderChestTracker.timeColor(0) == 0xFFFF0000, "an expiring chest is red");
		require("42s".equals(PowderChestTracker.seconds(42 * S + 900_000_000L)), "seconds are whole");
		System.out.println("Powder chest checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
