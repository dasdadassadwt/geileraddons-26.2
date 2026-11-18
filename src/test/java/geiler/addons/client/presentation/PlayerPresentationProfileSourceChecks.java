package geiler.addons.client.presentation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

/** Pure checks for the published profile location, bundled entry, bounds, and local fallback order. */
public final class PlayerPresentationProfileSourceChecks {
	private PlayerPresentationProfileSourceChecks() { }

	public static void main(String[] args) throws Exception { run(); }

	public static void run() throws Exception {
		expectedUrlMatchesTheEditableRepositoryFile();
		bundledFileContainsRequestedSizeProfile();
		exampleProfileIsSeparateAndFictional();
		profileBytesAreBoundedAndStrictUtf8();
		profileTreeIsBoundedBeforeConstruction();
		cacheAndBundledFallbackOrderIsStable();
		cacheSerializesAndReloadsFromAnIsolatedPath();
		invalidCachesFallBackWithoutChangingTheirBytes();
		bundledSchemaMatchesTheValidatedProfileContract();
		hostedRefreshTakesPrecedenceOverLocalSources();
		sourceRetainsJavaCallerAbi();
		storeRetainsJavaCallerAbi();
	}

	private static void sourceRetainsJavaCallerAbi() throws ReflectiveOperationException {
		var type = PlayerPresentationProfileSource.class;
		for (String name : new String[] { "REPOSITORY", "BRANCH", "REPOSITORY_PATH", "BUNDLED_RESOURCE",
			"RAW_PROFILE_URL" }) {
			assertPublicStaticFinalField(type, name, String.class);
		}
		assertPublicStaticFinalField(type, "MAX_BODY_BYTES", int.class);
		for (String name : new String[] { "CONNECT_TIMEOUT", "REQUEST_TIMEOUT", "RESPONSE_DEADLINE",
			"REFRESH_INTERVAL", "FAILURE_RETRY" }) {
			assertPublicStaticFinalField(type, name, java.time.Duration.class);
		}
		check(Modifier.isPrivate(type.getDeclaredConstructor().getModifiers()),
			"profile source remains a non-instantiable Java utility type");
	}

	private static void assertPublicStaticFinalField(Class<?> owner, String name, Class<?> type)
		throws ReflectiveOperationException {
		var field = owner.getField(name);
		check(field.getType() == type && Modifier.isPublic(field.getModifiers())
			&& Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
			"profile source keeps public static final Java field " + name);
	}

	private static void storeRetainsJavaCallerAbi() throws Exception {
		var type = PlayerPresentationProfileStore.class;
		var instance = type.getField("INSTANCE");
		var profileUrl = type.getField("PROFILE_URL");
		check(instance.getType() == type && Modifier.isPublic(instance.getModifiers())
			&& Modifier.isStatic(instance.getModifiers()) && Modifier.isFinal(instance.getModifiers()),
			"singleton remains a public static final Java field");
		check(profileUrl.getType() == String.class && Modifier.isPublic(profileUrl.getModifiers())
			&& Modifier.isStatic(profileUrl.getModifiers()) && Modifier.isFinal(profileUrl.getModifiers()),
			"profile URL remains a public static final Java field");
		check(type.getMethod("current").getReturnType() == PlayerPresentationProfiles.class
			&& !Modifier.isStatic(type.getMethod("current").getModifiers()),
			"current remains a public instance method for Java callers");
		check(type.getMethod("refreshIfDue").getReturnType() == void.class
			&& !Modifier.isStatic(type.getMethod("refreshIfDue").getModifiers()),
			"refreshIfDue remains a public instance method for Java callers");
		check(Modifier.isPrivate(type.getDeclaredConstructor().getModifiers()),
			"callers continue to obtain the store through its singleton field");
	}

	static void exampleProfileIsSeparateAndFictional() throws Exception {
		check(!"/data/player-presentations.example.json".equals(PlayerPresentationProfileSource.BUNDLED_RESOURCE),
			"the fictional example file is not the runtime profile resource");
		try (InputStream input = PlayerPresentationProfileSourceChecks.class
			.getResourceAsStream("/data/player-presentations.example.json")) {
			check(input != null, "the separate example profile resource exists");
			PlayerPresentationProfiles profile = PlayerPresentationProfiles.parseUtf8(
				input.readNBytes(PlayerPresentationProfileSource.MAX_BODY_BYTES + 1));
			check(profile.entries().size() == 2
				&& "Example Buddy".equals(profile.find("ExamplePlayer").alias())
				&& profile.find("ExamplePlayer").size() == 1.25f
				&& profile.find("GradientDemo").animation() != null,
				"fictional examples demonstrate aliases, color, gradient animation, and size");
		}
	}

