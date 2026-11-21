package geiler.addons.client.hunting;

import geiler.addons.client.location.SafariBiome;

import java.util.List;
import java.util.Map;

/** Offline checks for the Critter Safari tracker's pure logic: roster, chat parsing, run tally, value, trades, markers. */
public final class CritterSafariChecks {
	private CritterSafariChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		checkRoster();
		checkParser();
		checkRun();
		checkValue();
		checkTrades();
		checkMarkerRules();
		checkPriceParsing();
		checkReportsAndModules();
		System.out.println("Critter Safari checks passed");
	}

	private static void checkPriceParsing() {
		String json = "{\"success\":true,\"products\":{"
			+ "\"SHARD_FOXTROT\":{\"sell_summary\":[{\"amount\":5,\"pricePerUnit\":100.5,\"orders\":1},"
			+ "{\"amount\":1,\"pricePerUnit\":90.0,\"orders\":1}],"
			+ "\"buy_summary\":[{\"amount\":2,\"pricePerUnit\":150.0,\"orders\":1}],\"quick_status\":{\"sellPrice\":1.0}},"
			+ "\"WHEAT\":{\"sell_summary\":[],\"buy_summary\":[]},"
			+ "\"SHARD_WUMPA\":{\"sell_summary\":[],\"buy_summary\":null}}}";
		java.util.Set<String> wanted = java.util.Set.of("SHARD_FOXTROT", "SHARD_WUMPA");
		Map<String, ShardPrice> prices = ShardPriceParsing.parse(new java.io.StringReader(json), wanted);
		eq(2, prices.size(), "only wanted products are read");
		close(100.5, prices.get("SHARD_FOXTROT").getInstantSell(), "instant sell is the top buy order");
		close(150.0, prices.get("SHARD_FOXTROT").getSellOffer(), "sell offer is the cheapest sell offer");
		close(0.0, prices.get("SHARD_WUMPA").getInstantSell(), "empty book is 0");
		close(0.0, prices.get("SHARD_WUMPA").getSellOffer(), "null book is 0");
		eq(null, ShardPriceParsing.parse(new java.io.StringReader("{\"products\":{\"SHARD_FOXTROT\":"), wanted), "truncated response is null");
		eq(null, ShardPriceParsing.parse(new java.io.StringReader("not json"), wanted), "garbage is null");
	}

	private static void checkReportsAndModules() {
		SafariRun run = new SafariRun("Me", 0L);
		run.record(CritterChatParser.parse("CAPTURE! You caught a Gemzie and gained a Gemzie Shard!"));
		run.record(CritterChatParser.parse("LOOT SHARE! You received a Foxtrot Shard from Alice catching a Foxtrot!"));
		eq("Gemzie x2", CritterReports.missingName(run, CritterSpecies.byName("Gemzie")), "quota remainder shown");
		eq("Snoozle", CritterReports.missingName(run, CritterSpecies.byName("Snoozle")), "plain name");
		eq(true, CritterReports.missingLines(run).get(0).startsWith("Forest: "), "missing lists the forest first");
		eq(false, CritterReports.missingLines(run).get(0).contains("Foxtrot"), "caught species not missing");
		eq(List.of("Me: F0 C1 I0 H0", "Alice: F1 C0 I0 H0"), CritterReports.playerLines(run), "player lines");
		eq("Party 1/37 (You 1) | 2 catches | 1m 05s", CritterReports.summaryLine(run, 65_000L), "summary line");
		eq("-", CritterReports.valueText(10.0, false), "no data shows a dash");

		geiler.addons.client.module.impl.CritterTrackerModule tracker = geiler.addons.client.module.impl.CritterTrackerModule.INSTANCE;
		geiler.addons.client.module.impl.CritterMarkersModule markers = geiler.addons.client.module.impl.CritterMarkersModule.INSTANCE;
		geiler.addons.client.module.impl.CritterAlertsModule alerts = geiler.addons.client.module.impl.CritterAlertsModule.INSTANCE;
		eq("Critter Tracker", tracker.name(), "tracker name");
		eq("Critter Markers", markers.name(), "markers name");
		eq("Critter Alerts", alerts.name(), "alerts name");
		for (geiler.addons.client.module.Module module : List.of(tracker, markers, alerts)) {
			eq(geiler.addons.client.module.Category.HUNTING, module.category(), module.name() + " category");
			eq(false, module.isEnabled(), module.name() + " starts disabled");
		}
		eq("critter_progress", tracker.getProgressHud().id(), "progress panel id");
		eq("critter_missing", tracker.getMissingHud().id(), "missing panel id");
		eq("critter_trades", tracker.getTradesHud().id(), "trades panel id");
		eq(false, tracker.getProgressHud().visible(), "panels hidden while the module is off");
		eq(true, markers.collect().isEmpty(), "no markers while idle");
		eq(List.of("Bloodbat", "Duplico", "Hideonwall"), markers.boxedCritters(), "Bloodbat, Duplico and Hideonwall are boxed by default");
		eq(true, markerToggle(markers, "Hideonfloor Boxes").value(), "Hideonfloor boxes default on");
		eq(true, markerToggle(markers, "Hideonwall Boxes").value(), "Hideonwall boxes default on");
		eq(false, markerToggle(markers, "Depth Check").value(), "depth check defaults off so boxes show through terrain");

		// Discord mod-issues: a new Safari instance (new world object, same island) restarts the run; nothing else does.
		Object worldA = new Object();
		Object worldB = new Object();
		eq(true, geiler.addons.client.module.impl.CritterSafari.worldSwapped(true, true, worldA, worldB), "a new world inside the Safari restarts the run");
		eq(false, geiler.addons.client.module.impl.CritterSafari.worldSwapped(true, true, worldA, worldA), "the same world keeps the run");
		eq(false, geiler.addons.client.module.impl.CritterSafari.worldSwapped(true, true, null, worldB), "the first world seen is not a swap");
		eq(false, geiler.addons.client.module.impl.CritterSafari.worldSwapped(true, false, worldA, worldB), "no run to restart");
		eq(false, geiler.addons.client.module.impl.CritterSafari.worldSwapped(false, true, worldA, worldB), "outside the Safari the grace period decides");

		// Nearby counts: line of sight only, unless Count Through Walls is on.
		eq(true, geiler.addons.client.module.impl.CritterSafari.countsNearby(true, false), "seen critter counts");
		eq(false, geiler.addons.client.module.impl.CritterSafari.countsNearby(false, false), "hidden critter does not count by default");
		eq(true, geiler.addons.client.module.impl.CritterSafari.countsNearby(false, true), "hidden critter counts through walls");
		eq(false, tracker.nearbyThroughWalls(), "tracker starts with Count Through Walls off");
	}

	private static geiler.addons.client.module.BooleanSetting markerToggle(geiler.addons.client.module.impl.CritterMarkersModule markers, String name) {
		return markers.booleanSettings().stream().filter(s -> s.name().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError("missing Critter Markers setting " + name));
	}

	private static void checkRoster() {
		eq(37, CritterSpecies.count(), "species count");
		eq(9, CritterSpecies.inBiome(SafariBiome.FOREST).size(), "forest count");
		eq(10, CritterSpecies.inBiome(SafariBiome.HAUNTED).size(), "haunted count");
		eq(3, CritterSpecies.byName("Gemzie").getQuota(), "gemzie quota");
		eq(0, CritterSpecies.MACAW.getQuota(), "macaw has no quota");
		eq("SHARD_MANTIS_SHRIMP", CritterSpecies.byName("mantis shrimp").bazaarId(), "bazaar id derives from name");
		eq("Mantis Shrimp", CritterSpecies.findIn("You caught a Mantis Shrimp and").getName(), "findIn");
		eq(null, CritterSpecies.byName("Entry To Critter Safari"), "entrance is not a species");
	}

	private static void checkParser() {
		CritterEvent own = CritterChatParser.parse("CAPTURE! You caught a Foxtrot and gained a Foxtrot Shard!");
		eq(CritterEvent.Type.OWN_CATCH, own.getType(), "own catch type");
		eq(1, own.getShards(), "single shard");
		eq("Foxtrot", own.getCritter().getName(), "own catch species");

		CritterEvent multi = CritterChatParser.parse("CAPTURE! You caught a SPARKLING Rockmite and gained 3x Rockmite Shard!");
		eq(3, multi.getShards(), "multi shard count");
		eq(true, multi.getSparkling(), "sparkling flag");

		CritterEvent hideyho = CritterChatParser.parse("CAPTURE! You found Hideyho, and as a reward he gave you a Hideyho Shard!");
		eq("Hideyho", hideyho.getCritter().getName(), "hideyho capture");

		CritterEvent shared = CritterChatParser.parse("LOOT SHARE! You received 2x Gemzie Shard from Alice catching a Gemzie!");
		eq(CritterEvent.Type.SHARED_CATCH, shared.getType(), "shared type");
		eq("Alice", shared.getCatcher(), "shared catcher");
		eq(2, shared.getShards(), "shared shards");

		// A player named after a critter must not become the species.
		CritterEvent tricky = CritterChatParser.parse("LOOT SHARE! You received a Tepid Shard from Foxtrot catching a Tepid!");
		eq("Tepid", tricky.getCritter().getName(), "catcher name does not decide the species");

		eq(CritterEvent.Type.ATTEMPT, CritterChatParser.parse("You threw a Critter Capsule at the Mantis Shrimp!").getType(), "attempt");
		eq(CritterEvent.Type.FAILED, CritterChatParser.parse("The Foxtrot escaped your Critter Capsule!").getType(), "escaped");
		eq(null, CritterChatParser.parse("You threw a Critter Capsule at the Nothing!"), "unknown species is no event");
		eq(null, CritterChatParser.parse("Entry To Critter Safari"), "entrance banner is no event");

		eq("CAPTURE! x", CritterChatParser.clean("CAPTURE! x (3)"), "counter suffix stripped");
		eq(SafariBiome.ICY, CritterChatParser.hotspot("HOTSPOT! Your Hunting Hotspot is the Icy Biome!"), "hotspot");
		eq(null, CritterChatParser.hotspot("HOTSPOT! Your Hunting Hotspot is the Moon Biome!"), "unknown hotspot biome");
		eq(true, CritterChatParser.playerSaid("Party > [MVP+] Bob: HOTSPOT! Your Hunting Hotspot is the Icy Biome!"), "party chat is player-said");
		eq(true, CritterChatParser.playerSaid("[VIP] Bob: CAPTURE! You caught a Foxtrot"), "open chat is player-said");
		eq(false, CritterChatParser.playerSaid("[NPC] Hunter Dennis: I've got a Tepid Shard"), "NPC is the game");
		eq(false, CritterChatParser.playerSaid("CAPTURE! You caught a Foxtrot and gained a Foxtrot Shard!"), "capture is the game");
	}

	private static void checkRun() {
		SafariRun run = new SafariRun("Me", 1_000L);
		Critter gemzie = CritterSpecies.byName("Gemzie");
		Critter foxtrot = CritterSpecies.byName("Foxtrot");
		Critter snoozle = CritterSpecies.byName("Snoozle");

		run.record(CritterChatParser.parse("CAPTURE! You caught a Foxtrot and gained 2x Foxtrot Shard!"));
		run.record(CritterChatParser.parse("CAPTURE! You caught a Gemzie and gained a Gemzie Shard!"));
		run.record(CritterChatParser.parse("LOOT SHARE! You received a Gemzie Shard from Alice catching a Gemzie!"));
		run.record(CritterChatParser.parse("You threw a Critter Capsule at the Foxtrot!"));
		run.record(CritterChatParser.parse("The Foxtrot escaped your Critter Capsule!"));

		eq(2, run.ownUnique(), "own unique");
		eq(3, run.partyTotal(), "party total");
		eq(2, run.partyCatches(gemzie), "gemzie catches");
		eq(false, run.isComplete(gemzie), "gemzie needs 3 of 3");
		eq(1, run.remaining(gemzie), "one gemzie left");
		run.setUniqueOnly(true);
		eq(true, run.isComplete(gemzie), "unique-only counts first catch");
		run.setUniqueOnly(false);
		eq(List.of("Me", "Alice"), run.catchersOf(gemzie), "catchers in order");
		eq(1, run.partyUnique(), "only foxtrot is finished; gemzie is 2 of 3");
		eq(1, run.attempts(foxtrot), "attempts");
		eq(1, run.failures(foxtrot), "failures");
		eq(2, run.shardCounts().get(foxtrot), "foxtrot shards");
		eq(2, run.shardCounts().get(gemzie), "gemzie shards, own plus loot share");
		eq(4, run.totalShards(), "total shards");

		eq(false, run.isComplete(snoozle), "snoozle outstanding");
		run.setUnavailable(java.util.Set.of(snoozle));
		eq(true, run.isComplete(snoozle), "unavailable species counts as settled");
		eq(false, run.missing(SafariBiome.CAVERN).contains(snoozle), "unavailable species not missing");
		eq(true, run.partyCatches(snoozle) == 0 && run.isUnavailable(snoozle), "unavailable until caught");

		Map<String, Map<SafariBiome, Integer>> players = run.uniquePerPlayer();
		eq(List.of("Me", "Alice"), List.copyOf(players.keySet()), "players listed self first");
		eq(1, players.get("Alice").get(SafariBiome.CAVERN), "alice cavern unique");

		run.setNearby(Map.of(foxtrot, 4));
		run.setNearby(Map.of(foxtrot, 1));
		eq(1, run.nearby(foxtrot), "nearby replaced, not accumulated");
		eq(61_000L, run.elapsedMillis(62_000L), "elapsed");
		eq(false, run.isEmpty(), "run has events");
		eq(true, new SafariRun("Me", 0L).isEmpty(), "fresh run empty");
	}

	private static void checkValue() {
		Critter foxtrot = CritterSpecies.byName("Foxtrot");
		Critter wumpa = CritterSpecies.byName("Wumpa");
		Map<String, ShardPrice> prices = Map.of("SHARD_FOXTROT", new ShardPrice(100.0, 150.0));
		Map<Critter, Integer> shards = Map.of(foxtrot, 2, wumpa, 1);
		close(197.5, ShardValue.value(shards, prices, false), "instant sell with tax, unpriced species ignored");
		close(296.25, ShardValue.value(shards, prices, true), "sell offer with tax");
		eq(0.0, ShardValue.perHour(1_000.0, 59_000L), "no extrapolating under a minute");
		close(6_000.0, ShardValue.perHour(1_000.0, 600_000L), "coins per hour");
		eq("1.50M", ShardValue.coins(1_500_000.0), "millions");
		eq("12.3k", ShardValue.coins(12_345.0), "thousands");
		eq("987", ShardValue.coins(987.0), "units");
		eq("1h 02m 03s", ShardValue.duration(3_723_000L), "hours");
		eq("12m 05s", ShardValue.duration(725_000L), "minutes");
		eq("42s", ShardValue.duration(42_000L), "seconds");
	}

	private static void checkTrades() {
		TradeParser parser = new TradeParser();
		eq(null, parser.parse("[NPC] Hunter Dennis: I've got a Gimmiegold Shard you can h-h-have, if you w-w-want it..."), "offer alone is not a trade");
		eq("Hunter Dennis", parser.offerJustRegistered(), "offer registered");
		TradeOffer offer = parser.parse("[NPC] Hunter Dennis: You can h-h-have it if you give m-m-me a Purple Gem...");
		eq("Gimmiegold", offer.getCritter().getName(), "offer species");
		eq("Purple Gem", offer.getItem(), "offer item");
		eq(null, parser.parse("[NPC] Hunter Dennis: How about I give you it in exchange for a Purple Gem?"), "price without a pending offer");
		eq(null, parser.parse("[NPC] Villager: Mind the Tepid Shard in the snow"), "flavour text is no offer");

		TradeSpot spot = new TradeSpot(SafariBiome.ICY, -106, 87, -7);
		String text = TradeLines.announce(offer, spot);
		eq("Hunter Dennis (Icy -106 87 -7): Gimmiegold Shard for Purple Gem", text, "announce wording matches Critter Mod");
		TradeLines.Shared shared = TradeLines.parseShared(text);
		eq("Gimmiegold", shared.getCritter().getName(), "shared species");
		eq(-106, shared.getSpot().getX(), "shared x");
		eq(SafariBiome.ICY, shared.getSpot().getBiome(), "shared biome");
		eq(null, TradeLines.parseShared("Hunter Dennis (-106 87 -7): Gimmiegold Shard for Purple Gem").getSpot().getBiome(), "biome optional");
		eq(null, TradeLines.parseShared("hello there"), "chatter is not a trade");
		eq("Hunter Dennis (-1 2 3): Gimmiegold Shard for Purple Gem",
			TradeLines.announce(offer, new TradeSpot(null, -1, 2, 3)), "announce without biome");
		eq("Gimmiegold", TradeLines.parseTaken("Trade taken: Gimmiegold Shard").getName(), "taken line");
		eq("Trade taken: Gimmiegold Shard", TradeLines.taken(offer.getCritter()), "taken wording");
		eq("Wumpa", TradeLines.given("You have been given a Wumpa!").getName(), "given line");
		eq("Bob", TradeLines.partyLine("Party > [MVP+] Bob: hi").getFirst(), "party line name");
		eq(null, TradeLines.partyLine("Guild > Bob: hi"), "guild is not party");

		TradeBook book = new TradeBook();
		eq(true, book.add(new FoundTrade(offer, spot)), "first add");
		eq(false, book.add(new FoundTrade(offer, spot)), "duplicate ignored");
		TradeOffer far = new TradeOffer("Huntress Melissa", CritterSpecies.byName("Wumpa"), "Gold Ingot");
		book.add(new FoundTrade(far, new TradeSpot(SafariBiome.ICY, 500, 87, 500)));
		eq("Hunter Dennis", book.nearestFirst(-100, 87, -7).get(0).getOffer().getNpc(), "nearest first");
		eq(true, book.take(offer.getCritter()), "take removes");
		eq(false, book.has(offer.getCritter()), "taken gone");
		for (int i = 0; i < 100; i++) {
			book.add(new FoundTrade(new TradeOffer("N" + i, CritterSpecies.byName("Foxtrot"), "I" + i), null));
		}
		eq(true, book.all().size() <= 32, "book is bounded");
	}

	private static void checkMarkerRules() {
		eq(true, MarkerRules.shows(MarkerKind.SNOOPER_WALL, SafariBiome.CAVERN), "cavern wall in cavern");
		eq(false, MarkerRules.shows(MarkerKind.SNOOPER_WALL, SafariBiome.ICY), "cavern wall not in icy");
		eq(false, MarkerRules.shows(MarkerKind.BEE_SPOT, null), "biome markers need a biome");
		eq(true, MarkerRules.shows(MarkerKind.TRADE, null), "trades show anywhere");
		eq(true, MarkerRules.shows(MarkerKind.HIDEONFLOOR, SafariBiome.FOREST), "hideonfloor in forest");
		for (SafariBiome other : List.of(SafariBiome.CAVERN, SafariBiome.ICY, SafariBiome.HAUNTED)) {
			eq(false, MarkerRules.shows(MarkerKind.HIDEONFLOOR, other), "hideonfloor not in " + other);
		}
		eq(false, MarkerRules.shows(MarkerKind.HIDEONFLOOR, null), "hideonfloor needs a biome");

		eq(WallState.UNKNOWN, MarkerRules.wallState(WallState.UNKNOWN, false, true, false), "unloaded air is unknown");
		eq(WallState.UNKNOWN, MarkerRules.wallState(WallState.UNKNOWN, true, false, false), "unseen air is unknown");
		eq(WallState.BROKEN, MarkerRules.wallState(WallState.UNKNOWN, true, true, false), "seen air is broken");
		eq(WallState.INTACT, MarkerRules.wallState(WallState.UNKNOWN, true, false, true), "solid is intact");
		eq(WallState.BROKEN, MarkerRules.wallState(WallState.BROKEN, true, true, true), "broken is latched");
		eq(true, MarkerRules.snoozleGone(true, 0, 0), "snoozle gone");
		eq(false, MarkerRules.snoozleGone(true, 0, 1), "snoozle still nearby");

		eq(BeeStatus.UNCHECKED, MarkerRules.beeStatus(BeeStatus.UNCHECKED, false, BeeBlock.NEST), "unseen spot stays unchecked");
		eq(BeeStatus.NEST, MarkerRules.beeStatus(BeeStatus.UNCHECKED, true, BeeBlock.NEST), "seen nest");
		eq(BeeStatus.CLEARED, MarkerRules.beeStatus(BeeStatus.UNCHECKED, true, BeeBlock.HIVE), "hive crossed off");
		eq(BeeStatus.CLEARED, MarkerRules.beeStatus(BeeStatus.UNCHECKED, true, BeeBlock.OTHER), "empty crossed off");
		eq(BeeStatus.CLEARED, MarkerRules.beeStatus(BeeStatus.CLEARED, true, BeeBlock.NEST), "cleared is latched");

		eq(11, SafariPresets.BEE_SPOTS.size(), "eleven bee spots");
		eq(8, SafariPresets.HIDEONWALL_PERCHES.size(), "eight perches");
		eq(5, SafariPresets.SNOOPER_WALLS.size(), "five snooper walls");
		eq(3, SafariPresets.TROODON_WALLS.size(), "three troodon walls");
		eq(0, MarkerRules.nearestPreset(SafariPresets.BEE_SPOTS, -16, 68, 42, 2.5), "punch beside a spot");
		eq(-1, MarkerRules.nearestPreset(SafariPresets.BEE_SPOTS, 0, 0, 0, 2.5), "punch nowhere near a spot");

		eq(true, MarkerRules.isMound(0.70, 0.50, 40.0, true, false), "measured mound");
		eq(false, MarkerRules.isMound(0.70, 0.50, 80.0, true, false), "too high is not a mound");
		eq(false, MarkerRules.isMound(0.70, 0.50, 40.0, false, false), "falling fish is off-centre");
		eq(false, MarkerRules.isMound(0.70, 0.50, 40.0, true, true), "occupied box is a mob");
		eq(false, MarkerRules.isMound(2.0, 2.0, 40.0, true, false), "too big");
	}

	private static void eq(Object expected, Object actual, String what) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			throw new AssertionError(what + ": expected " + expected + " but was " + actual);
		}
	}

	private static void close(double expected, double actual, String what) {
		if (Math.abs(expected - actual) > 1e-6) {
			throw new AssertionError(what + ": expected " + expected + " but was " + actual);
		}
	}
}
