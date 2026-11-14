package geiler.addons.client.dungeon;

import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/** Offline fixtures for the profile-evidence boundary used by AutoKick gear checks. */
public final class DungeonStatsChecks {
	private DungeonStatsChecks() {
	}

	public static void run() {
		assertFalse("{\"inventory\":{\"error\":\"unavailable\"}}",
			"an API error object is not complete inventory data");
		assertFalse("{\"inventory\":[]}", "an empty inventory is not complete inventory data");
		assertFalse("{\"inventory\":[\"temporarily unavailable\"]}",
			"an arbitrary status string is not complete inventory data");
		assertFalse("{\"inventory\":[{\"tag\":{\"display\":{\"Lore\":[\"Terminator\"]}}}]}",
			"a lore mention is not an item identity");
		assertTrue("{\"inventory\":[{\"id\":\"TERMINATOR\"}]}",
			"an item identifier makes inventory evidence readable");
		assertTrue("{\"inventory\":[\"minecraft:diamond\"]}",
			"a namespaced item identifier makes inventory evidence readable");
		String odinPayload = compressedItemNbt("TERMINATOR", "HYPERION");
		assertTrue("{\"inventory\":{\"inv_contents\":{\"type\":\"inventory\",\"data\":\""
			+ odinPayload + "\"}}}", "Odin's wrapped base64 NBT inventory data is accepted");
		checkEndpointAllowlist();
		checkRetryAfterPolicy();
		checkRequestLimiterPolicy();
		checkNamePathEncoding();
		checkBoundedJsonParsing();
		checkCurrentProfileShape();
		checkProfileDetails();
		checkUnavailableDetails();
		checkKnownZeroFields();
		checkCatacombsProgress();
		checkJavaFacadeContracts();
		checkModelSnapshotSemantics();
	}

	private static void checkEndpointAllowlist() {
		assertEndpoint("https://api.minecraftservices.com/minecraft/profile/lookup/name/Example", true,
			"Mojang's HTTPS profile endpoint is allowed");
		assertEndpoint("https://api.odtheking.com/hypixel/get/12345678123412341234123456789012", true,
			"Odin's HTTPS profile endpoint is allowed");
		assertEndpoint("https://hypixel.odtheking.com/hypixel/secrets/12345678123412341234123456789012", true,
			"Odin's legacy secrets host remains allowed");
		assertEndpoint("https://api.odtheking.com:443/hypixel/get/example", true,
			"an explicit standard HTTPS port remains allowed");
		assertEndpoint("http://api.odtheking.com/hypixel/get/example", false,
			"plain HTTP is rejected");
		assertEndpoint("https://evil.api.odtheking.com/hypixel/get/example", false,
			"a subdomain outside the exact allowlist is rejected");
		assertEndpoint("https://api.odtheking.com.attacker.invalid/hypixel/get/example", false,
			"a host with an allowlisted suffix is rejected");
		assertEndpoint("https://api.odtheking.com:8443/hypixel/get/example", false,
			"a nonstandard HTTPS port is rejected");
		assertEndpoint("https://example.invalid/hypixel/get/example", false,
			"an unlisted HTTPS host is rejected");
		assertEndpoint("//api.odtheking.com/hypixel/get/example", false,
			"a scheme-relative endpoint is rejected");
		assertEndpoint("https://user@api.odtheking.com/hypixel/get/example", false,
			"endpoint user information is rejected");
		assertEndpoint("https://api.odtheking.com/hypixel/get/example#fragment", false,
			"endpoint fragments are rejected");
	}

	private static void assertEndpoint(String value, boolean expected, String message) {
		boolean actual = DungeonStatsService.endpointAllowedForChecks(URI.create(value));
		if (actual != expected) throw new AssertionError(message + ": got " + actual);
	}

