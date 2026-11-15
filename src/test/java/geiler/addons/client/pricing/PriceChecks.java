package geiler.addons.client.pricing;

import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/** Offline checks for Price Display: data parsing, API ids, coin formatting and the line builder. */
public final class PriceChecks {
	private PriceChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkParsing();
		checkIds();
		checkShards();
		checkFormatting();
		checkLines();
		checkCounts();
		checkEssence();
		checkNeuIds();
		checkRecipes();
		checkNetworth();
	}

	private static void checkParsing() {
		Map<String, Double> doubles = PriceParsing.doubleMap("{\"A\": 1.5, \"B\": 2, \"C\": \"x\"}");
		require(doubles != null && doubles.size() == 2 && doubles.get("A") == 1.5 && doubles.get("B") == 2.0,
			"flat price maps keep numbers only");
		require(PriceParsing.doubleMap("<html>302</html>") == null && PriceParsing.doubleMap("[1]") == null
			&& PriceParsing.doubleMap("{") == null && PriceParsing.doubleMap(null) == null, "bad bodies are rejected");
		Map<String, Integer> motes = PriceParsing.intMap("{\"STICK\": 15}");
		require(motes != null && motes.get("STICK") == 15, "motes are ints");
		Map<String, BazaarProduct> bz = PriceParsing.bazaar(
			"{\"X\":{\"id\":\"X\",\"buyPrice\":10.5,\"sellPrice\":9.0},\"Y\":{\"id\":\"Y\",\"buyPrice\":3}}");
		require(bz != null && bz.get("X").getBuyPrice() == 10.5 && bz.get("X").getSellPrice() == 9.0
			&& bz.get("Y").getSellPrice() == null, "bazaar products with a missing side");
	}

	private static CompoundTag tag(String id) {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", id);
		return tag;
	}

	private static void checkIds() {
		require(SkyblockItemIds.apiId(tag("HYPERION")).equals("HYPERION"), "plain ids stay");
		CompoundTag book = tag("ENCHANTED_BOOK");
		CompoundTag enchants = new CompoundTag();
		enchants.putInt("sharpness", 7);
		book.put("enchantments", enchants);
		require(SkyblockItemIds.apiId(book).equals("ENCHANTMENT_SHARPNESS_7"), "books become ENCHANTMENT_NAME_LEVEL");
		CompoundTag rune = tag("RUNE");
		CompoundTag runes = new CompoundTag();
		runes.putInt("snow", 3);
		rune.put("runes", runes);
		require(SkyblockItemIds.apiId(rune).equals("SNOW_RUNE_3"), "runes become NAME_RUNE_LEVEL");
		CompoundTag potion = tag("POTION");
		potion.putString("potion", "speed");
		potion.putInt("potion_level", 8);
		potion.putBoolean("enhanced", true);
		potion.putBoolean("splash", true);
		require(SkyblockItemIds.apiId(potion).equals("SPEED_POTION_8_ENHANCED_SPLASH"), "potion id with flags");
		CompoundTag shiny = tag("ENDER_DRAGON_PET");
		shiny.putBoolean("is_shiny", true);
		require(SkyblockItemIds.apiId(shiny).equals("SHINY_ENDER_DRAGON_PET"), "shiny prefix");
		CompoundTag midas = tag("MIDAS_SWORD");
		midas.putInt("winning_bid", 60_000_000);
		require(SkyblockItemIds.apiId(midas).equals("MIDAS_SWORD_50M"), "midas bid suffix");
		CompoundTag pet = tag("PET");
		pet.putString("petInfo", "{\"type\":\"ENDERMAN\",\"tier\":\"LEGENDARY\",\"exp\":0.0}");
		require(SkyblockItemIds.apiId(pet).equals("LVL_1_LEGENDARY_ENDERMAN"), "pet api id");
		SkyblockItemIds.Pet parsed = SkyblockItemIds.pet(pet);
		require(parsed != null && parsed.getNeuName().equals("ENDERMAN;4") && parsed.getExp() == 0.0, "pet neu name");
		require(SkyblockItemIds.pet(tag("HYPERION")) == null, "non pets have no pet info");
	}

	private static void checkShards() {
		Map<String, String> shards = PriceParsing.shardNames(
			"{\"attributes\":[{\"displayName\":\"Fenlord\",\"bazaarName\":\"SHARD_FENLORD\"},{\"displayName\":\"Grove\"},{\"bazaarName\":\"X\"}]}");
		require(shards != null && shards.size() == 1 && "SHARD_FENLORD".equals(shards.get("Fenlord")), "shard names parse");
		require(PriceParsing.shardNames("<html>") == null && PriceParsing.shardNames("{\"attributes\":5}") == null
			&& PriceParsing.shardNames(null) == null, "bad shard bodies are rejected");
		require("SHARD_FENLORD".equals(SkyblockItemIds.shardApiId("Fenlord Shard", shards)), "shard name");
		require("SHARD_FENLORD".equals(SkyblockItemIds.shardApiId("Fenlord Shard x3", shards)), "chest count suffix");
		require("SHARD_FENLORD".equals(SkyblockItemIds.shardApiId("BUY Fenlord Shard", shards)), "bazaar order prefix");
		require("SHARD_FENLORD".equals(SkyblockItemIds.shardApiId("Fenlord", shards)), "hunting box name without Shard");
		require(SkyblockItemIds.shardApiId("Mystery Shard", shards) == null, "unknown shard");
		PriceSnapshot snapshot = new PriceSnapshot(null, null, null,
			Map.of("SHARD_FENLORD", new BazaarProduct("SHARD_FENLORD", 150_000.0, 140_000.0)), null, null, null);
		List<String> lines = PriceLines.build(snapshot, all("BOTH"), new PriceItem("ATTRIBUTE_SHARD", "SHARD_FENLORD", 1, null));
		require(lines.size() == 2 && lines.get(0).startsWith("§6Bazaar Buy Price:"), "shard bazaar lines " + lines);
	}

	private static void checkFormatting() {
		require(PriceLines.coins(1234.56, 1).equals("§31,234.6 Coins"), "single item: " + PriceLines.coins(1234.56, 1));
		require(PriceLines.coins(100.0, 64).equals("§36,400.0 Coins §7(100.0 each)"), "stack shows total and each");
		require(PriceLines.coins(6400.0, 64, true).equals("§36,400.0 Coins §7(100.0 each)"), "pre counted totals");
		require(PriceLines.motes(105, 1).equals("§3105 Motes"), "one item in motes");
		require(PriceLines.motes(105, 2).equals("§3210 Motes §7(105 each)"), "stack in motes");
	}

	private static PriceOptions all(String average) {
		return new PriceOptions(true, true, true, true, average, true, true);
	}

	private static void checkLines() {
		PriceSnapshot snapshot = new PriceSnapshot(
			Map.of("COBBLESTONE", 1.0), Map.of("ENDERMAN;4", 1_000_000.0), Map.of("STICK", 15),
			Map.of("ENCHANTED_DIAMOND", new BazaarProduct("ENCHANTED_DIAMOND", 1700.0, null)),
			Map.of("HYPERION", 900_000_000.0, "ENCHANTED_DIAMOND", 5.0),
			Map.of("HYPERION", 880_000_000.0), Map.of("HYPERION", 870_000_000.0));
		List<String> hyperion = PriceLines.build(snapshot, all("BOTH"),
			new PriceItem("HYPERION", "HYPERION", 1, null));
		require(hyperion.equals(List.of(
			"§6Lowest BIN Price: §r§3900,000,000.0 Coins",
			"§61 Day Avg. Price: §r§3880,000,000.0 Coins",
			"§63 Day Avg. Price: §r§3870,000,000.0 Coins")), "auction item lines " + hyperion);
		List<String> three = PriceLines.build(snapshot, all("THREE_DAY"), new PriceItem("HYPERION", "HYPERION", 1, null));
		require(three.size() == 2 && three.get(1).contains("3 Day"), "three day only");
		List<String> bazaar = PriceLines.build(snapshot, all("BOTH"),
			new PriceItem("ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND", 2, null));
		require(bazaar.equals(List.of(
			"§6Bazaar Buy Price: §r§33,400.0 Coins §7(1,700.0 each)",
			"§6Bazaar Sell Price: §r§cNo data")), "bazaar items skip lowest bin and show no data " + bazaar);
		List<String> cobble = PriceLines.build(snapshot, all("BOTH"), new PriceItem("COBBLESTONE", "COBBLESTONE", 64, null));
		require(cobble.equals(List.of("§eNPC Sell Price: §r§364.0 Coins §7(1.0 each)")), "npc price " + cobble);
		List<String> george = PriceLines.build(snapshot, all("BOTH"),
			new PriceItem("PET", "LVL_1_LEGENDARY_ENDERMAN", 1, new SkyblockItemIds.Pet("ENDERMAN", "LEGENDARY", 0.0)));
		require(george.equals(List.of("§ePet Sell Price: §r§31,000,000.0 Coins")), "level one pet " + george);
		List<String> leveled = PriceLines.build(snapshot, all("BOTH"),
			new PriceItem("PET", "LVL_1_LEGENDARY_ENDERMAN", 1, new SkyblockItemIds.Pet("ENDERMAN", "LEGENDARY", 50.0)));
		require(leveled.isEmpty(), "George only pays for pets without experience");
		List<String> stick = PriceLines.build(snapshot, all("BOTH"), new PriceItem("STICK", "STICK", 2, null));
		require(stick.equals(List.of("§dMotes Price: §r§330 Motes §7(15 each)")), "motes " + stick);
		PriceOptions none = new PriceOptions(false, false, false, false, "BOTH", false, false);
		require(PriceLines.build(snapshot, none, new PriceItem("HYPERION", "HYPERION", 1, null)).isEmpty(),
			"disabled settings add no lines");
		require(PriceLines.build(new PriceSnapshot(null, null, null, null, null, null, null), all("BOTH"),
			new PriceItem("HYPERION", "HYPERION", 1, null)).isEmpty(), "unloaded data adds no lines");
	}

	private static void checkCounts() {
		require(PriceLines.displayedCount("Enchanted Diamond", List.of("Diamond Sack", "", "Stored: 1,234/20k"), "x").equals(1234),
			"sack stored amount");
		require(PriceLines.displayedCount("Rough Ruby Gemstone", List.of("Gemstones", "x", " Amount: 5,000"), "x").equals(5000),
			"gemstone sack amount");
		require(PriceLines.displayedCount("Item", List.of(), "Item x640").equals(640), "stash amount");
		require(PriceLines.displayedCount("Composter", List.of("Compost Available: 12,000"), "Composter").equals(12000),
			"compost amount");
		require(PriceLines.displayedCount("Stone", List.of("a"), "Stone") == null, "no amount means the stack size");
	}

	private static void checkNetworth() {
		PriceSnapshot data = new PriceSnapshot(null, null, null,
			Map.of("A", new BazaarProduct("A", 10.0, 5.0)), Map.of("A", 1.0, "B", 2.0, "C", 3.0),
			null, Map.of("B", 20.0, "A", 99.0));
		require(NetworthSupport.priceOf(data, "A") == 10.0 && NetworthSupport.priceOf(data, "B") == 20.0
			&& NetworthSupport.priceOf(data, "C") == 3.0 && NetworthSupport.priceOf(data, "D") == 0.0,
			"value prices: bazaar buy, then 3-day average, then lowest BIN");
		require(NetworthSupport.estimate(tag("HYPERION"), 1, id -> 1.0) == 0.0, "no item data means no estimate");
		require(NetworthSupport.load("{\"items\": 3}") == null && NetworthSupport.load("nope") == null, "bad item data is rejected");
	}

	private static void checkEssence() {
		Map<String, Long> sell = Map.of("WITHER", 400L, "GOLD", 0L);
		require("§bEssence Cost: §r§3600,000 coins §7(400 each)".equals(
			PriceLines.essenceLine("Cost 1,500 Wither Essence Click to buy", sell)), "essence cost line");
		require(PriceLines.essenceLine("Cost 5 Gold Essence", sell) == null, "zero price gives no line");
		require(PriceLines.essenceLine("Cost 5 Plaid Essence", sell) == null, "unknown essence gives no line");
		require(PriceLines.essenceLine("nothing", sell) == null, "no cost text gives no line");
		require("§6Crafting Price: §r§3150.0 Coins".equals(PriceLines.craftLine(150.0, 1)) && PriceLines.craftLine(-1.0, 1) == null,
			"craft line");
	}

	private static void checkNeuIds() {
		CompoundTag book = tag("ENCHANTED_BOOK");
		CompoundTag enchants = new CompoundTag();
		enchants.putInt("sharpness", 7);
		book.put("enchantments", enchants);
		require(SkyblockItemIds.neuName(book).equals("SHARPNESS;7"), "book neu name");
		CompoundTag pet = tag("PET");
		pet.putString("petInfo", "{\"type\":\"ENDERMAN\",\"tier\":\"EPIC\",\"exp\":10.0}");
		require(SkyblockItemIds.neuName(pet).equals("ENDERMAN;3"), "pet neu name");
		require(SkyblockItemIds.neuName(tag("A:B")).equals("A-B"), "colons become dashes");
	}

	private static void checkRecipes() {
		try {
			checkRecipesChecked();
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void checkRecipesChecked() throws java.io.IOException {
		String grid = "{\"internalname\":\"ENCHANTED_DIAMOND_BLOCK\",\"recipe\":{\"A1\":\"\",\"A2\":\"ENCHANTED_DIAMOND:32\","
			+ "\"B2\":\"ENCHANTED_DIAMOND:32\",\"count\":1}}";
		kotlin.Pair<String, Recipe> parsed = NeuRecipes.parseItem(grid);
		require(parsed != null && parsed.getFirst().equals("ENCHANTED_DIAMOND_BLOCK")
			&& parsed.getSecond().getInputs().get("ENCHANTED_DIAMOND") == 64.0 && parsed.getSecond().getOutput() == 1.0,
			"crafting grid merges identical ingredients");
		kotlin.Pair<String, Recipe> forge = NeuRecipes.parseItem(
			"{\"internalname\":\"X\",\"recipes\":[{\"type\":\"trade\",\"inputs\":[\"A:1\"]},"
				+ "{\"type\":\"forge\",\"inputs\":[\"GOLD:2\",\"SKYBLOCK_COIN:100\"],\"count\":2}]}");
		require(forge != null && forge.getSecond().getOutput() == 2.0 && forge.getSecond().getInputs().size() == 2,
			"forge recipes count, trades are skipped");
		require(NeuRecipes.parseItem("{\"internalname\":\"Y\",\"recipes\":[{\"type\":\"trade\"}]}") == null
			&& NeuRecipes.parseItem("nope") == null, "items without a usable recipe give nothing");

		Map<String, Recipe> recipes = Map.of(
			"BLOCK", new Recipe(1.0, Map.of("DIAMOND", 4.0, "SKYBLOCK_COIN", 10.0)),
			"DIAMOND", new Recipe(1.0, Map.of("COAL", 100.0)));
		Map<String, BazaarProduct> bazaar = Map.of(
			"DIAMOND", new BazaarProduct("DIAMOND", 50.0, 40.0), "COAL", new BazaarProduct("COAL", 1.0, 0.5));
		NeuRecipes.Calculator sell = new NeuRecipes.Calculator(recipes, bazaar, Map.of(), false);
		// diamond: buy 40 vs craft 100 * 0.5 = 50 -> 40; block = 4 * 40 + 10 coins
		require(Math.abs(sell.itemCost("BLOCK") - 170.0) < 1e-9, "sell order prices ingredients: " + sell.itemCost("BLOCK"));
		NeuRecipes.Calculator buy = new NeuRecipes.Calculator(recipes, bazaar, Map.of(), true);
		// diamond: buy 50 vs craft 100 * 1 = 100 -> 50
		require(Math.abs(buy.itemCost("BLOCK") - 210.0) < 1e-9, "buy order prices ingredients: " + buy.itemCost("BLOCK"));
		require(sell.itemCost("COAL") == -1.0, "no recipe means no craft cost");

		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
			zip.putNextEntry(new java.util.zip.ZipEntry("REPO-master/items/ENCHANTED_DIAMOND_BLOCK.json"));
			zip.write(grid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new java.util.zip.ZipEntry("REPO-master/other/README.json"));
			zip.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		Map<String, Recipe> fromZip = NeuRecipeStore.parse(new java.io.ByteArrayInputStream(bytes.toByteArray()));
		require(fromZip.size() == 1 && fromZip.containsKey("ENCHANTED_DIAMOND_BLOCK"), "zip parsing keeps items/*.json only");
		Map<String, Recipe> round = NeuRecipeStore.decode(NeuRecipeStore.encode(fromZip));
		require(round != null && round.get("ENCHANTED_DIAMOND_BLOCK").getInputs().get("ENCHANTED_DIAMOND") == 64.0,
			"recipe cache round trips");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