	private static void expectedUrlMatchesTheEditableRepositoryFile() {
		equals("src/client/resources/data/player-presentations.json",
			PlayerPresentationProfileSource.REPOSITORY_PATH, "owner-editable repository path");
		equals("https://raw.githubusercontent.com/dasdadassadwt/geileraddons-26.2/main/src/client/resources/data/player-presentations.json",
			PlayerPresentationProfileSource.RAW_PROFILE_URL, "raw URL points to that exact main-branch path");
		check(PlayerPresentationProfileSource.CONNECT_TIMEOUT.toSeconds() == 3
			&& PlayerPresentationProfileSource.REQUEST_TIMEOUT.toSeconds() == 8
			&& PlayerPresentationProfileSource.RESPONSE_DEADLINE.toSeconds() == 9,
			"connect, request, and full-response deadlines are bounded");
		check(PlayerPresentationProfileSource.REFRESH_INTERVAL.toHours() == 6
			&& PlayerPresentationProfileSource.FAILURE_RETRY.toMinutes() == 15,
			"successful refresh and failure retry windows are bounded");
	}

	private static void bundledFileContainsRequestedSizeProfile() throws Exception {
		try (InputStream input = PlayerPresentationProfileSourceChecks.class
			.getResourceAsStream(PlayerPresentationProfileSource.BUNDLED_RESOURCE)) {
			check(input != null, "bundled owner-editable profile resource exists");
			byte[] bytes = input.readNBytes(PlayerPresentationProfileSource.MAX_BODY_BYTES + 1);
			check(bytes.length <= PlayerPresentationProfileSource.MAX_BODY_BYTES,
				"bundled profile is within the same byte limit as remote data");
			PlayerPresentationProfiles profile = PlayerPresentationProfiles.parseUtf8(bytes);
			PlayerPresentationProfiles.Entry entry = profile.find("ich_liebe_Pizza1");
			PlayerPresentationProfiles.Entry lanrt = profile.find("JstLnrt2");
			PlayerPresentationProfiles.Entry schmon = profile.find("Schmondooo");
			check(lanrt != null && Float.compare(lanrt.sizeX(), 4.0f) == 0
				&& Float.compare(lanrt.sizeY(), 1.0f) == 0 && Float.compare(lanrt.sizeZ(), 4.0f) == 0,
				"JstLnrt2 is sized 4, 1, 4");
			check(schmon != null && Float.compare(schmon.sizeX(), 2.0f) == 0
				&& Float.compare(schmon.sizeY(), 1.5f) == 0 && Float.compare(schmon.sizeZ(), 1.0f) == 0,
				"Schmondooo is sized 2, 1.5, 1");
			check(profile.entries().size() == 5 && entry != null && entry.alias() == null
				&& Float.compare(entry.sizeX(), 2.0f) == 0
				&& Float.compare(entry.sizeY(), 1.0f) == 0
				&& Float.compare(entry.sizeZ(), 1.0f) == 0
				&& entry.size() == null,
				"the bundled size-only profile preserves its per-axis model scale");
		}
	}

	private static void profileBytesAreBoundedAndStrictUtf8() throws Exception {
		byte[] oversized = new byte[PlayerPresentationProfileSource.MAX_BODY_BYTES + 1];
		fails(() -> PlayerPresentationProfiles.parseUtf8(oversized), "oversized profile bytes are rejected");
		fails(() -> PlayerPresentationProfiles.parseUtf8(new byte[] { (byte) 0xC3, 0x28 }),
			"malformed UTF-8 does not get silently replaced");
	}

	private static void profileTreeIsBoundedBeforeConstruction() throws Exception {
		String tooDeepEffect = "[".repeat(PlayerPresentationProfiles.MAX_JSON_DEPTH - 2) + "null"
			+ "]".repeat(PlayerPresentationProfiles.MAX_JSON_DEPTH - 2);
		String deep = "{\"schemaVersion\":1,\"players\":[{\"name\":\"One\",\"effect\":"
			+ tooDeepEffect + "}]}";
		failsWith(() -> PlayerPresentationProfiles.parse(deep), "nesting limit",
			"over-depth nested profile members are rejected by the streaming preflight");

		String duplicateMember = "{\"schemaVersion\":1,\"schemaVersion\":1,\"players\":[]}";
		failsWith(() -> PlayerPresentationProfiles.parse(duplicateMember), "duplicate JSON member",
			"duplicate JSON members are rejected before tree insertion");

		StringBuilder overNodes = new StringBuilder("{\"schemaVersion\":1,\"players\":[{\"name\":\"One\",\"effect\":[");
		for (int i = 0; i <= PlayerPresentationProfiles.MAX_JSON_NODES; i++) {
			if (i > 0) overNodes.append(',');
			overNodes.append("null");
		}
		overNodes.append("]}]} ");
		failsWith(() -> PlayerPresentationProfiles.parse(overNodes.toString()), "node limit",
			"over-budget value nodes fail before the full Gson tree is built");

		StringBuilder overTokens = new StringBuilder("{\"schemaVersion\":1,\"players\":[],");
		for (int i = 0; i <= PlayerPresentationProfiles.MAX_JSON_TOKENS / 2; i++) {
			if (i > 0) overTokens.append(',');
			overTokens.append("\"k").append(i).append("\":0");
		}
		overTokens.append('}');
		failsWith(() -> PlayerPresentationProfiles.parse(overTokens.toString()), "token limit",
			"over-budget member/value tokens fail during streaming preflight");

		String validNumber = "{\"schemaVersion\":1,\"players\":[{\"name\":\"Numeric\",\"size\":1.25}]}";
		check(Math.abs(PlayerPresentationProfiles.parse(validNumber).find("Numeric").size() - 1.25f) < 0.0001f,
			"the bounded reader preserves numeric values for profile validation");
	}