	private static void checkRetryAfterPolicy() {
		if (DungeonStatsService.retryAfterForChecks(null) != 1_000L
			|| DungeonStatsService.retryAfterForChecks("") != 1_000L) {
			throw new AssertionError("missing Retry-After uses the bounded one-second backoff");
		}
		if (DungeonStatsService.retryAfterForChecks("0") != 0L
			|| DungeonStatsService.retryAfterForChecks("1") != 1_000L
			|| DungeonStatsService.retryAfterForChecks("2") != 2_000L) {
			throw new AssertionError("only numeric Retry-After delays up to two seconds are retried");
		}
		if (DungeonStatsService.retryAfterForChecks("3") >= 0
			|| DungeonStatsService.retryAfterForChecks("-1") >= 0
			|| DungeonStatsService.retryAfterForChecks("not-a-delay") >= 0
			|| DungeonStatsService.retryAfterForChecks("9223372036854776") >= 0) {
			throw new AssertionError("long, negative, malformed, and overflowing Retry-After values are surfaced");
		}
		if (DungeonStatsService.retryAfterForChecks("Sun, 06 Nov 1994 08:49:37 GMT") != 0L) {
			throw new AssertionError("past HTTP-date Retry-After values become an immediate bounded retry");
		}
	}

	private static void checkRequestLimiterPolicy() {
		DungeonRequestLimiter limiter = new DungeonRequestLimiter(2, 50L);
		DungeonRequestLimiter.Ticket first = limiter.tryAcquire();
		DungeonRequestLimiter.Ticket second = limiter.tryAcquire();
		if (first == null || second == null || limiter.tryAcquire() != null || limiter.outstanding() != 2) {
			throw new AssertionError("profile requests remain bounded by the configured outstanding limit");
		}
		first.close();
		first.close();
		DungeonRequestLimiter.Ticket third = limiter.tryAcquire();
		if (limiter.outstanding() != 2 || third == null) {
			throw new AssertionError("closing a request ticket releases one slot exactly once");
		}
		second.close();
		if (limiter.outstanding() != 1) {
			throw new AssertionError("each open request ticket keeps its own outstanding slot");
		}
		third.close();
		if (limiter.outstanding() != 0) throw new AssertionError("all completed tickets release their slots");
		long firstDelay = limiter.reserveStartDelayNanos(100L);
		long pacedDelay = limiter.reserveStartDelayNanos(120L);
		long caughtUpDelay = limiter.reserveStartDelayNanos(200L);
		if (firstDelay != 0L || pacedDelay != 30L || caughtUpDelay != 0L) {
			throw new AssertionError("profile request start pacing preserves monotonic reservation delays");
		}
		try {
			new DungeonRequestLimiter(0, 0L);
			throw new AssertionError("a non-positive request limit is rejected");
		} catch (IllegalArgumentException expected) { }
		try {
			new DungeonRequestLimiter(1, -1L);
			throw new AssertionError("a negative request interval is rejected");
		} catch (IllegalArgumentException expected) { }
	}

	private static void checkNamePathEncoding() {
		String encoded = DungeonStatsService.encodePathForChecks(" Example Name/?#% ");
		if (!encoded.equals("Example%20Name%2F%3F%23%25")) {
			throw new AssertionError("lookup names remain confined to one encoded URL path segment: " + encoded);
		}
		String encodedUnicode = DungeonStatsService.encodePathForChecks("+ Ä");
		if (!encodedUnicode.equals("%2B%20%C3%84")) {
			throw new AssertionError("lookup names encode plus signs and UTF-8 bytes in one URL path segment: "
				+ encodedUnicode);
		}
	}

