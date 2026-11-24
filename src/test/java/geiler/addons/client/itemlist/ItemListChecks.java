package geiler.addons.client.itemlist;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline checks for the Item List: repository parsers, the recipe index, search, aliases and the calculator. */
public final class ItemListChecks {
	private ItemListChecks() { }

	private static final String HYPERION = """
		{"internalname":"HYPERION","itemid":"minecraft:iron_sword","displayname":"\\u00a76Hyperion","damage":0,
		"lore":["\\u00a77Damage: \\u00a7c+260","\\u00a77Strength: \\u00a7c+150","","\\u00a76\\u00a7lLEGENDARY DUNGEON SWORD"],
		"info":["","https://wiki.hypixel.net/Hyperion"],
		"recipes":[{"type":"crafting","A1":"ENCHANTED_DIAMOND:2","A2":"ENCHANTED_DIAMOND:2","B2":"STICK","count":1}]}""";
	private static final String AOTE = """
		{"internalname":"ASPECT_OF_THE_END","itemid":"minecraft:diamond_sword","displayname":"\\u00a79Aspect of the End",
		"lore":["\\u00a77Damage: \\u00a7c+100","","\\u00a79\\u00a7lRARE SWORD"],
		"recipe":{"A1":"","A2":"ENCHANTED_ENDER_PEARL:16","B2":"ENCHANTED_DIAMOND:8","C3":"STICK:1","count":1}}""";
	private static final String MITHRIL = """
		{"internalname":"REFINED_MITHRIL","itemid":"minecraft:iron_ingot","displayname":"\\u00a7aRefined Mithril",
		"lore":["","\\u00a7a\\u00a7lUNCOMMON"],
		"recipes":[{"type":"forge","inputs":["MITHRIL_ORE:5","ENCHANTED_COAL:2"],"count":1,"duration":600}]}""";
	private static final String ZOMBIE = """
		{"internalname":"ZOMBIE_5_MONSTER","itemid":"minecraft:skull","displayname":"\\u00a7cZombie","lore":[],
		"recipes":[{"type":"drops","drops":[{"id":"ROTTEN_FLESH:1","chance":"100%"},{"id":"POTATO","chance":"5%"}],"level":5,"coins":20}]}""";
	private static final String SHOP = """
		{"internalname":"FLINT_SHOVEL","itemid":"minecraft:iron_shovel","displayname":"Flint Shovel","lore":[],
		"recipes":[{"type":"npc_shop","cost":["SKYBLOCK_COIN:200"],"result":"FLINT_SHOVEL:1"},
		{"type":"trade","cost":"FLINT:3","result":"FLINT_SHOVEL:1"}]}""";
	private static final String BEE = """
		{"internalname":"BEE;4","itemid":"minecraft:skull","displayname":"\\u00a76[Lvl {LVL}] Bee","lore":["","\\u00a76\\u00a7lLEGENDARY"],
		"recipes":[{"type":"katgrade","input":"BEE;4","output":"BEE;5","items":["PETRIFIED_STARFALL:1"],"coins":1000000,"time":24}]}""";
	private static final String BOOK = """
		{"internalname":"SHARPNESS;5","itemid":"minecraft:enchanted_book","displayname":"\\u00a79Sharpness V","lore":["","\\u00a79\\u00a7lCOMMON"]}""";
	private static final String NPC = """
		{"internalname":"BANKER_NPC","itemid":"minecraft:skull","displayname":"\\u00a7bBanker","lore":[]}""";
	private static final String ESSENCE = """
		{"HYPERION":{"type":"Wither","1":50,"2":100,"items":{"1":["WITHER_CATALYST:1"]}}}""";

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkParsers();
		checkIndex();
		checkSearch();
		checkCalculator();
		checkSlotSearch();
		checkRepo();
		checkLayout();
		checkStacks();
		checkSort();
		checkRarityColor();
		checkFallbacks();
		checkRealRepository();
	}

	/**
	 * Optional probe against a real repository zip: set GEILERADDONS_ITEM_LIST_ZIP to its path and every item is converted,
	 * timed and any failure is reported. Skipped when the variable is not set.
	 */
	private static void checkRealRepository() {
		String path = System.getenv("GEILERADDONS_ITEM_LIST_ZIP");
		if (path == null || path.isEmpty()) return;
		NeuRepo.Repo repo;
		try (java.io.InputStream in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(path))) {
			repo = NeuRepo.INSTANCE.parse(in);
		} catch (java.io.IOException exception) {
			throw new AssertionError(exception);
		}
		bindComponents();
		ItemListRepository.Compiled compiled = ItemListRepository.compile(repo, "check");
		require(compiled.getEntries().size() > repo.getEntries().size(), "vanilla items are added to the list");
		LegacyItemStacks.Store store = new LegacyItemStacks.Store(LegacyItemStacks.registries());
		long started = System.nanoTime();
		store.warm(repo.getEntries());
		long millis = (System.nanoTime() - started) / 1_000_000L;
		System.out.println("ItemListChecks real repository: " + repo.getEntries().size() + " items, " + store.getFailures() + " failures, "
			+ millis + " ms, sample " + store.getFailedSample());
		require(store.getFailures() * 100 < repo.getEntries().size(), "under one percent of real items fail to convert");
	}

	private static List<String> sorted(List<Entry> list, String mode, Map<String, Double> prices) {
		List<String> ids = new ArrayList<>();
		for (Entry e : ItemSearch.sort(list, mode, prices::get)) ids.add(e.getId());
		return ids;
	}

	private static void checkSort() {
		List<Entry> list = new ArrayList<>();
		for (String json : List.of(HYPERION, AOTE, MITHRIL)) list.add(parsed(json).getEntry());
		list.add(new Entry("DIAMOND", "Diamond", List.of(), "minecraft:diamond", 0, "", "", "", "VANILLA", null));
		Map<String, Double> prices = Map.of("HYPERION", 9e8, "REFINED_MITHRIL", 50.0);
		require(sorted(list, ItemListData.SORT_BEST, prices).equals(List.of("HYPERION", "ASPECT_OF_THE_END", "REFINED_MITHRIL", "DIAMOND")), "best match keeps the given order");
		require(sorted(list, ItemListData.SORT_NAME_ASC, prices).equals(List.of("ASPECT_OF_THE_END", "DIAMOND", "HYPERION", "REFINED_MITHRIL")), "name A-Z");
		require(sorted(list, ItemListData.SORT_NAME_DESC, prices).equals(List.of("REFINED_MITHRIL", "HYPERION", "DIAMOND", "ASPECT_OF_THE_END")), "name Z-A");
		require(sorted(list, ItemListData.SORT_PRICE_ASC, prices).equals(List.of("REFINED_MITHRIL", "HYPERION", "ASPECT_OF_THE_END", "DIAMOND")), "price low-high, unpriced last by name");
		require(sorted(list, ItemListData.SORT_PRICE_DESC, prices).equals(List.of("HYPERION", "REFINED_MITHRIL", "ASPECT_OF_THE_END", "DIAMOND")), "price high-low, unpriced last by name");
		require(ItemListData.nextSort(ItemListData.SORT_PRICE_DESC, 1).equals(ItemListData.SORT_BEST) && ItemListData.nextSort(ItemListData.SORT_BEST, -1).equals(ItemListData.SORT_PRICE_DESC)
			&& ItemListData.nextSort("junk", 1).equals(ItemListData.SORT_NAME_ASC), "sort modes cycle both ways and an unknown mode restarts");
	}

	private static void checkRarityColor() {
		require(parsed(HYPERION).getEntry().getRarityColor() == geiler.addons.client.inventory.SkyblockRarity.LEGENDARY, "rarity maps to its color");
		require(new Entry("X", "X", List.of(), "minecraft:stick", 0, "", "VERY SPECIAL", "", "MATERIAL", null).getRarityColor() == geiler.addons.client.inventory.SkyblockRarity.VERY_SPECIAL, "two-word rarity");
		require(new Entry("D", "D", List.of(), "minecraft:stick", 0, "", "", "", "VANILLA", null).getRarityColor() == null, "no rarity, no color");
	}

	/** Former skull items that NEU now lists as paper get their head back; vanilla stand-ins; unknown ids stay paper. */
	private static void checkFallbacks() {
		bindComponents();
		String model = "{ItemModel:\"hypixel_skyblock:item/abiphones/abiphone_basic\",ExtraAttributes:{id:\"X\"}}";
		Entry phone = new Entry("ABIPHONE_BASIC", "§fAbiphone Basic", List.of("§7A device", "", "§f§lCOMMON"), "minecraft:paper", 0, model, "COMMON", "", "MATERIAL", null);
		net.minecraft.world.item.ItemStack head = stack(phone);
		require(head.is(net.minecraft.world.item.Items.PLAYER_HEAD) && head.has(net.minecraft.core.component.DataComponents.PROFILE), "a paper item with a texture becomes a head");
		require(head.getHoverName().getString().equals("Abiphone Basic") && head.get(net.minecraft.core.component.DataComponents.LORE).lines().size() == 3, "the head keeps repository name and lore");
		Entry lasso = new Entry("ABYSMAL_LASSO", "§5Abysmal Lasso", List.of(), "minecraft:paper", 0, model, "EPIC", "", "MATERIAL", null);
		require(stack(lasso).is(net.minecraft.world.item.Items.LEAD), "a vanilla stand-in item");
		Entry unknown = new Entry("NOT_IN_TABLE", "Nothing", List.of(), "minecraft:paper", 0, model, "COMMON", "", "MATERIAL", null);
		require(stack(unknown).is(net.minecraft.world.item.Items.PAPER), "an id with no fallback stays paper");
		Entry sword = new Entry("ABIPHONE_BASIC", "Not a phone", List.of(), "minecraft:diamond_sword", 0, "", "COMMON", "", "MATERIAL", null);
		require(stack(sword).is(net.minecraft.world.item.Items.DIAMOND_SWORD), "only paper items are replaced");
		require(ItemFallbacks.INSTANCE.getSize() > 700 && ItemFallbacks.INSTANCE.parse("{\"A\":\"minecraft:lead\",\"B\":\"dGV4\",\"C\":5}").size() == 2, "table loads; bad values are skipped");
	}

	private static Parsed parsed(String json) {
		Parsed p = ItemListData.parseItem(json);
		require(p != null, "fixture parses: " + json.substring(0, 40));
		return p;
	}

	private static void checkParsers() {
		Parsed hyperion = parsed(HYPERION);
		Entry e = hyperion.getEntry();
		require(e.getId().equals("HYPERION") && e.getPlainName().equals("Hyperion") && e.getRarity().equals("LEGENDARY")
			&& e.getType().equals("DUNGEON SWORD") && e.getCategory().equals("WEAPON"), "item fields and category");
		require("https://wiki.hypixel.net/Hyperion".equals(e.getWikiUrl()), "wiki url skips blank info entries");
		ItemRecipe craft = hyperion.getRecipes().get(0);
		require(craft.getType().equals("crafting") && craft.getInputs().size() == 2 && craft.getInputs().get(0).getAmount() == 4.0
			&& craft.getOutputs().get(0).getId().equals("HYPERION"), "crafting grid merges equal ingredients");
		Parsed aote = parsed(AOTE);
		require(aote.getRecipes().size() == 1 && aote.getRecipes().get(0).getInputs().size() == 3, "legacy recipe object, empty cells skipped");
		ItemRecipe forge = parsed(MITHRIL).getRecipes().get(0);
		require(forge.getType().equals("forge") && "600".equals(forge.getExtra().get("duration")) && forge.getInputs().size() == 2, "forge recipe with duration");
		require(parsed(MITHRIL).getEntry().getRarity().equals("UNCOMMON") && parsed(MITHRIL).getEntry().getType().isEmpty()
			&& parsed(MITHRIL).getEntry().getCategory().equals("MATERIAL"), "rarity-only last line is a material");
		Parsed zombie = parsed(ZOMBIE);
		ItemRecipe drops = zombie.getRecipes().get(0);
		require(zombie.getEntry().getCategory().equals("MOB") && drops.getOutputs().size() == 2
			&& "100%,5%".equals(drops.getExtra().get("chances")) && drops.getInputs().get(0).getId().equals("ZOMBIE_5_MONSTER"), "mob drops");
		List<ItemRecipe> shop = parsed(SHOP).getRecipes();
		require(shop.size() == 2 && shop.get(0).getType().equals("npc_shop") && shop.get(1).getType().equals("trade")
			&& shop.get(1).getInputs().get(0).getAmount() == 3.0, "npc shop and trade");
		ItemRecipe kat = parsed(BEE).getRecipes().get(0);
		require(kat.getType().equals("katgrade") && kat.getInputs().size() == 2 && kat.getOutputs().get(0).getId().equals("BEE;5")
			&& "1000000".equals(kat.getExtra().get("coins")), "kat upgrade");
		require(parsed(BEE).getEntry().getCategory().equals("PET"), "semicolon id with a rarity is a pet");
		require(parsed(BOOK).getEntry().getCategory().equals("BOOK") && parsed(NPC).getEntry().getCategory().equals("NPC"), "book and npc categories");
		List<ItemRecipe> essence = ItemListData.parseEssence(ESSENCE);
		require(essence.size() == 2 && essence.get(0).getInputs().size() == 2 && essence.get(0).getInputs().get(0).getId().equals("ESSENCE_WITHER")
			&& "1".equals(essence.get(0).getExtra().get("star")), "essence upgrades per star");
		require(ItemListData.parseItem("{") == null && ItemListData.parseItem("[]") == null && ItemListData.parseItem("{}") == null, "bad item json");
		require(ItemListData.parseEssence("nope").isEmpty(), "bad essence json");
		require(ItemListData.rarityAndType("VERY SPECIAL").getFirst().equals("VERY SPECIAL") && ItemListData.rarityAndType("hello").getFirst().isEmpty(), "rarity line");
		Map<String, geiler.addons.client.pricing.Recipe> table = ItemListData.craftTable(allRecipes());
		geiler.addons.client.pricing.Recipe h = table.get("HYPERION");
		require(h != null && h.getOutput() == 1.0 && h.getInputs().get("ENCHANTED_DIAMOND") == 4.0 && table.containsKey("REFINED_MITHRIL")
			&& !table.containsKey("FLINT_SHOVEL"), "craft table feeds crafting cost");
		geiler.addons.client.pricing.Recipe old = geiler.addons.client.pricing.NeuRecipes.parseItem(AOTE).getSecond();
		geiler.addons.client.pricing.Recipe now = table.get("ASPECT_OF_THE_END");
		require(old.getInputs().equals(now.getInputs()) && old.getOutput() == now.getOutput(), "craft table matches the existing NEU parser");
	}

	private static List<ItemRecipe> allRecipes() {
		List<ItemRecipe> all = new ArrayList<>();
		for (String json : List.of(HYPERION, AOTE, MITHRIL, ZOMBIE, SHOP, BEE)) all.addAll(parsed(json).getRecipes());
		all.addAll(ItemListData.parseEssence(ESSENCE));
		return all;
	}

	private static void checkIndex() {
		RecipeIndex index = new RecipeIndex(allRecipes());
		require(index.usesOf("ENCHANTED_DIAMOND").size() == 2, "uses of an ingredient: hyperion and aote");
		require(index.recipesFor("HYPERION").size() == 3, "recipes for an item: crafting and two essence stars");
		require(index.recipesFor("ROTTEN_FLESH").get(0).getType().equals("drops"), "a drop lists its mob");
		require(index.recipesFor("ZOMBIE_5_MONSTER").size() == 1 && index.usesOf("ZOMBIE_5_MONSTER").isEmpty(), "a mob shows its own drops, not as a use");
		require(index.usesOf("BEE;4").size() == 1 && index.recipesFor("BEE;5").size() == 1, "kat upgrade is use of the pet and recipe of the result");
		require(index.usesOf("ESSENCE_WITHER").size() == 2, "essence is used by each star");
		require(index.recipesFor("NOTHING").isEmpty() && index.usesOf("NOTHING").isEmpty(), "unknown ids are empty");
	}

	private static ItemSearch search(java.util.function.Predicate<String> bazaar) {
		List<Entry> entries = new ArrayList<>();
		for (String json : List.of(HYPERION, AOTE, MITHRIL, ZOMBIE, SHOP, BEE, BOOK, NPC)) entries.add(parsed(json).getEntry());
		entries.add(new Entry("DIAMOND", "Diamond", List.of(), "minecraft:diamond", 0, "", "", "", "VANILLA", null));
		return new ItemSearch(entries, new RecipeIndex(allRecipes()), ItemSearch.Companion.getDEFAULT_ALIASES(), bazaar::test);
	}

	private static String first(ItemSearch s, String query) {
		List<Entry> result = s.search(query, "ALL");
		return result.isEmpty() ? "" : result.get(0).getId();
	}

	private static List<String> ids(ItemSearch s, String query, String category) {
		List<String> ids = new ArrayList<>();
		for (Entry e : s.search(query, category)) ids.add(e.getId());
		return ids;
	}

	private static void checkSearch() {
		ItemSearch s = search(id -> id.equals("REFINED_MITHRIL"));
		require(first(s, "hype").equals("HYPERION") && first(s, "HYPERION").equals("HYPERION"), "name search is case-insensitive");
		require(first(s, "aote").equals("ASPECT_OF_THE_END") && first(s, "term").isEmpty(), "acronym alias; unknown alias finds nothing");
		require(ids(s, "", "ALL").size() == 9, "empty query lists everything");
		require(ids(s, "", "VANILLA").equals(List.of("DIAMOND")) && ids(s, "", "MOB").equals(List.of("ZOMBIE_5_MONSTER")), "category filter");
		require(ids(s, "%pet", "ALL").equals(List.of("BEE;4")) && ids(s, "%MOB", "ALL").size() == 1, "%CATEGORY filter");
		require(ids(s, "r:legendary", "ALL").size() == 2, "rarity filter prefix");
		require(ids(s, "t:dungeon", "ALL").equals(List.of("HYPERION")), "type filter");
		require(ids(s, "damage>200", "ALL").equals(List.of("HYPERION")) && ids(s, "damage>=100", "ALL").size() == 2 && ids(s, "damage<150", "ALL").equals(List.of("ASPECT_OF_THE_END")),
			"stat compares");
		require(ids(s, "strength", "ALL").equals(List.of("HYPERION")), "lore search finds lore-only hits");
		require(ids(s, "\"strength: +150\"", "ALL").equals(List.of("HYPERION")) && ids(s, "\"aspect of\"", "ALL").equals(List.of("ASPECT_OF_THE_END")), "phrases");
		require(ids(s, "/^aspect/", "ALL").equals(List.of("ASPECT_OF_THE_END")), "regex");
		require(ids(s, "/(/", "ALL").size() == 9, "a bad regex filters nothing");
		require(ids(s, "is:forgeable", "ALL").equals(List.of("REFINED_MITHRIL")) && ids(s, "is:craftable", "ALL").size() == 2
			&& ids(s, "is:vanilla", "ALL").equals(List.of("DIAMOND")) && ids(s, "is:dungeon", "ALL").equals(List.of("HYPERION")), "flags");
		require(ids(s, "is:bazaar", "ALL").equals(List.of("REFINED_MITHRIL")) && ids(s, "is:npc", "ALL").equals(List.of("FLINT_SHOVEL")), "bazaar and npc flags");
		require(ids(s, "zombie", "ALL").get(0).equals("ZOMBIE_5_MONSTER"), "name hit ranks first");
		require(ids(s, "hype r:epic", "ALL").isEmpty() && ids(s, "hype r:leg", "ALL").equals(List.of("HYPERION")), "terms are ANDed");
		require(s.suggest("hyp").equals("Hyperion") && s.suggest("hype").equals("Hyperion") && s.suggest("aote").equals("Aspect of the End")
			&& s.suggest("hyperion").isEmpty() && s.suggest("r:leg").isEmpty() && s.suggest("").isEmpty(), "autocomplete ghost text");
		require(ItemSearch.acronym("aspect of the end").equals("aote") && ItemSearch.stats(List.of("Damage: +260 (+30)")).get("damage") == 260.0, "acronym and stat helpers");
	}

	private static Double calc(String text) {
		return SearchCalculator.evaluate(text, (fn, arg) -> {
			if (fn.equals("bz") && arg.equals("enchanted diamond")) return 1000.0;
			if (fn.equals("lb") && arg.equals("hyperion")) return 900_000_000.0;
			if (fn.equals("lb") && arg.equals("necron blade")) return 700_000_000.0;
			return null;
		});
	}

	private static void checkCalculator() {
		require(calc("1.5m*3+250k") == 4_750_000.0, "suffixes and precedence");
		require(calc("2+3*4") == 14.0 && calc("(1+2)*3") == 9.0 && calc("2^3^2") == 512.0 && calc("-5+2") == -3.0 && calc("7%4") == 3.0 && calc("10/4") == 2.5, "operators");
		require(calc("1k") == 1000.0 && calc("2b") == 2e9 && calc("1,5m") == 15_000_000.0, "suffix alone and grouping commas");
		require(calc("5") == null && calc("hype") == null && calc("%pet") == null && calc("r:epic") == null && calc("") == null, "plain text is not maths");
		require(calc("1/0") == null && calc("1m2") == null && calc("1+") == null && calc("kk") == null, "malformed maths is null");
		require(calc("2 x 3") == 6.0 && calc("2X3") == 6.0 && calc("3×4") == 12.0 && calc("1m x 2") == 2_000_000.0 && calc("1mx2") == 2_000_000.0
			&& calc("6÷4") == 1.5 && calc("2**3") == 8.0 && calc("1t") == 1e12 && calc("1b/2") == 5e8 && calc("(1+2") == 3.0 && calc("2x") == null,
			"lenient multiplication, division, trillions and open parenthesis");
		require(calc("bz(enchanted diamond)*160") == 160_000.0, "price function by name");
		require(calc("lb(hyperion)-lb(necron blade)") == 200_000_000.0, "price functions combine");
		require(calc("bz(unknown)") == null, "unknown price is null");
		require(calc("(".repeat(100) + "1" + ")".repeat(100) + "+1") == null, "deep nesting is refused");
		require(SearchCalculator.format(4_750_000.0).equals("4,750,000") && SearchCalculator.format(2.5).equals("2.5") && SearchCalculator.plain(4_750_000.0).equals("4750000"),
			"formatting");
	}

	private static void checkSlotSearch() {
		List<String> lore = List.of("Damage: +260", "Ability: Wither Impact");
		require(SlotSearch.matches("hyperion", "Hyperion", lore) && SlotSearch.matches("HYPER", "hyperion", lore), "name match, case-insensitive");
		require(SlotSearch.matches("wither impact", "Hyperion", lore), "lore match");
		require(SlotSearch.matches("hyperion damage", "Hyperion", lore) && !SlotSearch.matches("hyperion bow", "Hyperion", lore), "words are ANDed over name and lore");
		require(SlotSearch.matches("\"wither impact\"", "Hyperion", lore) && !SlotSearch.matches("\"impact wither\"", "Hyperion", lore), "quoted phrase stays one term");
		require(SlotSearch.matches("", "Hyperion", lore) && SlotSearch.matches("   ", "Hyperion", lore), "blank query matches everything");
		require(!SlotSearch.matches("necron", "Hyperion", List.of()), "no match without lore");
	}

	private static void checkLayout() {
		Layout crafting = RecipeLayout.of(parsed(HYPERION).getRecipes().get(0));
		require(crafting.getInputs().size() == 3 && crafting.getInputs().get(2).getX() == 1 && crafting.getInputs().get(2).getY() == 1
			&& crafting.getOutputs().get(0).getX() == 4 && crafting.getArrowX() == 3 && crafting.getRows() == 3, "crafting grid keeps cell positions");
		Layout forge = RecipeLayout.of(parsed(MITHRIL).getRecipes().get(0));
		require(forge.getInputs().size() == 2 && forge.getArrowX() == 2 && forge.getOutputs().get(0).getX() == 3
			&& forge.getLines().contains("Duration: 10m"), "forge layout and duration");
		Layout drops = RecipeLayout.of(parsed(ZOMBIE).getRecipes().get(0));
		require(drops.getOutputs().size() == 2 && drops.getOutputs().get(1).getLabel().equals("5%") && drops.getOutputs().get(0).getX() == 2, "drops layout with chances");
		Layout kat = RecipeLayout.of(parsed(BEE).getRecipes().get(0));
		require(kat.getInputs().size() == 2 && kat.getLines().contains("Cost: 1,000,000 coins"), "kat layout");
		require(RecipeLayout.duration(3725.0).equals("1h 2m 5s") && RecipeLayout.duration(0.0).equals("0s"), "durations");
		java.util.Map<String, geiler.addons.client.pricing.BazaarProduct> bz = java.util.Map.of(
			"ENCHANTED_DIAMOND", new geiler.addons.client.pricing.BazaarProduct("ENCHANTED_DIAMOND", 1000.0, 900.0));
		geiler.addons.client.pricing.PriceSnapshot snapshot = new geiler.addons.client.pricing.PriceSnapshot(
			java.util.Map.of("STICK", 1.0), null, null, bz, java.util.Map.of("HYPERION", 9e8), null, null);
		require(ItemPrices.unit("ENCHANTED_DIAMOND", snapshot, false) == 900.0 && ItemPrices.unit("ENCHANTED_DIAMOND", snapshot, true) == 1000.0,
			"bazaar price follows the buy mode");
		require(ItemPrices.unit("HYPERION", snapshot, false) == 9e8 && ItemPrices.unit("STICK", snapshot, false) == 1.0
			&& ItemPrices.unit("SKYBLOCK_COIN", snapshot, false) == 1.0 && ItemPrices.unit("NOPE", snapshot, false) == null, "bin, npc, coins and unknown prices");
		require(ItemPrices.source("lb", "HYPERION", snapshot, false) == 9e8 && ItemPrices.source("npc", "HYPERION", snapshot, false) == null
			&& ItemPrices.source("bz", "ENCHANTED_DIAMOND", snapshot, true) == 1000.0, "calculator price functions read one source each");
	}

	private static byte[] zip(Map<String, String> files) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
				for (Map.Entry<String, String> file : files.entrySet()) {
					zip.putNextEntry(new ZipEntry(file.getKey()));
					zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
					zip.closeEntry();
				}
			}
			return bytes.toByteArray();
		} catch (java.io.IOException exception) {
			throw new AssertionError(exception);
		}
	}

	private static void checkRepo() {
		NeuRepo.Repo repo = NeuRepo.INSTANCE.parse(new ByteArrayInputStream(zip(Map.of(
			"REPO-master/items/HYPERION.json", HYPERION,
			"REPO-master/items/ZOMBIE_5_MONSTER.json", ZOMBIE,
			"REPO-master/items/BROKEN.json", "{",
			"REPO-master/constants/essencecosts.json", ESSENCE,
			"REPO-master/other/README.md", "hello"))));
		require(repo.getEntries().size() == 2 && repo.getRecipes().size() == 4, "zip pass keeps items and essence, skips broken files");
		String saved = NeuRepo.INSTANCE.encode(repo).toString();
		NeuRepo.Repo back = NeuRepo.INSTANCE.decode(com.google.gson.JsonParser.parseString(saved).getAsJsonObject());
		require(back != null && back.getEntries().size() == 2 && back.getRecipes().size() == 4
			&& back.getEntries().stream().anyMatch(x -> x.getId().equals("HYPERION") && x.getLore().size() == 4 && "https://wiki.hypixel.net/Hyperion".equals(x.getWikiUrl())),
			"cache round trip");
		require(NeuRepo.INSTANCE.decode(com.google.gson.JsonParser.parseString("{\"version\":99}").getAsJsonObject()) == null, "unknown cache version is refused");
	}

	/** The offline environment has not loaded item default components; the game has. */
	private static void bindComponents() {
		net.minecraft.core.registries.BuiltInRegistries.ITEM.listElements().forEach(holder -> {
			if (!holder.areComponentsBound()) holder.bindComponents(net.minecraft.core.component.DataComponents.COMMON_ITEM_COMPONENTS);
		});
	}

	private static net.minecraft.world.item.ItemStack stack(Entry entry) {
		return LegacyItemStacks.buildOrThrow(entry, LegacyItemStacks.registries());
	}

	/** Real NEU item data (1.8 format) must become real stacks: right item, heads with a profile, name, lore, custom data. */
	private static void checkStacks() {
		bindComponents();
		Entry sword = new Entry("ASPECT_OF_THE_END", "§9Aspect of the End", List.of("§7Damage: §c+100", "", "§9§lRARE SWORD"),
			"minecraft:diamond_sword", 0,
			"{ExtraAttributes:{id:\"ASPECT_OF_THE_END\"},HideFlags:254,ItemModel:\"hypixel_skyblock:item/uncategorized/aspect_of_the_end\",Unbreakable:1B,"
				+ "display:{Lore:[0:\"§7Damage: §c+100\"],Name:\"§9Aspect of the End\"}}",
			"RARE", "SWORD", ItemListData.CAT_WEAPON, null);
		net.minecraft.world.item.ItemStack built = stack(sword);
		require(built != null && built.is(net.minecraft.world.item.Items.DIAMOND_SWORD), "sword becomes a diamond sword");
		require(built.getHoverName().getString().equals("Aspect of the End"), "name keeps its text without colour codes");
		net.minecraft.world.item.component.ItemLore lore = built.get(net.minecraft.core.component.DataComponents.LORE);
		require(lore != null && lore.lines().size() == 3, "lore has every repository line");
		net.minecraft.world.item.component.CustomData custom = built.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		require(custom != null && custom.copyTag().getStringOr("id", "").equals("ASPECT_OF_THE_END"), "ExtraAttributes id becomes custom data");
		java.util.List<net.minecraft.network.chat.Component> tooltip = built.getTooltipLines(
			net.minecraft.world.item.Item.TooltipContext.EMPTY, null, net.minecraft.world.item.TooltipFlag.NORMAL);
		require(tooltip.size() >= 4 && tooltip.get(0).getString().equals("Aspect of the End") && tooltip.get(1).getString().contains("Damage: +100"), "tooltip shows name and lore");

		Entry wolf = new Entry("WOLF;4", "§7[Lvl {LVL}] §6Wolf", List.of("", "§6§lLEGENDARY"), "minecraft:skull", 3,
			"{ExtraAttributes:{id:\"WOLF;4\"},HideFlags:254,SkullOwner:{Id:\"0734d656-b463-3404-a706-7d2462d6d566\",Properties:{textures:[0:{Value:\"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODE0ODMxMDVhYjhjMzNmMDRjOTdjNzQ5M2M5ODcxYzhhNTFjYzQ5ZGY2ZDliNWUyM2IyNjY1MjA5NGJmMzAzMCJ9fX0=\"}]}}}",
			"LEGENDARY", "PET", ItemListData.CAT_PET, null);
		net.minecraft.world.item.ItemStack head = stack(wolf);
		require(head != null && head.is(net.minecraft.world.item.Items.PLAYER_HEAD), "1.8 skull with damage 3 becomes a player head");
		require(head.has(net.minecraft.core.component.DataComponents.PROFILE), "the head keeps its texture profile");
		require(head.getHoverName().getString().equals("[Lvl 100] Wolf"), "pet level placeholder is filled");

		Entry diamond = new Entry("ENCHANTED_DIAMOND", "§aEnchanted Diamond", List.of("§8Collection Item"), "minecraft:diamond", 0,
			"{ExtraAttributes:{id:\"ENCHANTED_DIAMOND\"},HideFlags:254,display:{Lore:[0:\"x\"],Name:\"y\"},ench:[]}", "UNCOMMON", "ORE", ItemListData.CAT_MATERIAL, null);
		net.minecraft.world.item.ItemStack gem = stack(diamond);
		require(gem != null && gem.is(net.minecraft.world.item.Items.DIAMOND), "empty ench list is fine");

		Entry bad = new Entry("NOPE", "Nope", List.of(), "minecraft:not_an_item", 0, "", "", "", ItemListData.CAT_MATERIAL, null);
		LegacyItemStacks.Store store = new LegacyItemStacks.Store(LegacyItemStacks.registries());
		store.warm(List.of(sword, wolf, diamond, bad));
		require(store.getSize() == 4 && store.getFailures() == 1 && store.getWarmed(), "warm-up builds all, one failure");
		require(store.peek("NOPE").is(net.minecraft.world.item.Items.BARRIER) && store.peek("WOLF;4").is(net.minecraft.world.item.Items.PLAYER_HEAD), "failures show a barrier, the rest real items");
		// The sword asks for the Hypixel pack's model; with no pack loaded (no game here) it stays the plain diamond sword.
		require(store.peek("ASPECT_OF_THE_END").is(net.minecraft.world.item.Items.DIAMOND_SWORD), "an unavailable pack model leaves the plain item");
		try {
			java.nio.file.Path saved = java.nio.file.Files.createTempFile("item-stacks", ".nbt.gz");
			store.save(saved, "1:abc");
			LegacyItemStacks.Store again = new LegacyItemStacks.Store(LegacyItemStacks.registries());
			require(again.load(saved, "1:other", List.of(sword, wolf, diamond, bad)) == 0, "a saved file for another commit is ignored");
			require(again.load(saved, "1:abc", List.of(sword, wolf, diamond, bad)) == 3, "saved stacks come back, the failed one does not");
			net.minecraft.world.item.ItemStack restored = again.peek("WOLF;4");
			require(restored != null && restored.is(net.minecraft.world.item.Items.PLAYER_HEAD) && restored.has(net.minecraft.core.component.DataComponents.PROFILE)
				&& restored.getHoverName().getString().equals("[Lvl 100] Wolf"), "a restored stack keeps profile and name");
			java.nio.file.Files.deleteIfExists(saved);
		} catch (java.io.IOException exception) {
			throw new AssertionError(exception);
		}
		require(NeuRepo.INSTANCE.parseSha("95f8678f6a3a937ec14c7bb7279c58fc114a445d\n") != null && NeuRepo.INSTANCE.parseSha("not a sha") == null
			&& NeuRepo.INSTANCE.parseSha("{\"message\":\"rate limited\"}") == null, "commit id parsing");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError("ItemListChecks: " + message);
	}
}
