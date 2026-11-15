package geiler.addons.client.dungeon.odin;

import java.util.List;

/** Offline checks for the Spirit Leap menu map: marker placement, nearest pick and name based targeting. */
public final class SpiritLeapMapChecks {
	private SpiritLeapMapChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkFitScale();
		checkTop();
		checkShowsLeapMap();
		checkHudMapHiddenInLeapMenu();
		checkContains();
		checkMarkerPosition();
		checkNearest();
		checkTargetForPlayerName();
	}

	private static void checkFitScale() {
		// the requested scale is kept even when the map covers the cards
		require(SpiritLeapMapLogic.scale(1.5f, 100f) == 1.5f, "a map keeps the requested scale");
		require(SpiritLeapMapLogic.scale(4f, 400f) == 4f, "a big map may overlap the cards at the requested scale");
		require(SpiritLeapMapLogic.scale(Float.NaN, 100f) == 0f, "a bad scale hides the map");
		require(SpiritLeapMapLogic.scale(0f, 100f) == 0f, "a zero scale hides the map");
		require(SpiritLeapMapLogic.scale(1f, 0f) == 0f, "an empty map hides the map");
	}

	private static void checkTop() {
		// cards start at y=300, 8 px gap above them: map of 100 px sits at 192
		require(SpiritLeapMapLogic.top(100f, 300) == 192f, "the map sits just above the cards");
		// a map taller than the room stays on screen (top margin 4) and covers the cards
		require(SpiritLeapMapLogic.top(400f, 300) == 4f, "a tall map is pinned to the top and overlaps the cards");
	}

	private static void checkShowsLeapMap() {
		require(SpiritLeapMapLogic.showsLeapMap(true, false), "the leap copy shows when the HUD map is hidden");
		require(!SpiritLeapMapLogic.showsLeapMap(true, true), "the leap copy yields to a visible HUD map");
		require(!SpiritLeapMapLogic.showsLeapMap(false, false), "a disabled leap map stays hidden");
		require(!SpiritLeapMapLogic.showsLeapMap(false, true), "a disabled leap map stays hidden with a HUD map");
	}

	private static void checkHudMapHiddenInLeapMenu() {
		require(SpiritLeapMapLogic.hudMapHiddenInLeapMenu(true, true), "the HUD map yields to the leap copy inside the menu");
		require(!SpiritLeapMapLogic.hudMapHiddenInLeapMenu(true, false), "the HUD map stays when the leap copy is off");
		require(!SpiritLeapMapLogic.hudMapHiddenInLeapMenu(false, true), "the HUD map stays outside the leap menu");
	}

	private static void checkContains() {
		require(SpiritLeapMapLogic.contains(10f, 20f, 100f, 50f, 10f, 20f), "top left corner is inside");
		require(SpiritLeapMapLogic.contains(10f, 20f, 100f, 50f, 109.9f, 69.9f), "just inside the far corner");
		require(!SpiritLeapMapLogic.contains(10f, 20f, 100f, 50f, 110f, 30f), "right edge is outside");
		require(!SpiritLeapMapLogic.contains(10f, 20f, 100f, 50f, 50f, 19f), "above is outside");
		require(!SpiritLeapMapLogic.contains(10f, 20f, 0f, 50f, 10f, 20f), "an empty map contains nothing");
	}

	private static void checkMarkerPosition() {
		// origin (100, 40), scale 2, grid inset (5, 7), stride 20: cell (1, 2) with offset (0.5, 0.25)
		float[] p = SpiritLeapMapLogic.markerPosition(100f, 40f, 2f, 5, 7, 20, 1, 2, 0.5, 0.25);
		require(p[0] == 100f + (5 + 1.5f * 20) * 2f && p[1] == 40f + (7 + 2.25f * 20) * 2f,
			"marker is cell plus offset, scaled from the origin: " + p[0] + "," + p[1]);
	}

	private static void checkNearest() {
		List<SpiritLeapMapLogic.Marker> markers = List.of(
			new SpiritLeapMapLogic.Marker("Alice", 100f, 100f),
			new SpiritLeapMapLogic.Marker("Bob", 140f, 100f),
			new SpiritLeapMapLogic.Marker("Carol", 100f, 180f));
		require("Alice".equals(SpiritLeapMapLogic.nearestName(markers, 110f, 100f, 1000f)), "closest marker wins");
		require("Bob".equals(SpiritLeapMapLogic.nearestName(markers, 125f, 100f, 1000f)), "closer to bob");
		require("Carol".equals(SpiritLeapMapLogic.nearestName(markers, 100f, 170f, 1000f)), "vertical distance counts");
		require(SpiritLeapMapLogic.nearestName(markers, 300f, 300f, 50f) == null, "too far from every marker");
		require(SpiritLeapMapLogic.nearestName(List.of(), 10f, 10f, 50f) == null, "no markers, no target");
		require("Alice".equals(SpiritLeapMapLogic.nearestName(
			List.of(new SpiritLeapMapLogic.Marker("Alice", 0f, 0f), new SpiritLeapMapLogic.Marker("Bob", 0f, 0f)),
			0f, 0f, 10f)), "an exact tie keeps the first marker");
	}

	private static void checkTargetForPlayerName() {
		SpiritLeapRoster.Member alice = new SpiritLeapRoster.Member("Alice", "Mage", 100, true, false);
		SpiritLeapRoster.Member dead = new SpiritLeapRoster.Member("Bob", "Archer", 0, false, true);
		List<SpiritLeapRoster.Card> cards = List.of(new SpiritLeapRoster.Card(0, alice), new SpiritLeapRoster.Card(1, dead));
		List<SpiritLeapSelection.MenuHead> heads = List.of(
			new SpiritLeapSelection.MenuHead(11, "Alice", true), new SpiritLeapSelection.MenuHead(12, "Bob", true));
		var target = SpiritLeapSelection.targetForPlayerName(true, "Spirit Leap", "alice", cards, heads);
		require(target.isPresent() && target.get().containerSlot() == 11, "name match is case insensitive");
		require(SpiritLeapSelection.targetForPlayerName(true, "Spirit Leap", "Bob", cards, heads).isEmpty(),
			"a dead teammate is rejected");
		require(SpiritLeapSelection.targetForPlayerName(true, "Spirit Leap", "Nobody", cards, heads).isEmpty(),
			"an unknown name is rejected");
		require(SpiritLeapSelection.targetForPlayerName(false, "Spirit Leap", "Alice", cards, heads).isEmpty(),
			"a closed menu is rejected");
		require(SpiritLeapSelection.targetForPlayerName(true, "Chest", "Alice", cards, heads).isEmpty(),
			"a non leap menu is rejected");
		require(SpiritLeapSelection.targetForPlayerName(true, "Spirit Leap", null, cards, heads).isEmpty(),
			"a missing name is rejected");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
