package geiler.addons.client.hud;

import geiler.addons.client.location.Island;
import java.util.Set;

/** Offline checks for the live HUD's island gate and 30 second idle expiry. */
public final class HudExpiryChecks {
	private static final long S = 1_000_000_000L;

	private HudExpiryChecks() {
	}

	public static void run() {
		Set<Island> hollows = Set.of(Island.CRYSTAL_HOLLOWS);
		require(HudExpiry.onIsland(Set.of(), Island.HUB), "an element with no islands shows anywhere");
		require(HudExpiry.onIsland(hollows, Island.CRYSTAL_HOLLOWS), "an element shows on its island");
		require(!HudExpiry.onIsland(hollows, Island.HUB), "an element hides on another known island");
		require(!HudExpiry.onIsland(hollows, Island.OTHER), "an unnamed island is still not its island");
		require(HudExpiry.onIsland(hollows, Island.NONE) && HudExpiry.onIsland(hollows, null),
			"an unknown island never hides an element");

		HudExpiry.Clock clock = new HudExpiry.Clock();
		require(!clock.idle("p", true, "a", 0), "a fresh element is not idle");
		require(!clock.idle("p", true, "a", 29 * S), "an unchanged element stays up for 30 seconds");
		require(clock.idle("p", true, "a", 31 * S), "an unchanged element hides after 30 seconds");
		require(!clock.idle("p", true, "b", 32 * S), "a changed signature brings it back");
		require(!clock.idle("p", false, "b", 33 * S) && !clock.idle("p", true, "b", 34 * S),
			"an element that stopped showing restarts its clock");
		require(!clock.idle("q", true, null, 0) && !clock.idle("q", true, null, 100 * S), "no signature never expires");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