	private static void checkBoundedJsonParsing() {
		try {
			BoundedDungeonJsonParser.parse("{\"ok\":0}", 1, 2);
		} catch (IOException exception) {
			throw new AssertionError("a response within the JSON depth and node limits remains readable", exception);
		}
		assertJsonLimit("[[0]]", 1, 10, "JSON nesting beyond the configured depth is rejected");
		assertJsonLimit("[0,1]", 1, 2, "JSON nodes beyond the configured count are rejected");
		// Real SkyBlock profiles reach ~127k nodes (5.3 MB); the service limit must accept them.
		StringBuilder large = new StringBuilder("[");
		for (int i = 0; i < 200_000; i++) large.append(i == 0 ? "0" : ",0");
		large.append(']');
		try {
			BoundedDungeonJsonParser.parse(large.toString(), 32, DungeonStatsService.maxJsonNodesForChecks());
		} catch (IOException exception) {
			throw new AssertionError("a large real-world profile stays within the service JSON node limit", exception);
		}
	}

	private static void assertJsonLimit(String json, int maximumDepth, int maximumNodes, String message) {
		try {
			BoundedDungeonJsonParser.parse(json, maximumDepth, maximumNodes);
		} catch (IOException expected) {
			return;
		}
		throw new AssertionError(message);
	}

	private static void checkCurrentProfileShape() {
		String uuidText = "12345678123412341234123456789012";
		UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789012");
		String memberJson = "{" +
			"\"dungeons\":{" +
				"\"dungeon_types\":{" +
					"\"catacombs\":{\"experience\":569809640,\"tier_completions\":{\"1\":10}," +
						"\"fastest_time_s_plus\":{\"7\":360000}} ," +
					"\"master_catacombs\":{\"tier_completions\":{\"1\":2}}" +
				"}," +
				"\"player_classes\":{" +
					"\"mage\":{\"experience\":569809640},\"archer\":{\"experience\":50}," +
					"\"berserk\":{\"experience\":50},\"healer\":{\"experience\":50},\"tank\":{\"experience\":50}" +
				"},\"selected_dungeon_class\":\"mage\"" +
			"}," +
			"\"accessory_bag_storage\":{\"highest_magical_power\":2030}," +
			"\"pets_data\":{\"pets\":[{\"type\":\"GOLDEN_DRAGON\"}]}," +
			"\"inventory\":{\"inv_contents\":{\"type\":\"inventory\",\"data\":\"" +
				compressedItemNbt("TERMINATOR", "HYPERION") + "\"}}" +
		"}";
		String rootJson = "{\"profiles\":[{\"selected\":true,\"banking\":{\"balance\":123456}," +
			"\"members\":{" + quote(uuidText) + ":" + memberJson + "}}]}";
		DungeonStats stats = DungeonStatsService.parseForChecks("Example", uuid,
			JsonParser.parseString(rootJson).getAsJsonObject(),
			JsonParser.parseString(memberJson).getAsJsonObject(), 9876L);
		if (stats.totalSecrets() != 9876 || !stats.has(DungeonStats.DataField.SECRETS)) {
			throw new AssertionError("dedicated secrets data overrides the profile fallback");
		}
		if (stats.magicalPower() != 2030 || !stats.has(DungeonStats.DataField.MAGICAL_POWER)) {
			throw new AssertionError("nested magical power remains readable");
		}
		if (!stats.hasGearData(DungeonStats.Gear.TERMINATOR)
			|| !stats.hasGearData(DungeonStats.Gear.HYPERION)
			|| !stats.hasGearData(DungeonStats.Gear.GOLDEN_DRAGON)) {
			throw new AssertionError("current inventory and pet sections provide per-gear evidence");
		}
		if (!stats.has(DungeonStats.Gear.TERMINATOR) || !stats.has(DungeonStats.Gear.HYPERION)
			|| !stats.has(DungeonStats.Gear.GOLDEN_DRAGON)) {
			throw new AssertionError("current profile gear identities are parsed independently");
		}
		if (stats.itemDetails(DungeonStats.Gear.TERMINATOR).size() != 1
			|| !stats.itemDetails(DungeonStats.Gear.TERMINATOR).getFirst().displayName().equals("Terminator")
			|| !stats.itemDetails(DungeonStats.Gear.HYPERION).getFirst().lore().contains("Profile-provided lore")) {
			throw new AssertionError("compressed Odin inventory keeps weapon tooltip names and lore");
		}
		if (!stats.bankKnown() || stats.bank() != 123456) {
			throw new AssertionError("selected profile bank data remains readable");
		}
		if (!stats.has(DungeonStats.DataField.PERSONAL_BESTS)) {
			throw new AssertionError("personal best section availability is preserved");
		}
	}