	private static void cacheAndBundledFallbackOrderIsStable() throws Exception {
		PlayerPresentationProfiles cached = PlayerPresentationProfiles.parse("""
			{"schemaVersion":1,"players":[{"name":"CacheEntry","alias":"cached"}]}
			""");
		PlayerPresentationProfiles bundled = PlayerPresentationProfiles.parse("""
			{"schemaVersion":1,"players":[{"name":"BundleEntry","size":1.25}]}
			""");
		check(PlayerPresentationProfileFallback.local(cached, bundled) == cached,
			"a valid cache is preferred over bundled defaults");
		check(PlayerPresentationProfileFallback.local(null, bundled) == bundled,
			"bundled defaults provide fallback when cache is unavailable or invalid");
		check(PlayerPresentationProfileFallback.local(null, null) == PlayerPresentationProfiles.empty(),
			"missing local sources safely fall back to an empty profile");
	}

	private static void cacheSerializesAndReloadsFromAnIsolatedPath() throws Exception {
		Path directory = Files.createTempDirectory("geileraddons-player-presentations-");
		try {
			Path cache = directory.resolve("player-presentations-cache.json");
			String json = """
				{"schemaVersion":1,"players":[{"name":"CacheEntry","alias":"cached","gradient":["#112233","#AABBCC"],"animation":{"mode":"scroll","speed":0.5}}]}
				""";
			check(PlayerPresentationProfileCacheIo.writeCache(cache, json).get(10, TimeUnit.SECONDS),
				"the validated hosted JSON is written through the isolated cache path");
			check(json.equals(Files.readString(cache, StandardCharsets.UTF_8)),
				"cache persistence retains the fetched JSON bytes without changing its profile fields");
			try (var paths = Files.list(directory)) {
				check(paths.count() == 1, "atomic cache writing leaves no temporary files behind");
			}

			PlayerPresentationProfiles loaded = PlayerPresentationProfileCacheIo.loadLocal(cache,
				PlayerPresentationProfileStore.class, PlayerPresentationProfileSource.BUNDLED_RESOURCE);
			check(loaded.find("CacheEntry") != null
				&& "cached".equals(loaded.find("CacheEntry").alias())
				&& loaded.find("CacheEntry").gradient().size() == 2
				&& loaded.find("CacheEntry").animation() != null
				&& loaded.find("ich_liebe_Pizza1") == null,
				"a valid local cache overrides the bundled editable profile and reloads its styles");

			byte[] beforeOversizedWrite = Files.readAllBytes(cache);
			String oversized = " ".repeat(PlayerPresentationProfileSource.MAX_BODY_BYTES + 1);
			check(!PlayerPresentationProfileCacheIo.writeCache(cache, oversized).get(10, TimeUnit.SECONDS)
				&& java.util.Arrays.equals(beforeOversizedWrite, Files.readAllBytes(cache)),
				"oversized cache serialization is refused without replacing the last valid file");
		} finally {
			deleteTree(directory);
		}
	}

	private static void invalidCachesFallBackWithoutChangingTheirBytes() throws Exception {
		Path directory = Files.createTempDirectory("geileraddons-player-presentations-invalid-");
		try {
			Path cache = directory.resolve("player-presentations-cache.json");
			assertBundledFallback(cache, "a missing cache falls back to the bundled editable profile");

			byte[][] invalidInputs = {
				"{\"schemaVersion\":1,\"players\":[{\"name\":\"bad name\"}]}".getBytes(StandardCharsets.UTF_8),
				new byte[] { (byte) 0xC3, 0x28 },
				new byte[PlayerPresentationProfileSource.MAX_BODY_BYTES + 1],
			};
			String[] descriptions = { "semantically invalid", "malformed UTF-8", "oversized" };
			for (int index = 0; index < invalidInputs.length; index++) {
				Files.write(cache, invalidInputs[index]);
				byte[] before = Files.readAllBytes(cache);
				assertBundledFallback(cache, descriptions[index] + " cache falls back to the bundled profile");
				check(java.util.Arrays.equals(before, Files.readAllBytes(cache)),
					descriptions[index] + " cache bytes are retained for diagnosis or recovery");
			}

			Files.delete(cache);
			PlayerPresentationProfiles noResource = PlayerPresentationProfileCacheIo.loadLocal(cache,
				PlayerPresentationProfileStore.class, "/data/missing-player-presentations.json");
			check(noResource == PlayerPresentationProfiles.empty(),
				"missing cache and bundled resource safely resolve to an empty profile");
		} finally {
			deleteTree(directory);
		}
	}

