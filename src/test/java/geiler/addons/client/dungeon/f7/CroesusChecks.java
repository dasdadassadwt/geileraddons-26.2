package geiler.addons.client.dungeon.f7;

import geiler.addons.client.pricing.BazaarProduct;

import java.util.List;
import java.util.Map;

/** Offline checks for Croesus profit maths, menu state and the price file reader. */
public final class CroesusChecks {
	private CroesusChecks() { }

	public static void main(String[] args) {
		run();
	}

	private static final Map<String, Double> PRICES = Map.ofEntries(
		Map.entry("ENCHANTED_BOOK-ULTIMATE_LEGION-5", 45_000_000.0),
		Map.entry("ENCHANTED_BOOK-FEATHER_FALLING-6", 100_000.0),
		Map.entry("ESSENCE_WITHER", 2_000.0),
		Map.entry("SHARD_FENLORD", 150_000.0),
		Map.entry("NECRON_HANDLE", 550_000_000.0),
		Map.entry("WITHER_CATALYST", 12_000_000.0),
		Map.entry("RECOMBOBULATOR_3000", 6_500_000.0),
		Map.entry("SHADOW_WARP_SCROLL", 9_000_000.0));

	public static void run() {
		checkTitles();
		checkItemValues();
		checkPreviewChest();
		checkRanking();
		checkMenuState();
		checkOpenedChest();
		checkPriceFile();
		checkFormat();
		checkChestCountText();
		checkEssenceToggle();
		checkKeyCost();
		checkOdinColors();
		checkLivePrices();
	}

	private static void checkOdinColors() {
		require(CroesusRules.COLOR_FRESH == 0xFF55FF55 && CroesusRules.COLOR_OPENED == 0xFFFFAA00
			&& CroesusRules.COLOR_NONE == 0xFFFF5555 && CroesusRules.COLOR_BEST == 0xFF00AA00
			&& CroesusRules.COLOR_SECOND == 0xFFFFFF55 && CroesusRules.COLOR_KISMET == 0xFF55FFFF,
			"slot colors are Odin's opaque Minecraft colors");
	}

	private static void checkLivePrices() {
		Map<String, Double> base = Map.of("NECRON_HANDLE", 1.0, "WITHER_CATALYST", 2.0, "ENCHANTED_BOOK-ULTIMATE_LEGION-1", 3.0,
			"ONLY_AVERAGE", 4.0, "ZERO_LIVE", 5.0);
		Map<String, Double> bins = Map.of("NECRON_HANDLE", 100.0, "WITHER_CATALYST", 999.0);
		Map<String, BazaarProduct> bazaar = Map.of(
			"WITHER_CATALYST", new BazaarProduct("WITHER_CATALYST", 30.0, 20.0),
			"ENCHANTMENT_ULTIMATE_LEGION_1", new BazaarProduct("ENCHANTMENT_ULTIMATE_LEGION_1", 50.0, 40.0),
			"ZERO_LIVE", new BazaarProduct("ZERO_LIVE", 0.0, null));
		Map<String, Double> offer = CroesusPrices.live(base, bins, bazaar, true);
		require(offer.get("NECRON_HANDLE") == 100.0, "an auction item uses lowest BIN");
		require(offer.get("WITHER_CATALYST") == 30.0, "Bazaar beats lowest BIN; sell offer is the buy price");
		require(offer.get("ENCHANTED_BOOK-ULTIMATE_LEGION-1") == 50.0, "Bazaar books are found under the Croesus book key");
		require(offer.get("ONLY_AVERAGE") == 4.0 && offer.get("ZERO_LIVE") == 5.0, "a missing or zero live price keeps the 7-day value");
		require(CroesusPrices.live(base, bins, bazaar, false).get("WITHER_CATALYST") == 20.0, "instant sell is the sell price");
		require(CroesusPrices.live(base, null, null, true).equals(base), "before live data arrives the 7-day values are used");
	}