	private static void checkUnavailableDetails() {
		UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789012");
		DungeonStats stats = DungeonStatsService.parseForChecks("Unavailable", uuid,
			new com.google.gson.JsonObject(), new com.google.gson.JsonObject(), null);
		if (stats.catacombsExperienceKnown() || stats.has(DungeonStats.DataField.CATACOMBS_LEVEL)
			|| stats.has(DungeonStats.DataField.CLASS_AVERAGE) || stats.has(DungeonStats.DataField.SECRETS)
			|| stats.has(DungeonStats.DataField.RUNS) || stats.has(DungeonStats.DataField.MAGICAL_POWER)
			|| stats.has(DungeonStats.DataField.BANK) || stats.has(DungeonStats.DataField.GEAR)
			|| stats.has(DungeonStats.DataField.PERSONAL_BESTS) || stats.cacheable()
			|| !stats.allItemDetails().isEmpty()
			|| !stats.goldenDragonPets().isEmpty() || stats.hasGearData(DungeonStats.Gear.TERMINATOR)
			|| stats.hasGearData(DungeonStats.Gear.GOLDEN_DRAGON)) {
			throw new AssertionError("missing profile inventory, pet, and Catacombs data stays unavailable");
		}
	}

	private static void checkKnownZeroFields() {
		UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789012");
		String compactUuid = "12345678123412341234123456789012";
		String memberJson = "{\"dungeons\":{" +
			"\"dungeon_types\":{\"catacombs\":{\"experience\":0,\"tier_completions\":{\"1\":0}," +
				"\"fastest_time_s_plus\":{}}}," +
			"\"player_classes\":{\"mage\":{\"experience\":0},\"archer\":{\"experience\":0}," +
				"\"berserk\":{\"experience\":0},\"healer\":{\"experience\":0},\"tank\":{\"experience\":0}}," +
			"\"total_secrets\":0,\"selected_dungeon_class\":\"mage\"}," +
			"\"accessory_bag_storage\":{\"highest_magical_power\":0}," +
			"\"inventory\":{\"inv_contents\":[{\"id\":\"minecraft:diamond\"}]}," +
			"\"pets_data\":{\"pets\":[]}}";
		String rootJson = "{\"profiles\":[{\"selected\":true,\"banking\":{\"balance\":0}," +
			"\"members\":{" + quote(compactUuid) + ":" + memberJson + "}}]}";
		DungeonStats stats = DungeonStatsService.parseForChecks("Known zeros", uuid,
			JsonParser.parseString(rootJson).getAsJsonObject(),
			JsonParser.parseString(memberJson).getAsJsonObject(), null);
		if (!stats.catacombsExperienceKnown() || stats.catacombsLevel() != 0
			|| !stats.has(DungeonStats.DataField.CATACOMBS_LEVEL)
			|| !stats.has(DungeonStats.DataField.CLASS_AVERAGE) || stats.classAverage() != 0
			|| !stats.has(DungeonStats.DataField.SECRETS) || stats.totalSecrets() != 0
			|| !stats.has(DungeonStats.DataField.RUNS) || stats.totalRuns() != 0
			|| !stats.has(DungeonStats.DataField.PERSONAL_BESTS)
			|| !stats.has(DungeonStats.DataField.MAGICAL_POWER) || stats.magicalPower() != 0
			|| !stats.bankKnown() || stats.bank() != 0 || !stats.has(DungeonStats.DataField.BANK)
			|| !stats.has(DungeonStats.DataField.GEAR) || !stats.cacheable()) {
			throw new AssertionError("valid zero values are known data, distinct from missing profile fields");
		}
	}