	private static void assertBundledFallback(Path cache, String message) {
		PlayerPresentationProfiles loaded = PlayerPresentationProfileCacheIo.loadLocal(cache,
			PlayerPresentationProfileStore.class, PlayerPresentationProfileSource.BUNDLED_RESOURCE);
		var entry = loaded.find("ich_liebe_Pizza1");
		check(entry != null && Float.compare(entry.sizeX(), 2.0f) == 0
			&& Float.compare(entry.sizeY(), 1.0f) == 0 && Float.compare(entry.sizeZ(), 1.0f) == 0,
			message);
	}

	private static void bundledSchemaMatchesTheValidatedProfileContract() throws Exception {
		String schemaText;
		try (InputStream input = PlayerPresentationProfileSourceChecks.class
			.getResourceAsStream("/data/player-presentations.schema.json")) {
			check(input != null, "the profile schema is packaged separately from runtime and example JSON");
			byte[] bytes = input.readNBytes(PlayerPresentationProfileSource.MAX_BODY_BYTES + 1);
			check(bytes.length <= PlayerPresentationProfileSource.MAX_BODY_BYTES,
				"the schema remains within the same bounded resource policy");
			schemaText = new String(bytes, StandardCharsets.UTF_8);
		}
		JsonObject schema = JsonParser.parseString(schemaText).getAsJsonObject();
		JsonObject properties = schema.getAsJsonObject("properties");
		JsonObject players = properties.getAsJsonObject("players");
		JsonObject entryProperties = players.getAsJsonObject("items").getAsJsonObject("properties");
		check(schema.get("additionalProperties").getAsBoolean() == false
			&& properties.getAsJsonObject("schemaVersion").get("const").getAsInt()
				== PlayerPresentationProfiles.SCHEMA_VERSION
			&& players.get("maxItems").getAsInt() == PlayerPresentationProfiles.MAX_PLAYERS,
			"schema root, version, and player limit match the strict profile parser");
		check(entryProperties.keySet().containsAll(java.util.Set.of("name", "alias", "size", "sizeX",
			"sizeY", "sizeZ", "effect", "color", "gradient", "animation"))
			&& entryProperties.getAsJsonObject("gradient").get("minItems").getAsInt()
				== PlayerPresentationProfiles.MIN_GRADIENT_STOPS
			&& entryProperties.getAsJsonObject("gradient").get("maxItems").getAsInt()
				== PlayerPresentationProfiles.MAX_GRADIENT_STOPS,
			"schema preserves the parser's profile fields and gradient bounds");
	}

	private static void hostedRefreshTakesPrecedenceOverLocalSources() throws Exception {
		PlayerPresentationProfiles local = PlayerPresentationProfiles.parse(
			"{\"schemaVersion\":1,\"players\":[{\"name\":\"LocalEntry\"}]}");
		PlayerPresentationProfiles hosted = PlayerPresentationProfiles.parse(
			"{\"schemaVersion\":1,\"players\":[{\"name\":\"HostedEntry\"}]}");
		check(PlayerPresentationProfileFallback.hosted(hosted, local) == hosted,
			"a successfully validated hosted refresh replaces cached or bundled local data");
		check(PlayerPresentationProfileFallback.hosted(null, local) == local
			&& PlayerPresentationProfileFallback.hosted(null, null) == PlayerPresentationProfiles.empty(),
			"a failed or absent hosted refresh retains the last local source");
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	@FunctionalInterface
	private interface IoAction { void run() throws IOException; }

	private static void fails(IoAction action, String message) throws Exception {
		try {
			action.run();
			throw new AssertionError(message);
		} catch (IOException expected) {
			// Invalid source bytes must be rejected.
		}
	}

	private static void failsWith(IoAction action, String expectedMessage, String message) throws Exception {
		try {
			action.run();
			throw new AssertionError(message);
		} catch (IOException expected) {
			if (expected.getMessage() == null || !expected.getMessage().contains(expectedMessage)) {
				throw new AssertionError(message + ": expected diagnostic containing '" + expectedMessage
					+ "', got '" + expected.getMessage() + "'", expected);
			}
		}
	}

	private static void equals(Object expected, Object actual, String message) {
		if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
