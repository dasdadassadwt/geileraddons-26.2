package geiler.addons.client.dungeon.f7;

import java.util.List;
import java.util.Map;
import java.util.Random;

/** Offline checks for the Chest Roll rules: tier and floor parsing, strip building, timing and the loot file. */
public final class ChestRollChecks {
	private ChestRollChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkTiers();
		checkFloors();
		checkFloorKeys();
		checkStrip();
		checkEmptyPool();
		checkLanding();
		checkEasing();
		checkTimeline();
		checkMainItem();
		checkLootFile();
		checkContentWatch();
		checkRolledChests();
		checkPreviewLoot();
		checkPreviewMatch();
		checkCover();
	}

	/** The loot lines of a Croesus preview chest are the lines between Contents and the first blank line. */
	private static void checkPreviewLoot() {
		List<String> lines = List.of("Bedrock Chest", "Contents", "Necron's Handle", "Wither Essence x10", "", "Cost", "2,000,000 Coins");
		kotlin.ranges.IntRange range = ChestRollRules.lootLineRange(lines);
		require(range != null && range.getFirst() == 2 && range.getLast() == 3, "loot lines are 2..3: " + range);
		require(ChestRollRules.lootLineRange(List.of("Bedrock Chest", "Cost")) == null, "no Contents line has no loot");
		require(ChestRollRules.lootLineRange(List.of("Contents", "A")) == null, "an unfinished lore has no loot");
		require(ChestRollRules.lootLineRange(List.of("Contents", "")) == null, "an empty Contents block has no loot");
	}

	/** A preview chest matches an opened chest when the item names agree, ignoring counts, colour codes and book names. */
	private static void checkPreviewMatch() {
		List<String> preview = List.of("Necron's Handle", "Wither Essence x10", "Enchanted Book (Soul Eater I)");
		require(ChestRollRules.previewMatches(preview, List.of("Enchanted Book", "§6Necron's Handle", "Wither Essence")),
			"the same loot in another order and format matches");
		require(!ChestRollRules.previewMatches(preview, List.of("Necron's Handle", "Wither Essence")), "a missing item is another chest");
		require(!ChestRollRules.previewMatches(preview, List.of("Wither Catalyst", "Wither Essence", "Enchanted Book")),
			"other loot is another chest");
		require(!ChestRollRules.previewMatches(List.of(), List.of()), "empty loot never counts as rolled");
	}

	/** Before a roll starts, an unrolled chest of an enabled tier stays covered, but never longer than the time limit. */
	private static void checkCover() {
		require(ChestRollRules.covers(true, false, 0L), "a fresh chest of an enabled tier is covered");
		require(!ChestRollRules.covers(false, false, 0L), "a disabled tier is never covered");
		require(!ChestRollRules.covers(true, true, 0L), "an already rolled chest shows at once");
		require(ChestRollRules.covers(true, false, ChestRollRules.COVER_MAX_MS - 1), "the cover holds until the limit");
		require(!ChestRollRules.covers(true, false, ChestRollRules.COVER_MAX_MS), "the cover gives up at the limit");
	}

	/** A chest that was rolled once is not rolled again when reopened or viewed in Croesus; a reroll or new chest still rolls. */
	private static void checkRolledChests() {
		ChestRollRules.RolledChests rolled = new ChestRollRules.RolledChests(3, 1000L);
		require(!rolled.contains("Gold|A", 0L), "an unseen chest is not remembered");
		rolled.mark("Gold|A", 0L);
		require(rolled.contains("Gold|A", 10L), "a rolled chest is remembered across screens");
		require(!rolled.contains("Gold|B", 10L), "a rerolled chest has new content and rolls");
		require(!rolled.contains("Wood|A", 10L), "the same content in another tier is another chest");
		require(!rolled.contains("Gold|A", 1001L), "a memory older than the time limit is forgotten");
		rolled.mark("Gold|A", 2000L);
		require(rolled.contains("Gold|A", 2500L), "marking again refreshes the time");
		rolled.mark("1", 2001L);
		rolled.mark("2", 2002L);
		rolled.mark("3", 2003L);
		require(!rolled.contains("Gold|A", 2004L) && rolled.contains("3", 2004L) && rolled.size() == 3,
			"the oldest entry is dropped past the size limit");
		rolled.clear();
		require(rolled.size() == 0, "clear forgets everything");
	}

	private static void checkContentWatch() {
		ChestRollRules.ContentWatch watch = new ChestRollRules.ContentWatch();
		require(!watch.update("A"), "first sighting waits one tick");
		require(watch.update("A"), "stable content starts a roll");
		require(!watch.update("A"), "handled content does not start again");
		require(!watch.update("B"), "a reroll waits one tick");
		require(watch.update("B"), "rerolled content starts a roll");
		require(!watch.update(null), "empty content never starts");
		require(!watch.update("B"), "same content after empty waits one tick");
		require(watch.update("B"), "empty content re-arms the watch");
		require(!watch.update("C"), "flicker C");
		require(!watch.update("D"), "flicker D resets the wait");
		require(watch.update("D"), "D settles");
		watch.reset();
		require(!watch.update("D"), "a new screen resets the watch");
		require(watch.update("D"), "and rolls the same content again");
	}

	private static ChestRollRules.Card card(String name, int rarity, double weight) {
		return new ChestRollRules.Card(name, rarity, weight, "minecraft:stone");
	}

	private static void checkTiers() {
		require(ChestRollRules.tierOf("Bedrock Chest") == ChestRollRules.Tier.BEDROCK, "Bedrock Chest maps to BEDROCK");
		require(ChestRollRules.tierOf("Wood Chest") == ChestRollRules.Tier.WOOD, "Wood Chest maps to WOOD");
		require(ChestRollRules.tierOf("Gold") == ChestRollRules.Tier.GOLD, "a bare tier name maps too");
		require(ChestRollRules.tierOf("Iron Chest") == null, "Iron is not a roll tier");
		require(ChestRollRules.tierOf("Croesus") == null, "other titles are not chests");
		require(ChestRollRules.tierOf(null) == null, "null title");
		require(ChestRollRules.Tier.values().length == 6, "six tiers");
	}

	private static void checkFloors() {
		require("F7".equals(ChestRollRules.floorKey("Catacombs - Floor VII")), "Floor VII");
		require("M7".equals(ChestRollRules.floorKey("Master Catacombs - Floor VII")), "Master Floor VII");
		require("F1".equals(ChestRollRules.floorKey("Catacombs - Floor I")), "Floor I");
		require("F4".equals(ChestRollRules.floorKey("Catacombs - Floor IV")), "Floor IV");
		require("F6".equals(ChestRollRules.floorKey("Catacombs - Floor VI")), "Floor VI");
		require("F0".equals(ChestRollRules.floorKey("Catacombs - Entrance")), "Entrance");
		require(ChestRollRules.floorKey("Croesus") == null, "not a preview title");
		require(ChestRollRules.floorKey(null) == null, "null title");
	}

	private static void checkStrip() {
		List<ChestRollRules.Card> pool = List.of(card("A", 0, 50), card("B", 2, 30), card("C", 4, 1));
		ChestRollRules.Card winner = new ChestRollRules.Card("Winner", 5, 0.1, "minecraft:diamond");
		for (long seed = 0; seed < 50; seed++) {
			List<ChestRollRules.Card> strip = ChestRollRules.buildStrip(winner, pool, 52, 44, new Random(seed));
			require(strip.size() == 52, "strip has the requested size");
			require(strip.get(44) == winner, "winner sits at the landing index");
			for (int i = 0; i < strip.size(); i++) {
				if (i == 44) continue;
				ChestRollRules.Card c = strip.get(i);
				require(pool.contains(c), "filler comes from the pool: " + c.getName());
			}
		}
		int a = 0;
		int c = 0;
		for (ChestRollRules.Card card : ChestRollRules.buildStrip(winner, pool, 2000, 5, new Random(1))) {
			if (card.getName().equals("A")) a++;
			if (card.getName().equals("C")) c++;
		}
		require(a > c, "heavier cards appear more often than rare ones");
		require(c > 0, "rare filler still appears (the uniform third)");
	}

	private static void checkEmptyPool() {
		ChestRollRules.Card winner = card("Only", 1, 1);
		List<ChestRollRules.Card> strip = ChestRollRules.buildStrip(winner, List.of(), 10, 7, new Random(3));
		require(strip.size() == 10 && strip.get(7) == winner, "an empty pool still yields a strip with the winner");
		boolean threw = false;
		try {
			ChestRollRules.buildStrip(winner, List.of(), 10, 10, new Random(3));
		} catch (IllegalArgumentException expected) {
			threw = true;
		}
		require(threw, "a winner index outside the strip is rejected");
	}

	private static void checkLanding() {
		double pitch = 112;
		double width = 106;
		double center = 400;
		for (double jitter = -1.0; jitter <= 1.0; jitter += 0.25) {
			double x = ChestRollRules.landingTrackX(44, pitch, width, center, jitter);
			int under = ChestRollRules.cardUnder(x, center, pitch);
			require(under == 44, "the line stays over the winner at jitter " + jitter + " (got " + under + ")");
		}
		require(ChestRollRules.cardUnder(center - 0.0, center, pitch) == 0, "card 0 at the start");
		require(ChestRollRules.cardUnder(center - pitch * 3 - 1, center, pitch) == 3, "card index advances with the track");
		require(ChestRollRules.cardUnder(center + 50, center, pitch) == -1, "before the strip is index -1");
	}

	private static void checkEasing() {
		require(ChestRollRules.easeOut(0.0) == 0.0, "ease starts at 0");
		require(ChestRollRules.easeOut(1.0) == 1.0, "ease ends at 1");
		require(ChestRollRules.easeOut(2.0) == 1.0 && ChestRollRules.easeOut(-1.0) == 0.0, "ease clamps");
		double last = -1;
		for (int i = 0; i <= 100; i++) {
			double v = ChestRollRules.easeOut(i / 100.0);
			require(v >= last, "ease is monotonic");
			last = v;
		}
	}

	private static void checkTimeline() {
		ChestRollRules.Timeline t = new ChestRollRules.Timeline(5000, 700, 3000);
		require(t.phase(0) == ChestRollRules.Phase.SPIN, "starts spinning");
		require(t.phase(4999) == ChestRollRules.Phase.SPIN, "spin until the duration");
		require(t.phase(5000) == ChestRollRules.Phase.SETTLE, "settle after the spin");
		require(t.phase(5700) == ChestRollRules.Phase.REVEAL, "reveal after the settle");
		require(t.phase(8700) == ChestRollRules.Phase.DONE, "done after the reveal");
		require(t.skip(100) == 5700, "skip from the spin jumps to the reveal");
		require(t.skip(5100) == 5700, "skip from the settle jumps to the reveal");
		require(t.skip(6000) == 8700, "skip from the reveal ends the roll");
		require(t.skip(9000) == 9000, "skip when done changes nothing");
		require(t.spinProgress(0) == 0.0 && t.spinProgress(5000) == 1.0 && t.spinProgress(9999) == 1.0, "spin progress clamps");
		require(new ChestRollRules.Timeline(100, 0, 0).phase(100) == ChestRollRules.Phase.DONE, "zero tails end at once");
		checkRevealTiming();
	}

	/** The reveal is short by default and its length follows the slider; its fraction drives the grow, spin and text stages. */
	private static void checkRevealTiming() {
		ChestRollRules.Timeline fast = new ChestRollRules.Timeline(5000);
		require(ChestRollRules.SETTLE_MS <= 400L, "the settle after the landing is short");
		require(ChestRollRules.REVEAL_MS >= 800L && ChestRollRules.REVEAL_MS <= 2200L, "the default reveal is quick");
		require(fast.getRevealStart() == 5000 + ChestRollRules.SETTLE_MS, "the reveal follows the settle");
		require(fast.phase(fast.getRevealStart() + ChestRollRules.REVEAL_MS - 1) == ChestRollRules.Phase.REVEAL, "default reveal length");
		require(fast.phase(fast.getRevealStart() + ChestRollRules.REVEAL_MS) == ChestRollRules.Phase.DONE, "done after the default reveal");
		ChestRollRules.Timeline slow = new ChestRollRules.Timeline(5000, ChestRollRules.SETTLE_MS, 3000);
		require(slow.revealFraction(slow.getRevealStart() - 10) == 0.0, "no reveal progress before the reveal");
		require(slow.revealFraction(slow.getRevealStart() + 1500) == 0.5, "half way through the reveal");
		require(slow.revealFraction(slow.getRevealStart() + 9000) == 1.0, "reveal progress clamps at the end");
		require(new ChestRollRules.Timeline(100, 0, 0).revealFraction(500) == 1.0, "an empty reveal is complete");
		require(ChestRollRules.revealMs(1.6f) == 1600L && ChestRollRules.revealMs(0.1f) == 500L && ChestRollRules.revealMs(99f) == 4000L,
			"the slider seconds become milliseconds inside 0.5 to 4 s");
		double last = -1;
		for (int i = 0; i <= 100; i++) {
			double spin = ChestRollRules.revealSpin(i / 100.0);
			require(spin >= last && spin >= 0.0 && spin <= 1.0, "the item spin only moves forward");
			last = spin;
		}
		require(ChestRollRules.revealSpin(0.0) == 0.0 && ChestRollRules.revealSpin(ChestRollRules.SPIN_END) == 1.0, "the spin spans the first stage");
		require(ChestRollRules.revealText(0.0) == 0.0 && ChestRollRules.revealText(1.0) == 1.0, "the text fades in over the reveal");
		require(ChestRollRules.revealText(ChestRollRules.TEXT_START) == 0.0 && ChestRollRules.TEXT_START < ChestRollRules.SPIN_END,
			"the text starts before the spin ends so it overlaps");
	}

	private static void checkFloorKeys() {
		require("F7".equals(ChestRollRules.floorKeyOf("F7")) && "M3".equals(ChestRollRules.floorKeyOf("M3")), "dungeon floor labels are floor keys");
		require("F0".equals(ChestRollRules.floorKeyOf("Entrance")), "the entrance is F0 like the Croesus title");
		require(ChestRollRules.floorKeyOf(null) == null && ChestRollRules.floorKeyOf("nope") == null, "unknown labels give no key");
		require("M5".equals(ChestRollRules.pickFloor("M5", "F7")), "the dungeon floor wins over a remembered Croesus floor");
		require("F7".equals(ChestRollRules.pickFloor(null, "F7")), "the remembered Croesus floor is used outside a dungeon");
		require(ChestRollRules.pickFloor(null, null) == null, "no floor at all");
	}

	private static void checkMainItem() {
		require(ChestRollRules.mainIndex(List.of("Wither Essence x50", "Wither Catalyst", "Hot Potato Book"), List.of(100_000.0, 900_000.0, 50_000.0)) == 1,
			"the highest priced item wins");
		require(ChestRollRules.mainIndex(List.of("Wither Essence x50", "Rejuvenate I"), List.of(900_000.0, 5_000.0)) == 1,
			"essence never beats a real item");
		require(ChestRollRules.mainIndex(List.of("Wither Essence x50"), List.of(900_000.0)) == 0,
			"essence is the main item when it is all there is");
		require(ChestRollRules.mainIndex(List.of(), List.of()) == -1, "no items, no main item");
		require(ChestRollRules.mainIndex(List.of("A", "B"), List.of(0.0, 0.0)) == 0, "equal prices pick the first");
	}

	private static void checkLootFile() {
		String json = "{\"floors\":{\"F7\":{\"Bedrock\":[{\"name\":\"Necron's Handle\",\"w\":0.11,\"r\":\"LEGENDARY\",\"icon\":\"minecraft:stick\"}]}}}";
		Map<String, Map<String, List<ChestRollRules.Card>>> loot = ChestRollRules.parseLoot(json);
		ChestRollRules.Card handle = loot.get("F7").get("Bedrock").get(0);
		require(handle.getName().equals("Necron's Handle") && handle.getRarity() == 4 && handle.getWeight() == 0.11
			&& handle.getIcon().equals("minecraft:stick"), "a loot entry parses");
		require(ChestRollRules.rarityIndex("COMMON") == 0 && ChestRollRules.rarityIndex("MYTHIC") == 5 && ChestRollRules.rarityIndex("nope") == 0,
			"rarity names map to ordinals, unknown is common");
		boolean threw = false;
		try {
			ChestRollRules.parseLoot("{\"floors\":{\"F7\":{\"Bedrock\":[{\"name\":\"X\",\"w\":-1,\"r\":\"RARE\",\"icon\":\"minecraft:stone\"}]}}}");
		} catch (IllegalArgumentException expected) {
			threw = true;
		}
		require(threw, "a non-positive weight is rejected");
		String shipped = readResource("/data/chest-roll-loot.json");
		Map<String, Map<String, List<ChestRollRules.Card>>> real = ChestRollRules.parseLoot(shipped);
		int nonBooks = 0;
		int withId = 0;
		for (String floor : List.of("F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7")) {
			require(real.containsKey(floor), "the shipped file covers " + floor);
			boolean bedrockFloor = floor.charAt(1) >= '5';
			for (String tier : List.of("Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock")) {
				List<ChestRollRules.Card> pool = real.get(floor).getOrDefault(tier, List.of());
				if (tier.equals("Bedrock") && !bedrockFloor) {
					require(pool.isEmpty(), floor + " has no Bedrock chest");
					continue;
				}
				require(!pool.isEmpty(), floor + " has a " + tier + " pool");
				for (ChestRollRules.Card c : pool) {
					require(c.getWeight() > 0 && !c.getIcon().isEmpty() && !c.getName().isEmpty(), floor + "/" + tier + " entry is complete");
					if (!c.getIcon().equals("minecraft:enchanted_book")) {
						nonBooks++;
						if (!c.getId().isEmpty()) withId++;
					}
				}
			}
		}
		require(nonBooks > 0 && withId * 4 >= nonBooks * 3, "most non-book entries carry a NEU id (" + withId + " of " + nonBooks + ")");
		String idJson = "{\"floors\":{\"F7\":{\"Bedrock\":[{\"name\":\"Wither Boots\",\"w\":1,\"r\":\"EPIC\",\"icon\":\"minecraft:leather_boots\",\"id\":\"WITHER_BOOTS\"}]}}}";
		require(ChestRollRules.parseLoot(idJson).get("F7").get("Bedrock").get(0).getId().equals("WITHER_BOOTS"), "an optional NEU id parses");
		require(card("A", 0, 1).getId().isEmpty(), "a card without an id has an empty one");
		boolean handleInBedrock = real.get("F7").get("Bedrock").stream().anyMatch(c -> c.getName().equals("Necron's Handle"));
		require(handleInBedrock, "Necron's Handle is in the F7 Bedrock pool");
	}

	private static String readResource(String path) {
		try (java.io.InputStream in = ChestRollChecks.class.getResourceAsStream(path)) {
			require(in != null, "resource " + path + " exists");
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