	private static void checkEssenceToggle() {
		List<String> lore = List.of("Contents", "Wither Essence x10", "Wither Catalyst", "", "Cost", "1,500,000 Coins", "");
		CroesusRules.ChestResult with = CroesusRules.previewChest(0, "Gold", lore, PRICES, true);
		CroesusRules.ChestResult without = CroesusRules.previewChest(0, "Gold", lore, PRICES, false);
		require(with != null && with.getItems().size() == 2 && with.getProfit() == 20_000.0 + 12_000_000.0 - 1_500_000.0,
			"essence counts by default");
		require(without != null && without.getItems().size() == 1 && without.getProfit() == 12_000_000.0 - 1_500_000.0,
			"Include Essence off removes the essence line and its value: " + (without == null ? "null" : without.getProfit()));
		require(CroesusRules.previewChest(0, "Gold", lore, PRICES).getProfit() == with.getProfit(), "the short overload keeps essence on");
		require(CroesusRules.parseItemValue("Wither Essence x10", PRICES, false) == null, "an excluded essence line has no price");
		List<CroesusRules.OpenedSlot> slots = List.of(
			CroesusRules.OpenedSlot.chest(List.of("Cost", "1,000,000 Coins")),
			CroesusRules.OpenedSlot.item("Wither Essence x10", null),
			CroesusRules.OpenedSlot.item("Wither Catalyst", "WITHER_CATALYST"));
		require(CroesusRules.openedChest(slots, PRICES, false).getProfit() == 12_000_000.0 - 1_000_000.0,
			"an opened chest leaves essence out when Include Essence is off");
		require(CroesusRules.openedChest(slots, PRICES).getProfit() == 20_000.0 + 12_000_000.0 - 1_000_000.0,
			"an opened chest counts essence by default");
	}

	private static void checkKeyCost() {
		CroesusRules.ChestResult a = new CroesusRules.ChestResult("A", List.of(), 100.0, 1);
		CroesusRules.ChestResult b = new CroesusRules.ChestResult("B", List.of(), 90.0, 2);
		CroesusRules.ChestResult c = new CroesusRules.ChestResult("C", List.of(), 60.0, 3);
		require(CroesusRules.topSlots(List.of(a, b, c), 0.0).equals(List.of(1, 2)), "no key price keeps the old ranking");
		require(CroesusRules.topSlots(List.of(a, b, c), 20.0).equals(List.of(1, 2)), "second still wins after the key: 90-20 > 60-20");
		require(CroesusRules.topSlots(List.of(a, b, c), 50.0).equals(List.of(1, 2)), "ordering by profit minus the same key never swaps the runners-up");
		require(CroesusRules.topSlots(List.of(a, b, c), 95.0).equals(List.of(1)), "no second pick when profit minus key is not above zero");
		require(CroesusRules.topSlots(List.of(a), 10.0).equals(List.of(1)), "a lone chest is still the best pick");
		require(CroesusRules.topSlots(List.of(a, new CroesusRules.ChestResult("N", List.of(), -5.0, 4)), 10.0).equals(List.of(1)),
			"an unprofitable chest is never highlighted");
		require(CroesusPrices.parseKey("{\"DUNGEON_CHEST_KEY\":233624.5,\"X\":1}") == 233624.5, "the key price is read from the 3-day file");
		require(CroesusPrices.parseKey("{\"X\":1}") == null && CroesusPrices.parseKey("nope") == null && CroesusPrices.parseKey(null) == null,
			"a missing or broken key price is null");
	}

	private static void checkChestCountText() {
		require("§6Chests: §a0".equals(CroesusRules.chestCountText(true, true, 0)), "zero is shown in a dungeon, as Odin does");
		require(CroesusRules.chestCountText(true, true, -1) == null, "a negative count is hidden");
		require("§6Chests: §a7".equals(CroesusRules.chestCountText(true, true, 7)), "a positive count is shown");
		require(CroesusRules.chestCountText(false, true, 7) == null && CroesusRules.chestCountText(true, false, 7) == null,
			"Chest Count needs the module on and a dungeon");
	}

	private static void checkTitles() {
		require(CroesusRules.isCroesusMenu("Croesus") && CroesusRules.isCroesusMenu("(2/3) Croesus")
			&& !CroesusRules.isCroesusMenu("Croesus Shop"), "Croesus menu titles");
		require(CroesusRules.isChestPreview("Catacombs - Floor VII") && CroesusRules.isChestPreview("Master Catacombs - F7")
			&& !CroesusRules.isChestPreview("Catacombs - Boss"), "chest preview titles");
		require(CroesusRules.isOpenedChest("Bedrock Chest") && CroesusRules.isOpenedChest("Wood") && CroesusRules.isOpenedChest("Diamond Chest")
			&& !CroesusRules.isOpenedChest("Chest"), "opened chest titles");
	}

