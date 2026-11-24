package geiler.addons.client.collection;

import geiler.addons.client.collections.FolderTree;
import geiler.addons.client.location.Island;
import geiler.addons.client.pricing.BazaarProduct;
import java.util.List;
import java.util.Map;

/** Offline checks for the Collection Tracker: sack parsing, inventory gains, rates, pricing and persistence. */
public final class CollectionTrackerChecks {
	private CollectionTrackerChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		checkSackParsing();
		checkInventoryGains();
		checkStats();
		checkPricing();
		checkTrackerAndStore();
	}

	private static void checkSackParsing() {
		require(SackChangeParser.isSackMessage("[Sacks] +128 items. (Last 30s.)"), "the sack summary is recognised");
		require(SackChangeParser.isSackMessage("[Sacks] +1 item, -12 items. (Last 30s.)"), "mixed add/remove summaries are recognised");
		require(!SackChangeParser.isSackMessage("[Sacks] hello"), "other sack lines are not summaries");
		SackChangeParser.Result result = SackChangeParser.parseHover(String.join("\n",
			"Added items:",
			"  +1,024 Enchanted Hay Bale (Farming Sack)",
			"  +3 Hearthstone (Gemstone Sack, Mining Sack)",
			"Removed items:",
			"  -12 Wheat (Farming Sack)",
			"and 4 other items."));
		require(result.getChanges().size() == 3, "every item row is read");
		require(result.getChanges().get(0).getDelta() == 1024 && "Enchanted Hay Bale".equals(result.getChanges().get(0).getItemName()),
			"thousands separators and the sack names are handled");
		require(result.getChanges().get(2).getDelta() == -12, "removals stay negative");
		require(result.getTruncated(), "the 'other items' tail is reported as truncated");
	}

	private static void checkInventoryGains() {
		InventoryGains gains = new InventoryGains();
		require(gains.observe(Map.of("WHEAT", 10L), 0L).isEmpty(), "the first snapshot is only a baseline");
		require(gains.observe(Map.of("WHEAT", 15L), 100L).equals(Map.of("WHEAT", 5L)), "an increase counts");
		require(gains.observe(Map.of("WHEAT", 3L), 200L).isEmpty(), "a decrease does not count");
		gains.suppress(300L, 1_000L);
		require(gains.observe(Map.of("WHEAT", 64L), 400L).isEmpty(), "items taken out of a container do not count");
		require(gains.observe(Map.of("WHEAT", 128L), 900L).isEmpty(), "nothing counts during the grace after a container closes");
		require(gains.observe(Map.of("WHEAT", 130L), 1_400L).equals(Map.of("WHEAT", 2L)), "counting resumes from the post-container baseline");
	}

	private static void checkStats() {
		CollectionStats stats = new CollectionStats();
		stats.tick(true, 1_000L);
		for (long t = 2_000L; t <= 121_000L; t += 1_000L) stats.tick(true, t);
		stats.record("WHEAT", 600L);
		require(stats.getSessionActiveMs() == 120_000L, "active time adds the ticked wall-clock time");
		Double rate = stats.perHour("WHEAT", false);
		require(rate != null && Math.abs(rate - 18_000.0) < 1e-6, "600 items in two minutes is 18,000 per hour");
		stats.tick(false, 200_000L);
		stats.tick(true, 300_000L);
		require(stats.getSessionActiveMs() == 120_000L, "inactive spans and long gaps add nothing");
		require(CollectionStats.perHour(10.0, 59_999L) == null, "no rate before a minute of active time");
		stats.resetSession();
		require(stats.count("WHEAT", false) == 0L && stats.count("WHEAT", true) == 600L, "a session reset keeps totals");
		stats.restoreTotal(Map.of("WHEAT", 5L, "BAD", -1L), 7L);
		require(stats.count("WHEAT", true) == 5L && stats.count("BAD", true) == 0L && stats.getTotalActiveMs() == 7L,
			"restored totals drop negative counts");
	}

	private static void checkPricing() {
		Map<String, BazaarProduct> bazaar = Map.of("WHEAT", new BazaarProduct("WHEAT", 7.0, 5.0));
		Map<String, Double> bins = Map.of("HEARTHSTONE", 1_000.0);
		require(CollectionPricing.quote("WHEAT", CollectionTracker.PriceMode.INSTANT_SELL, bazaar, bins).getPrice() == 5.0,
			"instant sell is the top buy order");
		require(CollectionPricing.quote("WHEAT", CollectionTracker.PriceMode.SELL_OFFER, bazaar, bins).getPrice() == 7.0,
			"sell offer is the lowest sell order");
		require(CollectionPricing.quote("HEARTHSTONE", CollectionTracker.PriceMode.AUTO, bazaar, bins).getPrice() == 1_000.0,
			"auto falls back to lowest BIN off the Bazaar");
		require(CollectionPricing.quote("NOPE", CollectionTracker.PriceMode.AUTO, bazaar, bins).getPrice() == null,
			"unknown items have no price rather than zero");
		require(CollectionPricing.quote("WHEAT", CollectionTracker.PriceMode.INSTANT_SELL, null, null).getPrice() == null,
			"missing data is not presented as a price");
		require("1.5k".equals(CollectionPricing.coins(1_500)) && "3.40M".equals(CollectionPricing.coins(3_400_000)), "coin text is short");
		require("1h 05m".equals(CollectionPricing.duration(3_900_000L)) && "45s".equals(CollectionPricing.duration(45_000L)),
			"durations read naturally");
	}

	private static void checkTrackerAndStore() {
		CollectionTracker tracker = new CollectionTracker(3, "Crimson");
		tracker.getItems().add("HEARTHSTONE");
		require(tracker.allowedOn(Island.HUB), "no island filter allows every island");
		tracker.getIslandFilter().setValue(true);
		tracker.getIslands().get(Island.CRIMSON_ISLE).setValue(true);
		require(tracker.allowedOn(Island.CRIMSON_ISLE) && !tracker.allowedOn(Island.HUB) && !tracker.allowedOn(Island.NONE),
			"the filter allows only ticked islands and never an unknown one");
		require(!tracker.active(Island.CRIMSON_ISLE, 10_000L), "a tracker with no gain yet is not active");
		tracker.setLastGainMs(10_000L);
		require(tracker.active(Island.CRIMSON_ISLE, 129_999L) && !tracker.active(Island.CRIMSON_ISLE, 130_000L),
			"a tracker stays active for its idle window after the last gain");
		require(!tracker.active(Island.HUB, 11_000L), "a tracker is inactive off its islands");
		tracker.getPriceMode().setValue("Lowest BIN");
		tracker.getStats().record("HEARTHSTONE", 4L);

		FolderTree folders = new FolderTree();
		FolderTree.Folder folder = folders.create("Nether", null);
		tracker.setFolderId(folder.id());
		CollectionTrackerStore.Saved saved = CollectionTrackerStore.toSaved(List.of(tracker), folders);
		String json = CollectionTrackerStore.json(saved);
		CollectionTrackerStore.Saved reread = geiler.addons.client.dungeon.ClientJsonFile.GSON.fromJson(json, CollectionTrackerStore.Saved.class);
		CollectionTracker back = CollectionTrackerStore.fromSaved(reread.trackers.get(0));
		require(back.getId() == 3 && "Crimson".equals(back.getName().value()) && folder.id().equals(back.getFolderId()),
			"name and folder survive a save");
		require(back.getItems().equals(List.of("HEARTHSTONE")) && back.getIslandFilter().value()
			&& back.getIslands().get(Island.CRIMSON_ISLE).value() && back.priceMode() == CollectionTracker.PriceMode.LOWEST_BIN,
			"items, islands and price mode survive a save");
		require(back.getStats().count("HEARTHSTONE", true) == 4L && back.getStats().count("HEARTHSTONE", false) == 0L,
			"totals persist and the session starts empty");
		require(reread.folders.size() == 1 && "Nether".equals(reread.folders.get(0).name()), "folders persist");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