	private static void checkProfileDetails() {
		UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789012");
		com.google.gson.JsonObject catacombs = new com.google.gson.JsonObject();
		catacombs.addProperty("experience", 569_821_985L);
		com.google.gson.JsonObject dungeonTypes = new com.google.gson.JsonObject();
		dungeonTypes.add("catacombs", catacombs);
		com.google.gson.JsonObject dungeons = new com.google.gson.JsonObject();
		dungeons.add("dungeon_types", dungeonTypes);

		com.google.gson.JsonArray items = new com.google.gson.JsonArray();
		items.add(profileItem("TERMINATOR", "Terminator", "Shortbow: Instantly shoots!"));
		items.add(profileItem("TERMINATOR", "Terminator (Duplicate)", "Extra terminator detail"));
		items.add(profileItem("HYPERION", "Hyperion", "Wither Impact"));
		com.google.gson.JsonObject inventory = new com.google.gson.JsonObject();
		inventory.add("inv_contents", items);

		com.google.gson.JsonObject pet = new com.google.gson.JsonObject();
		pet.addProperty("type", "GOLDEN_DRAGON");
		pet.addProperty("tier", "LEGENDARY");
		pet.addProperty("level", 200);
		pet.addProperty("exp", 1_000_000_000L);
		pet.addProperty("heldItem", "PET_ITEM_TIER_BOOST");
		pet.addProperty("skin", "GOLDEN_DRAGON");
		pet.addProperty("active", true);
		com.google.gson.JsonArray pets = new com.google.gson.JsonArray();
		pets.add(pet);
		com.google.gson.JsonObject petsData = new com.google.gson.JsonObject();
		petsData.add("pets", pets);

		com.google.gson.JsonObject member = new com.google.gson.JsonObject();
		member.add("dungeons", dungeons);
		member.add("inventory", inventory);
		member.add("pets_data", petsData);
		DungeonStats stats = DungeonStatsService.parseForChecks("Detailed", uuid,
			new com.google.gson.JsonObject(), member, null);
		if (stats.itemDetails(DungeonStats.Gear.TERMINATOR).size() != 2
			|| stats.itemDetails(DungeonStats.Gear.HYPERION).size() != 1) {
			throw new AssertionError("all matching weapons are retained in the profile snapshot");
		}
		DungeonStats.ItemDetails terminator = stats.itemDetails(DungeonStats.Gear.TERMINATOR).getFirst();
		if (!terminator.displayName().contains("Terminator") || terminator.lore().isEmpty()
			|| !terminator.lore().getFirst().contains("Shortbow")
			|| !terminator.source().equals("Inventory")) {
			throw new AssertionError("weapon name, source, and lore survive profile parsing: "
				+ terminator.displayName() + " / " + terminator.source() + " / " + terminator.lore());
		}
		try {
			stats.allItemDetails().clear();
			throw new AssertionError("profile detail snapshots must be immutable");
		} catch (UnsupportedOperationException expected) {
			// Expected: consumers only receive the detached immutable snapshot.
		}
		if (stats.goldenDragonPets().size() != 1) {
			throw new AssertionError("Golden Dragon pet details are retained");
		}
		DungeonStats.GoldenDragonPet parsedPet = stats.goldenDragonPets().getFirst();
		if (!parsedPet.rarity().equals("LEGENDARY") || !parsedPet.level().equals("200")
			|| !parsedPet.experience().equals("1000000000")
			|| !parsedPet.heldItem().equals("PET_ITEM_TIER_BOOST")
			|| !parsedPet.skin().equals("GOLDEN_DRAGON") || !Boolean.TRUE.equals(parsedPet.active())) {
			throw new AssertionError("Golden Dragon tooltip fields survive profile parsing");
		}
	}