	private static void checkItemValues() {
		require(CroesusRules.parseItemValue("Enchanted Book (Legion V)", PRICES) == 45_000_000.0, "ultimate book price");
		require(CroesusRules.parseItemValue("Enchanted Book (Feather Falling VI)", PRICES) == 100_000.0, "normal book price");
		require(CroesusRules.parseItemValue("Wither Essence x10", PRICES) == 20_000.0, "essence price times quantity");
		require(CroesusRules.parseItemValue("Fenlord Shard", PRICES) == 150_000.0, "shard price");
		require(CroesusRules.parseItemValue("Necron's Handle", PRICES) == 550_000_000.0, "replacement table");
		require(CroesusRules.parseItemValue("Shadow Warp", PRICES) == 9_000_000.0, "scroll replacement");
		require(CroesusRules.parseItemValue("Wither Catalyst", PRICES) == 12_000_000.0, "plain name to id");
		require(CroesusRules.parseItemValue("Recombobulator 3000", PRICES) == 6_500_000.0, "name with digits to id");
		require(CroesusRules.parseItemValue("Unknown Thing", PRICES) == null, "unknown items have no price");
	}

	private static void checkPreviewChest() {
		List<String> lore = List.of("Contents", "Enchanted Book (Legion V)", "Wither Catalyst", "", "Cost", "1,500,000 Coins", "");
		CroesusRules.ChestResult chest = CroesusRules.previewChest(3, "Bedrock Chest", lore, PRICES);
		require(chest != null && chest.getSlot() == 3 && chest.getItems().size() == 2, "preview chest items");
		require(chest.getProfit() == 45_000_000.0 + 12_000_000.0 - 1_500_000.0, "profit is contents minus cost: " + chest.getProfit());
		require(CroesusRules.previewChest(0, "x", List.of("No contents here"), PRICES) == null, "no Contents line is no chest");
		CroesusRules.ChestResult noCost = CroesusRules.previewChest(1, "Wood", List.of("Contents", "Wither Catalyst", "", "x"), PRICES);
		require(noCost != null && noCost.getProfit() == 12_000_000.0, "missing cost counts as zero");
		CroesusRules.ChestResult noEnd = CroesusRules.previewChest(1, "Wood", List.of("Contents", "Wither Catalyst"), PRICES);
		require(noEnd == null, "an unfinished lore (no blank line) is ignored");
	}

	private static void checkRanking() {
		CroesusRules.ChestResult a = new CroesusRules.ChestResult("A", List.of(), 5.0, 1);
		CroesusRules.ChestResult b = new CroesusRules.ChestResult("B", List.of(), 50.0, 2);
		CroesusRules.ChestResult c = new CroesusRules.ChestResult("C", List.of(), -3.0, 3);
		CroesusRules.ChestResult d = new CroesusRules.ChestResult("D", List.of(), 20.0, 4);
		require(CroesusRules.topSlots(List.of(a, b, c, d)).equals(List.of(2, 4)), "the two most profitable chests, best first");
		require(CroesusRules.topSlots(List.of(c)).isEmpty(), "no profitable chest, no highlight");
		require(CroesusRules.sorted(List.of(a, b, c, d)).get(0).getName().equals("B"), "sorted by profit");
	}

	private static void checkMenuState() {
		CroesusRules.MenuState opened = CroesusRules.menuState(List.of("Opened Chest: Diamond"), true, false, false);
		require(opened.getHide() && opened.getColor() == 0, "an opened run is hidden when Hide Claimed is on");
		CroesusRules.MenuState shown = CroesusRules.menuState(List.of("Opened Chest: Diamond"), false, false, false);
		require(!shown.getHide() && shown.getColor() == CroesusRules.COLOR_OPENED, "an opened run is gold when shown");
		CroesusRules.MenuState none = CroesusRules.menuState(List.of("No more chests to open!"), false, false, false);
		require(none.getColor() == CroesusRules.COLOR_NONE, "no more chests is red");
		CroesusRules.MenuState fresh = CroesusRules.menuState(List.of("Something"), true, false, false);
		require(!fresh.getHide() && fresh.getColor() == CroesusRules.COLOR_FRESH, "an unopened run is green");
		CroesusRules.MenuState key = CroesusRules.menuState(List.of("Opened Chest: Gold"), true, true, false);
		require(!key.getHide(), "Include Key keeps a run whose key is not struck through");
		CroesusRules.MenuState keyStruck = CroesusRules.menuState(List.of("Opened Chest: Gold"), true, true, true);
		require(keyStruck.getHide(), "Include Key hides a run once the key is struck through");
		CroesusRules.MenuState off = CroesusRules.menuState(List.of("Opened Chest: Gold"), false, false, false, false);
		require(!off.getHide() && off.getColor() == 0, "Highlight State off draws nothing");
	}

