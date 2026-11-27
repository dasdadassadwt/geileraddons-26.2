package geiler.addons.client.profileviewer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Profile Viewer parts that need NotEnoughUpdates repository constants (bestiary, minions, accessories, attributes, perk trees). */
final class ProfileViewerConstantsChecks {
	private ProfileViewerConstantsChecks() { }

	static void run() {
		bestiary();
		minions();
		accessories();
		attributes();
		trees();
		jacob();
		garden();
		museum();
		backend();
	}

	private static void bestiary() {
		JsonObject constants = obj("{\"brackets\":{\"1\":[20,40,60,100,200],\"2\":[5,10,15]},"
			+ "\"hub\":{\"name\":\"Hub\",\"mobs\":[{\"name\":\"§aCrypt Ghoul\",\"cap\":100,\"mobs\":[\"ghoul_30\",\"ghoul_40\"],\"bracket\":1},"
			+ "{\"name\":\"Zombie\",\"cap\":15,\"mobs\":[\"zombie_1\"],\"bracket\":2}]},"
			+ "\"fishing\":{\"name\":\"Fishing\",\"hasSubcategories\":true,"
			+ "\"lava\":{\"name\":\"Lava\",\"mobs\":[{\"name\":\"Thunder\",\"cap\":15,\"mobs\":[\"thunder_400\"],\"bracket\":2}]}}}");
		PvBestiary b = PvBestiary.parse(constants, Map.of("ghoul_30", 50L, "ghoul_40", 30L, "zombie_1", 20L, "thunder_400", 6L));
		equal(2, b.getIslands().size(), "hub and fishing");
		PvBestiary.Island hub = b.getIslands().get(0);
		equal("Hub", hub.getName(), "island name");
		equal(3, hub.getMobs().get(0).getTier(), "80 kills of bracket 1 is tier 3");
		equal(4, hub.getMobs().get(0).getMaxTier(), "cap 100 is max tier 4");
		equal(80L, hub.getMobs().get(0).getKills(), "kills summed over ids");
		equal("Crypt Ghoul", hub.getMobs().get(0).getName(), "colour codes dropped");
		equal(3, hub.getMobs().get(1).getTier(), "capped at 15");
		equal(1, hub.getMaxed(), "only the zombie is maxed");
		equal(1, b.getIslands().get(1).getMobs().get(0).getTier(), "subcategory mob");
		equal(7, b.getTiers(), "3 + 3 + 1 tiers");
		equal(0, b.getMilestone(), "milestone is tiers / 10");
	}

	private static void minions() {
		JsonObject misc = obj("{\"minions\":{\"WHEAT_GENERATOR\":12,\"COBBLESTONE_GENERATOR\":12,\"SNOW_GENERATOR\":11}}");
		List<PvMinions.Minion> list = PvMinions.parse(misc, Set.of("WHEAT_1", "WHEAT_2", "WHEAT_11", "SNOW_3", "junk"));
		equal(3, list.size(), "every minion listed");
		PvMinions.Minion snow = list.stream().filter(m -> m.getId().equals("SNOW")).findFirst().orElseThrow();
		equal(3, snow.getHighest(), "highest crafted tier");
		equal(11, snow.getMaxTier(), "max tier");
		equal(4, PvMinions.uniqueCrafts(Set.of("WHEAT_1", "WHEAT_2", "WHEAT_11", "SNOW_3", "junk")), "unique crafts skip junk");
	}

	private static void accessories() {
		JsonObject misc = obj("{\"talisman_upgrades\":{\"WOLF_TALISMAN\":[\"WOLF_RING\"],\"WOLF_RING\":[\"WOLF_ARTIFACT\"]},"
			+ "\"ignored_talisman\":[\"PUNCHCARD_ARTIFACT\"]}");
		List<String> all = List.of("WOLF_TALISMAN", "WOLF_RING", "WOLF_ARTIFACT", "SPEED_TALISMAN", "PUNCHCARD_ARTIFACT", "BAT_RING");
		PvAccessories.Missing m = PvAccessories.missing(misc, all, Set.of("WOLF_RING", "BAT_RING"));
		equal(List.of("SPEED_TALISMAN"), m.getMissing(), "only the untouched family is missing; ignored skipped");
		equal(List.of("WOLF_ARTIFACT"), m.getUpgrades(), "owned ring can upgrade");
	}

