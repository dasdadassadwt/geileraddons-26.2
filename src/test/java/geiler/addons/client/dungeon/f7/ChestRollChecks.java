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
		checkStrip();
		checkEmptyPool();
		checkLanding();
		checkEasing();
		checkTimeline();
		checkMainItem();
		checkLootFile();
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
		require(real.containsKey("F7") && real.containsKey("M7"), "the shipped file covers F7 and M7");
		for (String tier : List.of("Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock")) {
			require(!real.get("F7").getOrDefault(tier, List.of()).isEmpty(), "F7 has a " + tier + " pool");
		}
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
