package geiler.addons.client.profileviewer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public final class ProfileViewerParserChecks {
	private static final String UUID = "0123456789abcdef0123456789abcdef";

	private ProfileViewerParserChecks() { }

	public static void run() throws IOException {
		levels();
		profiles();
		inventories();
		sections();
		newerData();
		networth();
		ProfileViewerConstantsChecks.run();
	}

	/** Class progress in numbers, and the networth categories past items: coins, pets, sacks, essence. */
	private static void networth() {
		DungeonProgress fifty = DungeonProgress.of(569_809_640L);
		equal(0L, fifty.getInto(), "exactly level 50 has nothing into the next level");
		equal(200_000_000L, fifty.getNeed(), "an overflow level takes 200M");
		DungeonProgress early = DungeonProgress.of(25L);
		equal(25L, early.getInto(), "xp into level 1");
		equal(50L, early.getNeed(), "level 1 takes 50 xp");
		equal("50.0%", early.percentText(), "percent of the level");
		equal(25L, early.getLeft(), "xp left");

		JsonObject root = JsonParser.parseString("{\"profiles\":[{\"profile_id\":\"n\",\"cute_name\":\"Nut\",\"banking\":{\"balance\":1000},"
			+ "\"members\":{\"" + UUID + "\":{\"currencies\":{\"coin_purse\":100,\"essence\":{\"WITHER\":{\"current\":5}}},"
			+ "\"profile\":{\"bank_account\":50},"
			+ "\"inventory\":{\"sacks_counts\":{\"ENCHANTED_X\":10,\"EMPTY_ONE\":0}},"
			+ "\"pets_data\":{\"pets\":[{\"type\":\"GOLDEN_DRAGON\",\"tier\":\"LEGENDARY\",\"exp\":30000000,\"candyUsed\":2}]}}}}]}").getAsJsonObject();
		PvProfile p = PvParser.profiles(root, UUID).get(0);
		equal(50.0, p.getPersonalBank(), "personal bank");
		equal(java.util.Map.of("ENCHANTED_X", 10L), p.getSacks(), "sack counts without empty sacks");
		equal(2, p.getPets().get(0).getCandyUsed(), "pet candy");
		java.util.Map<String, Double> prices = java.util.Map.of("ESSENCE_WITHER", 3.0, "ENCHANTED_X", 2.0,
			"LVL_1_LEGENDARY_GOLDEN_DRAGON", 600.0, "LVL_100_LEGENDARY_GOLDEN_DRAGON", 700.0, "LVL_200_LEGENDARY_GOLDEN_DRAGON", 900.0);
		PvNetworth.Result r = PvNetworth.compute(p, null, data -> null, id -> prices.getOrDefault(id, 0.0));
		equal(100.0, r.getCategories().get("Purse"), "purse");
		equal(1050.0, r.getCategories().get("Bank") + r.getCategories().get("Personal bank"), "profile and personal bank");
		equal(15.0, r.getCategories().get("Essence"), "essence at its bazaar price");
		equal(20.0, r.getCategories().get("Sacks"), "sacks at their price");
		double pet = r.getCategories().getOrDefault("Pets", 0.0);
		if (pet < 600.0 || pet > 900.0) throw new AssertionError("pets are priced from the LVL_ prices: " + pet);
		equal(Boolean.FALSE, r.getMuseumIncluded(), "no museum without its data");
	}

	/** Data whose API paths changed: whispers, wardrobe and equipment in loadouts, trophy frogs, co-op collections, garden crops. */
	private static void newerData() throws IOException {
		PvSections s = PvSections.parse(JsonParser.parseString("{"
			+ "\"foraging_core\":{\"whispers\":{\"forest\":{\"total\":300,\"1\":{\"spent\":100},\"2\":{\"spent\":50}},"
			+ "\"desert\":{\"total\":20,\"1\":{\"spent\":5}}}},"
			+ "\"player_stats\":{\"pets\":{\"milestone\":{\"sea_creatures_killed\":40}}},"
			+ "\"leveling\":{\"completed_tasks\":[\"TROPHY_BULLFROG_BRONZE\",\"TROPHY_BULLFROG_GOLD\",\"TROPHY_SEA_FROG_SILVER\",\"TROPHY_BLOBFISH_GOLD\"]}"
			+ "}").getAsJsonObject());
		equal(300L, s.getWhispersAvailable(), "forest whispers now are the forest total");
		equal(450L, s.getWhispersTotal(), "forest whispers ever = now + spent");
		equal(20L, s.getDesertWhispersAvailable(), "desert whispers");
		equal(40L, s.getSeaCreatureKills(), "sea creatures fall back to the pet milestone count");
		equal(3, s.trophyFrog("BULLFROG"), "highest frog tier: gold is 3");
		equal(2, s.trophyFrog("SEA_FROG"), "silver is 2");
		equal(0, s.trophyFrog("TREE_FROG"), "not caught");
		equal(12, PvSections.TROPHY_FROGS.size(), "twelve trophy frogs");

		String other = "fedcba9876543210fedcba9876543210";
		String item = inventory(1);
		JsonObject root = JsonParser.parseString("{\"profiles\":[{\"profile_id\":\"c\",\"cute_name\":\"Coop\",\"members\":{"
			+ "\"" + UUID + "\":{\"collection\":{\"WHEAT\":10},\"inventory\":{\"inv_contents\":{\"data\":\"" + item + "\"}},"
			+ "\"loadout\":{\"armor\":{\"1\":{\"id\":1,\"HELMET\":{\"data\":\"" + item + "\"},\"BOOTS\":{\"data\":\"" + item + "\"}},"
			+ "\"2\":{\"id\":2}},"
			+ "\"equipment\":{\"3\":{\"id\":3,\"EQUIPMENT_SLOT_2\":{\"data\":\"" + item + "\"}}},"
			+ "\"loadouts\":{\"1\":{\"id\":1,\"name\":\"Loadout 1\"},\"4\":{\"id\":4,\"name\":\"Mine\",\"armor_set_id\":1,"
			+ "\"equipment_set_id\":3,\"pet\":\"abc\",\"power_stone\":\"hurtful\"}}},"
			+ "\"pets_data\":{\"pets\":[{\"uuid\":\"abc\",\"type\":\"BAT\",\"tier\":\"RARE\",\"exp\":0}]}},"
			+ "\"" + other + "\":{\"collection\":{\"WHEAT\":5,\"CACTUS\":2}}}}]}").getAsJsonObject();
		PvProfile p = PvParser.profiles(root, UUID).get(0);
		equal(15L, p.getCollections().get("WHEAT"), "collections add up the co-op");
		equal(2L, p.getCollections().get("CACTUS"), "co-op only collection");
		equal(5L, p.getCollectionsByMember().get("WHEAT").get(other), "per member amount");
		equal(Boolean.TRUE, p.getInventories().containsKey("wardrobe_1_HELMET"), "wardrobe set piece from the loadout");
		equal(Boolean.TRUE, p.getInventories().containsKey("equipment_3_2"), "equipment set piece from the loadout");
		equal(List.of(1), p.getArmorSets(), "only sets with pieces");
		equal(List.of(3), p.getEquipmentSets(), "equipment sets");
		equal(1, p.getLoadouts().size(), "default loadouts without sets are skipped");
		equal("Mine", p.getLoadouts().get(0).getName(), "loadout name");
		equal("abc", p.getPets().get(0).getUuid(), "pet uuid for the loadout pet");
		equal("§b", PvLevels.sbLevelColor(200), "SkyBlock level 200 is aqua");
		equal("§7", PvLevels.sbLevelColor(39), "below 40 is gray");
		equal("§4", PvLevels.sbLevelColor(520), "480 and up is dark red");
		equal("CARROT", PvGarden.cropKey("CARROT_ITEM"), "garden crop key");
		equal("COCOA_BEANS", PvGarden.cropKey("INK_SACK:3"), "cocoa");
		equal("NETHER_WART", PvGarden.cropKey("NETHER_STALK"), "wart");
		equal("MUSHROOM", PvGarden.cropKey("MUSHROOM_COLLECTION"), "mushroom");
		equal(12.5, PvProfile.secretAverage(250L, 20), "secrets per run");
		equal(0.0, PvProfile.secretAverage(250L, 0), "no runs");
	}

	/** Newer profile data: skill trees (HOTM and HOTF), foraging, hunting, Crimson Isle, Rift, trophy fish, chocolate. */
	private static void sections() {
		equal(7, PvLevels.hotf(347_000L), "HOTF 7");
		equal(2, PvLevels.hotf(3_000L), "HOTF 2");
		equal(1, PvLevels.hotf(0L), "HOTF 1");
		equal(8, PvLevels.hotf(547_000L), "HOTF 8 (added in SkyBlock 0.27)");
		equal(7, PvLevels.hotf(546_999L), "just under HOTF 8");
		equal(15, PvLevels.hotfTokens(8, 0), "HOTF 8 tokens");
		equal("Black", PvSections.belt(7_000), "black belt");
		equal("Brown", PvSections.belt(6_999), "brown belt");
		equal("White", PvSections.belt(999), "white belt");
		equal("Force", PvSections.dojoName("mob_kb"), "test name");
		equal(20, PvLevels.hotmTokens(10, 0), "HOTM 10 tokens");
		equal(25, PvLevels.hotmTokens(10, 10), "plus Core of the Mountain");
		equal(13, PvLevels.hotfTokens(7, 0), "HOTF 7 tokens");
		equal(15, PvLevels.hotfTokens(7, 5), "plus Center of the Forest");

		JsonObject member = JsonParser.parseString("{"
			+ "\"skill_tree\":{\"experience\":{\"mining\":1247000,\"foraging\":97000},"
			+ "\"nodes\":{\"mining\":{\"core_of_the_mountain\":3,\"mining_speed\":50},\"foraging\":{\"center_of_the_forest\":1}},"
			+ "\"tokens_spent\":{\"mountain\":12,\"forest\":4},\"selected_ability\":{\"mining\":\"pickobulus\",\"foraging\":\"\"}},"
			+ "\"mining_core\":{\"powder_mithril\":5,\"powder_spent_mithril\":10,\"powder_mithril_total\":40,"
			+ "\"powder_gemstone\":7,\"powder_spent_gemstone\":3,"
			+ "\"crystals\":{\"jade_crystal\":{\"state\":\"FOUND\",\"total_placed\":9},\"amber_crystal\":{\"total_placed\":4}}},"
			+ "\"glacite_player_data\":{\"fossils_donated\":[\"CLAW\",\"SPINE\"],\"fossil_dust\":12.5,"
			+ "\"corpses_looted\":{\"lapis\":3,\"umber\":2},\"mineshafts_entered\":8},"
			+ "\"foraging_core\":{\"forests_whispers\":100,\"forests_whispers_spent\":50},"
			+ "\"foraging\":{\"fish_family\":[\"A\",\"B\"],\"hina\":{\"tasks\":{\"tier_claimed\":3}},"
			+ "\"tree_gifts\":{\"milestone_tier_claimed\":{\"FIG\":2,\"MANGROVE\":1}}},"
			+ "\"attributes\":{\"stacks\":{\"lifeline\":5,\"mana_pool\":2}},"
			+ "\"shards\":{\"owned\":[{\"type\":\"a\",\"amount_owned\":3},{\"type\":\"b\",\"amount_owned\":4}]},"
			+ "\"nether_island_player_data\":{\"selected_faction\":\"mages\",\"mages_reputation\":1200.5,\"barbarians_reputation\":-50,"
			+ "\"kuudra_completed_tiers\":{\"none\":10,\"infernal\":2,\"highest_wave_hot\":9},"
			+ "\"dojo\":{\"dojo_points_mob_kb\":900,\"dojo_time_mob_kb\":30},\"matriarch\":{\"pearls_collected\":7}},"
			+ "\"rift\":{\"enigma\":{\"found_souls\":[\"a\",\"b\",\"c\"]},\"dead_cats\":{\"found_cats\":[\"x\"]},"
			+ "\"castle\":{\"grubber_stacks\":4},\"gallery\":{\"secured_trophies\":[{\"type\":\"t\"}]},"
			+ "\"wither_cage\":{\"killed_eyes\":[\"e1\",\"e2\"]}},"
			+ "\"currencies\":{\"motes_purse\":1500,\"essence\":{\"WITHER\":{\"current\":300},\"DRAGON\":{\"current\":20}}},"
			+ "\"player_stats\":{\"rift\":{\"lifetime_motes_earned\":9000,\"visits\":12},"
			+ "\"kills\":{\"total\":100,\"zealot_enderman\":60,\"ghast\":40},\"deaths\":{\"total\":5,\"void\":5},"
			+ "\"items_fished\":{\"total\":77},\"sea_creature_kills\":33},"
			+ "\"trophy_fish\":{\"total_caught\":11,\"blobfish\":6,\"blobfish_bronze\":5,\"blobfish_gold\":1,\"rewards\":[1,2]},"
			+ "\"events\":{\"easter\":{\"chocolate\":1000,\"total_chocolate\":5000,\"chocolate_since_prestige\":4000,"
			+ "\"chocolate_level\":3,\"rabbits\":{\"bob\":2,\"amy\":1,\"collected_eggs\":{\"breakfast\":1}}}},"
			+ "\"accessory_bag_storage\":{\"highest_magical_power\":1450,\"selected_power\":\"silky\"}"
			+ "}").getAsJsonObject();
		PvSections s = PvSections.parse(member);
		equal(1_247_000L, s.getHotmXp(), "HOTM from the skill tree");
		equal(10, s.getHotmLevel(), "HOTM level");
		equal(3, s.getCoreOfTheMountain(), "Core of the Mountain");
		equal(12, s.getMountainTokensSpent(), "tokens spent");
		equal("Pickobulus", s.getPickaxeAbility(), "pickaxe ability");
		equal(40L, s.powder("mithril").getTotal(), "total from the API");
		equal(10L, s.powder("gemstone").getTotal(), "total as available + spent");
		equal(9, s.getNucleusRuns(), "nucleus runs are the most placed crystal");
		equal("FOUND", s.getCrystals().get("jade"), "crystal state");
		equal(2, s.getFossilsDonated(), "fossils");
		equal(5, s.getCorpsesLooted(), "corpses");
		equal(8, s.getMineshafts(), "mineshafts");
		equal(5, s.getHotfLevel(), "HOTF 5 at 97k");
		equal(1, s.getCenterOfTheForest(), "Center of the Forest");
		equal(4, s.getForestTokensSpent(), "forest tokens");
		equal("None", s.getAxeAbility(), "no axe ability");
		equal(150L, s.getWhispersTotal(), "whispers total");
		equal(2, s.getFishFamily(), "fish family");
		equal(3, s.getHinaChapter(), "Hina chapter");
		equal(2, s.getTreeGifts().get("FIG"), "fig gifts");
		equal(5, s.getAttributes().get("lifeline"), "attribute stacks");
		equal(7, s.getShardsOwned(), "shards owned");
		equal("mages", s.getFaction(), "faction");
		equal(1200.5, s.getMagesReputation(), "mage rep");
		equal(2, s.getKuudra().get("infernal"), "kuudra infernal");
		equal(null, s.getKuudra().get("highest_wave_hot"), "waves are not tiers");
		equal(900, s.getDojo().get("mob_kb"), "dojo points");
		equal(7, s.getMatriarchPearls(), "matriarch pearls");
		equal(1500.0, s.getMotes(), "motes");
		equal(9000.0, s.getLifetimeMotes(), "lifetime motes");
		equal(12, s.getRiftVisits(), "visits");
		equal(3, s.getEnigmaSouls(), "enigma souls");
		equal(1, s.getMontezumaCats(), "cats");
		equal(4, s.getGrubberStacks(), "grubber");
		equal(1, s.getTimecharms(), "timecharms");
		equal(2, s.getWitherEyes(), "eyes");
		equal(300L, s.getEssence().get("WITHER"), "essence");
		equal(60L, s.getKills().get("zealot_enderman"), "kills");
		equal(null, s.getKills().get("total"), "total is not a mob");
		equal(100L, s.getTotalKills(), "total kills");
		equal(5L, s.getTotalDeaths(), "total deaths");
		equal(77L, s.getItemsFished(), "items fished");
		equal(33L, s.getSeaCreatureKills(), "sea creatures");
		equal(11, s.getTrophyTotal(), "trophy total");
		equal(5, s.trophy("blobfish", "bronze"), "trophy tier");
		equal(0, s.trophy("blobfish", "diamond"), "missing tier");
		equal(1000L, s.getChocolate(), "chocolate");
		equal(5000L, s.getChocolateTotal(), "all-time chocolate");
		equal(3, s.getChocolatePrestige(), "prestige");
		equal(2, s.getRabbits(), "unique rabbits, egg data skipped");
		equal(1450, s.getMagicalPower(), "magical power");
		equal("Silky", s.getSelectedPower(), "power");

		PvSections empty = PvSections.parse(JsonParser.parseString("{\"mining_core\":{\"experience\":3000}}").getAsJsonObject());
		equal(3000L, empty.getHotmXp(), "old mining_core experience is the fallback");
		equal(1, empty.getHotfLevel(), "no HOTF");
		equal(null, empty.getFaction(), "no faction");
	}

	private static void levels() {
		equal(0.0, PvLevels.skill(0, 60), "no xp is level 0");
		equal(1.0, PvLevels.skill(50, 60), "50 xp is level 1");
		equal(50.0, PvLevels.skill(55_172_425L, 60), "level 50 total");
		equal(50.0, PvLevels.skill(200_000_000L, 50), "capped at 50");
		equal(60.0, PvLevels.skill(111_672_425L, 60), "level 60 total");
		equal(1.496, PvLevels.skill(50 + 62, 60), "62 of 125 xp toward level 2");
		equal(25.0, PvLevels.runecrafting(94_300L), "runecrafting max");
		equal(4, PvLevels.slayer("zombie", 1_000L), "zombie 1k xp is level 4");
		equal(9, PvLevels.slayer("wolf", 2_000_000L), "wolf max");
		equal(5, PvLevels.slayer("vampire", 2_400L), "vampire max");
		equal(1, PvLevels.pet("COMMON", 0L), "pets start at 1");
		equal(100, PvLevels.pet("LEGENDARY", 25_353_230L), "legendary 100");
		equal(100, PvLevels.pet("COMMON", 5_624_785L), "common 100");
		equal(2, PvLevels.pet("LEGENDARY", 660L), "legendary level 2 after 660 xp");
		equal(10, PvLevels.hotm(1_247_000L), "HOTM 10");
		equal(1, PvLevels.hotm(2_999L), "HOTM 1");
	}

	private static void profiles() throws IOException {
		JsonObject root = JsonParser.parseString("{\"profiles\":["
			+ "{\"profile_id\":\"a\",\"cute_name\":\"Apple\",\"selected\":false,\"members\":{\"" + UUID + "\":{}}},"
			+ "{\"profile_id\":\"b\",\"cute_name\":\"Banana\",\"selected\":true,\"game_mode\":\"ironman\","
			+ "\"banking\":{\"balance\":1234.5},\"members\":{\"" + UUID + "\":{"
			+ "\"player_data\":{\"experience\":{\"SKILL_COMBAT\":55172425,\"SKILL_RUNECRAFTING\":94300}},"
			+ "\"leveling\":{\"experience\":25050},"
			+ "\"currencies\":{\"coin_purse\":99.5},"
			+ "\"fairy_soul\":{\"total_collected\":240},"
			+ "\"slayer\":{\"slayer_bosses\":{\"zombie\":{\"xp\":1000,\"boss_kills_tier_4\":7}}},"
			+ "\"dungeons\":{\"selected_dungeon_class\":\"mage\",\"secrets\":5000,"
			+ "\"dungeon_types\":{\"catacombs\":{\"experience\":569809640,\"tier_completions\":{\"7\":100},"
			+ "\"fastest_time_s_plus\":{\"7\":250000}},\"master_catacombs\":{\"tier_completions\":{\"7\":40}}},"
			+ "\"player_classes\":{\"mage\":{\"experience\":569809640}}},"
			+ "\"pets_data\":{\"pets\":[{\"type\":\"GOLDEN_DRAGON\",\"tier\":\"LEGENDARY\",\"exp\":25353230,\"active\":true,"
			+ "\"heldItem\":\"PET_ITEM_TEXTBOOK\"}]},"
			+ "\"collection\":{\"WHEAT\":12345},"
			+ "\"mining_core\":{\"experience\":1247000,\"powder_mithril\":10,\"powder_spent_mithril\":90},"
			+ "\"inventory\":{\"inv_contents\":{\"data\":\"" + inventory(2) + "\"},"
			+ "\"backpack_contents\":{\"1\":{\"data\":\"" + inventory(1) + "\"}}}"
			+ "}}}]}").getAsJsonObject();
		List<PvProfile> list = PvParser.profiles(root, UUID);
		equal(2, list.size(), "two profiles");
		PvProfile empty = list.get(0);
		equal("Apple", empty.getCuteName(), "name");
		equal(Boolean.FALSE, empty.getInventoryApi(), "no inventory data is API off");
		PvProfile p = PvParser.selected(list);
		equal("Banana", p.getCuteName(), "selected profile wins");
		equal("ironman", p.getGameMode(), "game mode");
		equal(1234.5, p.getBank(), "bank");
		equal(99.5, p.getPurse(), "purse");
		equal(240, p.getFairySouls(), "fairy souls");
		equal(250.5, p.getSkyblockLevel(), "SkyBlock level is xp / 100");
		equal(50.0, p.skillLevel("COMBAT"), "combat 50");
		equal(25.0, p.skillLevel("RUNECRAFTING"), "runecrafting 25");
		equal(0.0, p.skillLevel("FISHING"), "missing skill is 0");
		equal(1000L, p.getSlayerXp().get("zombie"), "zombie xp");
		equal(7, p.getSlayerKills().get("zombie").get(4), "tier 5 kills index 4");
		equal(50.0, p.getCatacombsLevel(), "cata 50");
		equal("mage", p.getSelectedClass(), "class");
		equal(50.0, p.classLevel("mage"), "mage 50");
		equal(5000L, p.getSecrets(), "secrets");
		equal(100, p.getCompletions().get(7), "F7 completions");
		equal(40, p.getMasterCompletions().get(7), "M7 completions");
		equal(250000L, p.getFastestSPlus().get(7), "F7 S+ ms");
		equal(1, p.getPets().size(), "one pet");
		equal(100, p.getPets().get(0).getLevel(), "pet level");
		equal(Boolean.TRUE, p.getPets().get(0).getActive(), "active pet");
		equal(12345L, p.getCollections().get("WHEAT"), "collection");
		equal(10, p.getSections().getHotmLevel(), "hotm");
		equal(100L, p.getSections().powder("mithril").getTotal(), "mithril total = left + spent");
		equal(Boolean.TRUE, p.getInventoryApi(), "inventory API on");
		equal(Boolean.TRUE, p.getInventories().containsKey("inv_contents"), "inventory kept");
		equal(Boolean.TRUE, p.getInventories().containsKey("backpack_1"), "backpack kept");
	}

	private static void inventories() throws IOException {
		equal(3, PvParser.decodeCount(inventory(2)), "two items plus one empty slot");
		equal(-1, PvParser.decodeCount("not base64!"), "bad data is -1");
	}

	/** A gzipped, base64 NBT `{i:[...]}` with [items] items and one empty slot, as the profile API sends it. */
	private static String inventory(int items) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
			named(out, 10, "");
			named(out, 9, "i");
			out.writeByte(10);
			out.writeInt(items + 1);
			for (int i = 0; i < items; i++) {
				named(out, 2, "id");
				out.writeShort(276);
				named(out, 1, "Count");
				out.writeByte(1);
				out.writeByte(0);
			}
			out.writeByte(0);
			out.writeByte(0);
		}
		return Base64.getEncoder().encodeToString(bytes.toByteArray());
	}

	private static void named(DataOutputStream out, int type, String name) throws IOException {
		out.writeByte(type);
		out.writeUTF(name);
	}

	private static void equal(Object expected, Object actual, String label) {
		boolean same = expected instanceof Number a && actual instanceof Number b
			? Math.abs(a.doubleValue() - b.doubleValue()) < 1e-6
			: java.util.Objects.equals(expected, actual);
		if (!same) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}
}