	private static void attributes() {
		JsonObject constants = obj("{\"attribute_levelling\":{\"COMMON\":[1,3,5],\"RARE\":[1,2]},"
			+ "\"unconsumable_attributes\":[\"SHARD_X\"],"
			+ "\"attributes\":[{\"bazaarName\":\"SHARD_GROVE\",\"displayName\":\"Grove\",\"rarity\":\"COMMON\","
			+ "\"internalName\":\"ATTRIBUTE_SHARD_NATURE_ELEMENTAL;1\",\"abilityName\":\"Nature Elemental\"},"
			+ "{\"bazaarName\":\"SHARD_X\",\"displayName\":\"X\",\"rarity\":\"RARE\",\"internalName\":\"ATTRIBUTE_SHARD_X;1\",\"abilityName\":\"X\"}]}");
		List<PvAttributes.Attribute> list = PvAttributes.parse(constants, Map.of("nature_elemental", 4), Map.of("GROVE", 9));
		equal(1, list.size(), "unconsumable skipped");
		PvAttributes.Attribute a = list.get(0);
		equal("Nature Elemental", a.getName(), "ability name");
		equal(2, a.getLevel(), "4 shards: 1 + 3 for level 2");
		equal(3, a.getMaxLevel(), "three levels");
		equal(9, a.getOwned(), "owned shards");
		equal(0, PvAttributes.level(List.of(1, 3, 5), 0), "none");
		equal(3, PvAttributes.level(List.of(1, 3, 5), 9), "all");
	}

	private static void trees() {
		JsonObject layout = obj("{\"hotm\":{\"perks\":{\"mining_speed\":{\"name\":\"Mining Speed\",\"x\":3,\"y\":9,\"maxLevel\":50},"
			+ "\"pickobulus\":{\"name\":\"Pickobulus\",\"x\":1,\"y\":8,\"maxLevel\":1}}}}");
		List<PvTree.Perk> perks = PvTree.parse(layout, "hotm", Map.of("mining_speed", 20));
		equal(2, perks.size(), "two perks");
		PvTree.Perk speed = perks.stream().filter(p -> p.getId().equals("mining_speed")).findFirst().orElseThrow();
		equal(20, speed.getLevel(), "level");
		equal(50, speed.getMaxLevel(), "max");
		equal(3, speed.getX(), "x");
		equal(9, speed.getY(), "y");
		equal(0, perks.stream().filter(p -> p.getId().equals("pickobulus")).findFirst().orElseThrow().getLevel(), "locked");

		JsonObject looks = obj("{\"hotm\":{\"perks\":{"
			+ "\"core_of_the_mountain\":{\"x\":3,\"y\":5,\"maxLevel\":10,\"item\":\":REDSTONE_BLOCK\"},"
			+ "\"pickobulus\":{\"x\":5,\"y\":8,\"maxLevel\":1,\"item\":\"(api level0)\"},"
			+ "\"maniac_miner\":{\"x\":6,\"y\":4,\"maxLevel\":1,\"item\":\"(api level0)\"},"
			+ "\"mining_speed\":{\"x\":3,\"y\":9,\"maxLevel\":50,\"item\":\"(npi level0 maxLevel)\"},"
			+ "\"mole\":{\"x\":3,\"y\":6,\"maxLevel\":200},\"sky_mall\":{\"x\":0,\"y\":6,\"maxLevel\":1}}}}");
		Map<String, PvTree.Look> by = new java.util.HashMap<>();
		for (PvTree.Perk p : PvTree.parse(looks, "hotm", Map.of("core_of_the_mountain", 3, "pickobulus", 1, "maniac_miner", 1, "mining_speed", 50, "mole", 20)))
			by.put(p.getId(), PvTree.look(p, "pickobulus"));
		equal(PvTree.Look.REDSTONE_BLOCK, by.get("core_of_the_mountain"), "core is a redstone block");
		equal(PvTree.Look.EMERALD_BLOCK, by.get("pickobulus"), "selected ability");
		equal(PvTree.Look.COAL_BLOCK, by.get("maniac_miner"), "unselected ability");
		equal(PvTree.Look.DIAMOND, by.get("mining_speed"), "maxed node");
		equal(PvTree.Look.EMERALD, by.get("mole"), "started node");
		equal(PvTree.Look.COAL, by.get("sky_mall"), "locked node");

		List<PvCollections.Category> cats = PvCollections.parse(obj("{\"collections\":{\"FARMING\":{\"name\":\"Farming\",\"items\":{"
			+ "\"WHEAT\":{\"name\":\"Wheat\",\"tiers\":[{\"tier\":2,\"amountRequired\":100},{\"tier\":1,\"amountRequired\":50}]}}}}}"));
		equal("Farming", cats.get(0).getName(), "category name");
		equal(1, cats.get(0).getItems().get(0).tier(99), "tier from sorted requirements");
		equal(2, cats.get(0).getItems().get(0).tier(100), "max tier");
	}