	private static com.google.gson.JsonObject profileItem(String id, String name, String lore) {
		com.google.gson.JsonObject display = new com.google.gson.JsonObject();
		display.addProperty("Name", name);
		com.google.gson.JsonArray loreLines = new com.google.gson.JsonArray();
		loreLines.add(lore);
		display.add("Lore", loreLines);
		com.google.gson.JsonObject tag = new com.google.gson.JsonObject();
		tag.add("display", display);
		com.google.gson.JsonObject item = new com.google.gson.JsonObject();
		item.addProperty("id", id);
		item.add("tag", tag);
		return item;
	}

	private static void checkCatacombsProgress() {
		DungeonStatsService.CataProgress level49 = DungeonStatsService.catacombsProgress(453_559_640L);
		if (level49.level() != 49 || level49.capped() || level49.experienceIntoLevel() != 0
			|| level49.experienceForLevel() != 116_250_000L) {
			throw new AssertionError("Catacombs hover reports progress from the exact level threshold");
		}
		DungeonStatsService.CataProgress capped = DungeonStatsService.catacombsProgress(569_821_985L);
		if (capped.level() != 50 || !capped.capped() || capped.overflowExperience() != 12_345L) {
			throw new AssertionError("Catacombs hover reports XP beyond the level-50 threshold");
		}
	}

	private static void checkJavaFacadeContracts() {
		assertRecordComponents(DungeonStatsService.Result.class, "stats", "error");
		assertRecordComponents(DungeonStatsService.CataProgress.class, "level", "totalExperience",
			"experienceIntoLevel", "experienceForLevel", "overflowExperience", "capped");
		assertStaticMethod(DungeonStatsService.class, "fetch", String.class,
			java.util.function.Consumer.class);
		assertStaticMethod(DungeonStatsService.class, "fetchFresh", String.class,
			java.util.function.Consumer.class);
		assertStaticMethod(DungeonStatsService.class, "clearCache");
		assertStaticMethod(DungeonStatsService.class, "close");
		assertStaticMethod(DungeonStatsService.class, "formatCatacombsLevel", long.class, boolean.class);
		assertStaticMethod(DungeonStatsService.class, "catacombsProgress", long.class);
		assertStaticMethod(DungeonStatsService.class, "formatTime", long.class);
		assertStaticMethod(DungeonStatsService.class, "parseForChecks", String.class, UUID.class,
			com.google.gson.JsonObject.class, com.google.gson.JsonObject.class, Long.class);
		assertStaticMethod(DungeonStatsService.class, "hasValidatedInventoryData",
			com.google.gson.JsonElement.class);
		assertStaticMethod(DungeonStatsService.class, "endpointAllowedForChecks", URI.class);
		assertStaticMethod(DungeonStatsService.class, "retryAfterForChecks", String.class);
		assertStaticMethod(DungeonStatsService.class, "encodePathForChecks", String.class);
		assertStaticMethod(DungeonStatsService.Result.class, "success", DungeonStats.class);
		assertStaticMethod(DungeonStatsService.Result.class, "failure", String.class);

		DungeonStatsService.Result absent = DungeonStatsService.Result.success(null);
		if (absent.available() || absent.stats() != null || absent.error() != null
			|| !absent.equals(new DungeonStatsService.Result(null, null))) {
			throw new AssertionError("nullable Java result factories retain record value behavior");
		}
		DungeonStatsService.CataProgress snapshot = new DungeonStatsService.CataProgress(
			50, 600_000_000L, 0L, 0L, 30_000_000L, true);
		if (snapshot.level() != 50 || snapshot.totalExperience() != 600_000_000L
			|| snapshot.overflowExperience() != 30_000_000L || !snapshot.capped()) {
			throw new AssertionError("Catacombs progress record accessors and constructor remain Java-compatible");
		}
	}