	private static void checkOpenedChest() {
		List<CroesusRules.OpenedSlot> slots = List.of(
			CroesusRules.OpenedSlot.chest(List.of("Cost", "1,000,000 Coins")),
			CroesusRules.OpenedSlot.book("Enchanted Book", "LEGION", 5),
			CroesusRules.OpenedSlot.item("Wither Essence x10", null),
			CroesusRules.OpenedSlot.item("Wither Catalyst", "WITHER_CATALYST"),
			CroesusRules.OpenedSlot.empty());
		CroesusRules.ChestResult result = CroesusRules.openedChest(slots, PRICES);
		require(result.getItems().size() == 2, "unpriced books are left out: " + result.getItems().size());
		require(result.getProfit() == 20_000.0 + 12_000_000.0 - 1_000_000.0, "opened chest profit: " + result.getProfit());
		Map<String, Double> bookPrices = Map.of("ENCHANTED_BOOK-LEGION-5", 3.0);
		CroesusRules.ChestResult book = CroesusRules.openedChest(List.of(CroesusRules.OpenedSlot.book("Enchanted Book", "LEGION", 5)), bookPrices);
		require(book.getProfit() == 3.0 && book.getItems().size() == 1, "an enchanted book in an opened chest uses its enchantment id");
	}

	private static void checkPriceFile() {
		CroesusPrices.Parsed ok = CroesusPrices.parse("{\"A\":1.5,\"B\":2,\"C\":\"x\",\"D\":null}");
		require(ok.getError() == null && ok.getPrices().size() == 2 && ok.getPrices().get("A") == 1.5 && ok.getPrices().get("B") == 2.0,
			"numeric entries load, others are skipped");
		require(CroesusPrices.parse("[]").getError() != null && CroesusPrices.parse("nope").getError() != null
			&& CroesusPrices.parse(null).getError() != null, "non-object JSON is rejected");
		StringBuilder big = new StringBuilder("{");
		for (int i = 0; i < CroesusPrices.MAX_ENTRIES + 1; i++) {
			if (i > 0) big.append(',');
			big.append("\"K").append(i).append("\":1");
		}
		big.append('}');
		require(CroesusPrices.parse(big.toString()).getError() != null, "too many entries are rejected");
	}

	private static void checkFormat() {
		require("1,234,568".equals(CroesusRules.money(1_234_567.8)) && "-50".equals(CroesusRules.money(-50.0)),
			"money is grouped and rounded");
		CroesusRules.ChestResult first = new CroesusRules.ChestResult("Bedrock",
			List.of(new CroesusRules.ChestItem("Wither Catalyst", 12_000_000.0)), 11_000_000.0, 0);
		CroesusRules.ChestResult second = new CroesusRules.ChestResult("Gold", List.of(), -5.0, 1);
		List<CroesusRules.Row> rows = CroesusRules.rows(List.of(first, second), false);
		require(rows.size() == 3 && rows.get(0).getChest() == first && rows.get(0).getText().contains("§211,000,000")
			&& rows.get(1).getChest() == null && rows.get(1).getText().contains("Wither Catalyst")
			&& rows.get(2).getText().contains("§c-5"), "the list shows each chest, then its items");
		require(rows.get(0).getY() == 0 && rows.get(1).getY() == 9 && rows.get(2).getY() == 20
			&& CroesusRules.rowsHeight(rows) == 31, "Odin's spacing: 9 px lines and 2 px after each chest");
		require(CroesusRules.rows(List.of(first, second), true).size() == 2, "minimized shows only the profit");
		require(CroesusRules.fitted(List.of(first, second), false, 31).size() == 3, "a list that fits keeps its items");
		require(CroesusRules.fitted(List.of(first, second), false, 25).size() == 2, "a too tall list drops to profit only");
		require(CroesusRules.fitted(List.of(first, second), false, 12).size() == 1, "and is cut off as a last resort");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