	private static void jacob() {
		PvSections s = PvSections.parse(obj("{\"jacobs_contest\":{\"medals_inv\":{\"gold\":3,\"silver\":1},"
			+ "\"perks\":{\"double_drops\":5,\"farming_level_cap\":7,\"personal_bests\":true},"
			+ "\"contests\":{\"a\":{},\"b\":{},\"c\":{}},\"unique_brackets\":{\"gold\":[\"WHEAT\",\"CARROT_ITEM\"],\"diamond\":[\"WHEAT\"]},"
			+ "\"personal_bests\":{\"WHEAT\":1234}},"
			+ "\"pets_data\":{\"pet_care\":{\"pet_types_sacrificed\":[\"A\",\"B\"]}}}"));
		equal(3, s.getMedals().get("gold"), "gold medals");
		equal(5, s.getDoubleDrops(), "double drops");
		equal(7, s.getFarmingCapPerk(), "farming cap perk");
		equal(3, s.getContests(), "contests");
		equal(2, s.getUniqueBrackets().get("gold"), "unique golds");
		equal(1234L, s.getPersonalBests().get("WHEAT"), "personal best");
		equal("diamond", s.bestMedal("WHEAT"), "best medal for a crop");
		equal("gold", s.bestMedal("CARROT_ITEM"), "gold only");
		equal(null, s.bestMedal("CACTUS"), "no medal");
		equal(2, s.getPetsSacrificed(), "taming cap raises");
		equal(57, PvLevels.cap("FARMING", s), "farming 50 + 7");
		equal(52, PvLevels.cap("TAMING", s), "taming 50 + 2");
		equal(60, PvLevels.cap("COMBAT", s), "combat 60");
		equal(50, PvLevels.cap("HUNTING", s), "hunting 50");
	}

	private static void garden() {
		JsonObject constants = obj("{\"garden_exp\":[0,70,70,140,240],\"crop_milestones\":{\"WHEAT\":[30,50,80,200]},"
			+ "\"crop_upgrades\":[5,10,20],\"plots\":{\"a\":{},\"b\":{},\"c\":{}},\"visitors\":{\"x\":\"RARE\",\"y\":\"RARE\"}}");
		JsonObject garden = obj("{\"garden_experience\":280,\"resources_collected\":{\"WHEAT\":100},"
			+ "\"crop_upgrade_levels\":{\"WHEAT\":3},\"unlocked_plots_ids\":[\"a\",\"b\"],"
			+ "\"commission_data\":{\"total_completed\":12,\"unique_npcs_served\":5},"
			+ "\"composter_data\":{\"upgrades\":{\"speed\":3}}}");
		PvGarden g = PvGarden.parse(garden, constants);
		equal(4, g.getLevel(), "280 xp is garden level 4");
		equal(5, g.getMaxLevel(), "five levels");
		equal(2, g.getMilestones().get("WHEAT"), "100 wheat is milestone 2");
		equal(3, g.getCropUpgrades().get("WHEAT"), "crop upgrade");
		equal(2, g.getPlots(), "plots unlocked");
		equal(3, g.getMaxPlots(), "plots total");
		equal(12, g.getVisitorsServed(), "visits");
		equal(5, g.getUniqueVisitors(), "unique");
		equal(2, g.getMaxVisitors(), "visitor list");
		equal(3, g.getComposter().get("speed"), "composter");
	}

	private static void museum() {
		JsonObject constants = obj("{\"items\":{\"combat\":[\"A\",\"B\",\"C\"],\"mining\":[\"D\"]},"
			+ "\"children\":{\"B\":\"A\"},\"max_values\":{\"combat\":3,\"mining\":1,\"special\":10,\"total\":4}}");
		JsonObject member = obj("{\"value\":1000000,\"appraisal\":true,\"items\":{\"B\":{},\"D\":{}},\"special\":[{},{}]}");
		PvMuseum m = PvMuseum.parse(member, constants);
		equal(1_000_000L, m.getValue(), "value");
		equal(2, m.getCategories().get(0).getDonated(), "B donated covers its child A");
		equal(3, m.getCategories().get(0).getMax(), "combat max");
		equal(List.of("C"), m.getCategories().get(0).getMissing(), "missing combat");
		equal(1, m.getCategories().get(1).getDonated(), "mining");
		equal(2, m.getSpecial(), "special items");
		equal(3, m.getDonated(), "total donated");
		equal(Boolean.TRUE, m.getAppraisal(), "appraisal");
	}

	private static void backend() {
		equal(Boolean.TRUE, PvBackend.allowedPath("/garden/0123456789abcdef0123456789abcdef"), "garden path");
		equal(Boolean.FALSE, PvBackend.allowedPath("/museum/01234567-89ab-cdef-0123-456789abcdef"), "ids are sent compact only");
		equal(Boolean.TRUE, PvBackend.allowedPath("/museum/" + PvBackend.compact("01234567-89ab-cdef-0123-456789abcdef")), "compacted museum path");
		equal(Boolean.FALSE, PvBackend.allowedPath("/garden/../authenticate"), "no traversal");
		equal(Boolean.FALSE, PvBackend.allowedPath("/player/0123456789abcdef0123456789abcdef"), "only garden and museum");
		equal("0123456789abcdef0123456789abcdef", PvBackend.compact("01234567-89ab-cdef-0123-456789abcdef"), "dashes dropped");
	}

	private static JsonObject obj(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	private static void equal(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}
}
