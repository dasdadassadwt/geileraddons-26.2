package geiler.addons.client.dungeon;

import java.util.List;

/** Focused checks that Mimic lookups use the shared bounded Devonian waypoint catalog. */
public final class DungeonMimicCatalogChecks {
	private DungeonMimicCatalogChecks() { }
	public static void main(String[] args) { run(); }

	public static void run() {
		DevonianDungeonWaypointCatalog.CatalogResult catalog = DevonianDungeonWaypointCatalog.bundled();
		check(catalog.available(), "bundled Mimic coordinates load");
		equals(108L, catalog.markers().stream().filter(marker -> marker.category().equals("chest"))
			.map(DevonianDungeonWaypointCatalog.Marker::roomName).distinct().count(),
			"the shared catalog has chest rows for verified direct joins and aliases");
		List<DevonianDungeonWaypointCatalog.Marker> waterfall = catalog.find("Waterfall", "chest");
		equals(238L, catalog.markers().stream().filter(marker -> marker.category().equals("chest")).count(),
			"the shared catalog contains all joined chest positions");
		check(catalog.find("Criss-Cross", "chest").size() == 1,
			"the punctuation-only source alias resolves to the unique local room");
		check(catalog.find("Silver Sword", "chest").size() == 1
			&& catalog.find("Rail Track", "chest").size() == 1
			&& catalog.find("Rare Pillars", "chest").size() == 1,
			"the three evidence-backed source aliases are available to Mimic lookup");
		check(catalog.find("Higher Blaze", "chest").size() == 1
			&& catalog.find("Lower Blaze", "chest").size() == 1
			&& catalog.find("Blaze", "chest").isEmpty(),
			"the exact Blaze coordinate split is exposed only under the two local puzzle names");
		check(waterfall.contains(new DevonianDungeonWaypointCatalog.Marker(
			"devonian:waterfall:chest:0", "Waterfall", "chest", 34, 84, 23)),
			"a representative canonical chest coordinate is retained");
		equals(waterfall, catalog.find(" waterFALL ", "chest"), "room lookup is case-insensitive and trims the name");
		check(catalog.find("unverified room", "chest").isEmpty(), "unknown room names do not fall back to another row");
		check(catalog.find("Waterfall", "mimic").isEmpty(), "the shared catalog has no synthetic Mimic category");
		check(catalog.markers().stream().anyMatch(marker -> marker.category().equals("item"))
			&& catalog.markers().stream().anyMatch(marker -> marker.category().equals("essence")),
			"Mimic lookup shares the same catalog while other marker categories remain independent");
	}

	private static void equals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