	private static void assertRecordComponents(Class<?> type, String... names) {
		java.lang.reflect.RecordComponent[] components = type.getRecordComponents();
		if (!type.isRecord() || components == null || components.length != names.length) {
			throw new AssertionError(type.getName() + " must retain native record metadata");
		}
		for (int i = 0; i < names.length; i++) {
			if (!components[i].getName().equals(names[i])) {
				throw new AssertionError(type.getName() + " record component order changed at " + i);
			}
		}
	}

	private static void assertStaticMethod(Class<?> type, String name, Class<?>... parameterTypes) {
		try {
			int modifiers = type.getDeclaredMethod(name, parameterTypes).getModifiers();
			if (!java.lang.reflect.Modifier.isStatic(modifiers)) {
				throw new AssertionError(type.getName() + "." + name + " must remain static");
			}
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("Missing Java API " + type.getName() + "." + name, exception);
		}
	}

	private static void checkModelSnapshotSemantics() {
		java.util.EnumMap<DungeonClass, Integer> classLevels = new java.util.EnumMap<>(DungeonClass.class);
		classLevels.put(DungeonClass.MAGE, 42);
		java.util.EnumMap<DungeonFloor, Long> personalBests = new java.util.EnumMap<>(DungeonFloor.class);
		personalBests.put(DungeonFloor.F7, 360L);
		java.util.EnumSet<DungeonStats.Gear> knownGear = java.util.EnumSet.allOf(DungeonStats.Gear.class);
		java.util.EnumSet<DungeonStats.Gear> gear = java.util.EnumSet.of(DungeonStats.Gear.HYPERION);
		java.util.EnumSet<DungeonStats.DataField> fields = java.util.EnumSet.allOf(DungeonStats.DataField.class);
		java.util.ArrayList<String> lore = new java.util.ArrayList<>(java.util.List.of("Profile lore"));
		DungeonStats.ItemDetails detail = new DungeonStats.ItemDetails(
			null, null, null, null, lore);
		java.util.ArrayList<DungeonStats.ItemDetails> details = new java.util.ArrayList<>(java.util.List.of(detail));
		java.util.ArrayList<DungeonStats.GoldenDragonPet> pets = new java.util.ArrayList<>(java.util.List.of(
			new DungeonStats.GoldenDragonPet(null, null, null, null, null, null)));
		DungeonStats stats = new DungeonStats(
			"Model", UUID.fromString("12345678-1234-1234-1234-123456789012"), -1,
			DungeonClass.MAGE, classLevels, 12.5, -5L, 2L, -4, -1L, true,
			knownGear, gear, personalBests, fields, -20L, true, details, pets);

		classLevels.clear();
		personalBests.clear();
		knownGear.clear();
		gear.clear();
		fields.clear();
		lore.add("mutated after snapshot");
		details.clear();
		pets.clear();

		if (stats.catacombsLevel() != -1 || stats.catacombsExperience() != 0L
			|| stats.totalSecrets() != 0L || stats.totalRuns() != 2L || stats.secretAverage() != 0.0
			|| stats.magicalPower() != 0 || stats.bank() != 0L || !stats.bankKnown()
			|| stats.classLevel(DungeonClass.MAGE) != 42 || stats.classAverage() != 12.5
			|| !stats.gearKnown() || !stats.hasGearData(DungeonStats.Gear.TERMINATOR)
			|| !stats.has(DungeonStats.Gear.HYPERION) || !stats.cacheable()
			|| !stats.hasPersonalBest(DungeonFloor.F7) || stats.fastestSPlusSeconds(DungeonFloor.F7) != 360L
			|| stats.hasPersonalBest(DungeonFloor.F6)) {
			throw new AssertionError("DungeonStats clamps values and detaches its immutable profile snapshot");
		}
		if (!stats.selectedClassLine(DungeonClass.MAGE).equals("Mage 42")
			|| !stats.selectedClassLine(null).equals("Class ?")
			|| !stats.allClassLevels().contains("Mage 42")
			|| !stats.allClassLevels().contains("Tank Unavailable")) {
			throw new AssertionError("class summaries retain selected and unavailable values");
		}
		try {
			stats.classLevels().put(DungeonClass.TANK, 1);
			throw new AssertionError("profile class-level snapshots remain immutable");
		} catch (UnsupportedOperationException expected) {
			// The profile model exposes detached immutable collections.
		}
		DungeonStats.ItemDetails normalized = stats.allItemDetails().getFirst();
		if (normalized.gear() != DungeonStats.Gear.TERMINATOR || !normalized.identifier().isEmpty()
			|| !normalized.displayName().isEmpty() || !normalized.source().isEmpty()
			|| !normalized.lore().equals(java.util.List.of("Profile lore"))
			|| !normalized.equals(new DungeonStats.ItemDetails(
				DungeonStats.Gear.TERMINATOR, "", "", "", java.util.List.of("Profile lore")))) {
			throw new AssertionError("item details preserve null normalization, value behavior, and copied lore");
		}
		try {
			normalized.lore().clear();
			throw new AssertionError("item lore snapshots remain immutable");
		} catch (UnsupportedOperationException expected) {
			// The item record exposes an immutable lore snapshot.
		}
		DungeonStats.GoldenDragonPet pet = stats.goldenDragonPets().getFirst();
		if (!pet.rarity().isEmpty() || !pet.level().isEmpty() || !pet.experience().isEmpty()
			|| !pet.heldItem().isEmpty() || !pet.skin().isEmpty() || pet.active() != null) {
			throw new AssertionError("Golden Dragon detail fields retain null normalization");
		}
	}

	private static String quote(String value) {
		return "\"" + value + "\"";
	}

	private static String compressedItemNbt(String... identifiers) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream nbt = new DataOutputStream(new GZIPOutputStream(bytes))) {
				nbt.writeByte(10); // Root compound.
				writeNbtString(nbt, "");
				nbt.writeByte(9); // A list of item compounds.
				writeNbtString(nbt, "Items");
				nbt.writeByte(10);
				nbt.writeInt(identifiers.length);
				for (String identifier : identifiers) {
					nbt.writeByte(8); // String tag named id.
					writeNbtString(nbt, "id");
					writeNbtString(nbt, identifier);
					nbt.writeByte(10); // Compound tag named tag.
					writeNbtString(nbt, "tag");
					nbt.writeByte(10); // display compound.
					writeNbtString(nbt, "display");
					nbt.writeByte(8); // Display name string.
					writeNbtString(nbt, "Name");
					writeNbtString(nbt, identifier.equals("HYPERION") ? "Hyperion" : "Terminator");
					nbt.writeByte(9); // List tag named Lore.
					writeNbtString(nbt, "Lore");
					nbt.writeByte(8); // List entries are strings.
					nbt.writeInt(1);
					writeNbtString(nbt, "Profile-provided lore");
					nbt.writeByte(0); // End display compound.
					nbt.writeByte(0); // End tag compound.
					nbt.writeByte(0); // End of item compound.
				}
				nbt.writeByte(0); // End of compound.
			}
			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		} catch (IOException exception) {
			throw new AssertionError("could not create compressed NBT test fixture", exception);
		}
	}

	private static void writeNbtString(DataOutputStream output, String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		output.writeShort(bytes.length);
		output.write(bytes);
	}

	private static void assertTrue(String json, String message) {
		if (!DungeonStatsService.hasValidatedInventoryData(JsonParser.parseString(json))) {
			throw new AssertionError(message);
		}
	}

	private static void assertFalse(String json, String message) {
		if (DungeonStatsService.hasValidatedInventoryData(JsonParser.parseString(json))) {
			throw new AssertionError(message);
		}
	}
}
