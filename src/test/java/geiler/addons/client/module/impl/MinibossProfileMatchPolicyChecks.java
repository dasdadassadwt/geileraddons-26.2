package geiler.addons.client.module.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/** JDK-only acceptance checks for appearance-match identity exclusions and capture provenance. */
public final class MinibossProfileMatchPolicyChecks {
	private MinibossProfileMatchPolicyChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		UUID localId = UUID.randomUUID();
		UUID partyId = UUID.randomUUID();
		UUID otherId = UUID.randomUUID();
		var identities = new MinibossProfileMatchPolicy.IdentitySnapshot(localId, "LocalPlayer",
			Set.of(partyId), Set.of("PartyMate"));
		var appearance = MinibossProfileMatchPolicy.MatchSource.REVIEWED_APPEARANCE;
		var exactName = MinibossProfileMatchPolicy.MatchSource.EXACT_PROFILE_NAME;

		check(!MinibossProfileMatchPolicy.allows(appearance, localId, "NPC-1", identities),
			"a catalog appearance cannot classify the local UUID");
		check(!MinibossProfileMatchPolicy.allows(appearance, otherId, "localplayer", identities),
			"a catalog appearance cannot classify the local player by normalized name");
		check(!MinibossProfileMatchPolicy.allows(appearance, partyId, "NPC-2", identities),
			"a catalog appearance cannot classify a current party UUID");
		check(!MinibossProfileMatchPolicy.allows(appearance, otherId, "partymate", identities),
			"a catalog appearance cannot classify a current party member by normalized name");
		check(MinibossProfileMatchPolicy.allows(appearance, otherId, "NPC-3", identities),
			"a catalog appearance remains usable for an identity outside the current party snapshot");
		check(MinibossProfileMatchPolicy.allows(exactName, localId, "Shadow Assassin", identities)
			&& MinibossProfileMatchPolicy.allows(exactName, partyId, "Lost Adventurer", identities),
			"exact recognized profile names retain precedence over local and party appearance exclusions");
		check(MinibossProfileMatchPolicy.allows(appearance, localId, "NPC-4",
			MinibossProfileMatchPolicy.IdentitySnapshot.empty()),
			"an unavailable roster contributes no stale persisted identity exclusions");
		check(!MinibossProfileMatchPolicy.allowsAutomaticCapture(localId, "NPC-5", identities)
			&& !MinibossProfileMatchPolicy.allowsAutomaticCapture(otherId, "localplayer", identities)
			&& !MinibossProfileMatchPolicy.allowsAutomaticCapture(partyId, "NPC-6", identities)
			&& !MinibossProfileMatchPolicy.allowsAutomaticCapture(otherId, "PartyMate", identities)
			&& MinibossProfileMatchPolicy.allowsAutomaticCapture(otherId, "DungeonNpc", identities),
			"automatic capture excludes local and current-party UUIDs or names");
		check("Frozen Adventurer".equals(MinibossProfileMatchPolicy.uniqueRecognizedMinibossLabel(
			java.util.List.of("Frozen Adventurer", "Frozen Adventurer")))
			&& MinibossProfileMatchPolicy.uniqueRecognizedMinibossLabel(
				java.util.List.of("Frozen Adventurer", "King Midas")) == null
			&& MinibossProfileMatchPolicy.uniqueRecognizedMinibossLabel(
				java.util.List.of("Unrecognized Mob")) == null
			&& MinibossProfileMatchPolicy.uniqueRecognizedMinibossLabel(
				java.util.Arrays.asList("Frozen Adventurer", null)) == null,
			"only one unanimous recognized miniboss nameplate label can confirm an appearance");
		check(MinibossProfileMatchPolicy.shouldCaptureAutomatically(exactName)
			&& !MinibossProfileMatchPolicy.shouldCaptureAutomatically(appearance),
			"only exact profile-name matches can be recorded as confirmed-profile evidence");
		var confirmedNameplate = MinibossProfileMatchPolicy.AutomaticCaptureEvidence.UNIQUE_RECOGNIZED_NAMEPLATE;
		var reviewedSkin = MinibossProfileMatchPolicy.AutomaticCaptureEvidence.REVIEWED_APPEARANCE;
		var manual = MinibossProfileMatchPolicy.AutomaticCaptureEvidence.MANUAL_CONFIRMATION;
		check(MinibossProfileMatchPolicy.shouldCaptureAppearanceAutomatically(confirmedNameplate,
			"Frozen Adventurer", otherId, "DungeonNpc", identities, false)
			&& MinibossProfileMatchPolicy.shouldCaptureAppearanceAutomatically(
				MinibossProfileMatchPolicy.AutomaticCaptureEvidence.EXACT_PROFILE_NAME,
				"King Midas", otherId, "King Midas", identities, false),
			"a recognized, confirmed nameplate or exact profile can begin one automatic appearance capture");
		check(!MinibossProfileMatchPolicy.shouldCaptureAppearanceAutomatically(confirmedNameplate,
			"Unknown Mob", otherId, "DungeonNpc", identities, false)
			&& !MinibossProfileMatchPolicy.shouldCaptureAppearanceAutomatically(reviewedSkin,
				"Frozen Adventurer", otherId, "DungeonNpc", identities, false)
			&& !MinibossProfileMatchPolicy.shouldCaptureAppearanceAutomatically(manual,
				"Frozen Adventurer", otherId, "DungeonNpc", identities, false),
			"unknown labels, catalog-derived matches, and manual captures cannot enter the automatic path");
		check(!MinibossProfileMatchPolicy.shouldCaptureAppearanceAutomatically(confirmedNameplate,
			"Frozen Adventurer", localId, "DungeonNpc", identities, false)
			&& !MinibossProfileMatchPolicy.shouldCaptureAppearanceAutomatically(confirmedNameplate,
				"Frozen Adventurer", partyId, "DungeonNpc", identities, false)
			&& !MinibossProfileMatchPolicy.shouldCaptureAppearanceAutomatically(confirmedNameplate,
				"Frozen Adventurer", otherId, "PartyMate", identities, false),
			"nameplate-based capture excludes local and current-party identities by UUID or name");
		check(!MinibossProfileMatchPolicy.shouldCaptureAppearanceAutomatically(confirmedNameplate,
			"Frozen Adventurer", otherId, "DungeonNpc", identities, true),
			"an automatically inspected profile cannot trigger a second capture attempt during the session");

		checkCatalogStorePersistence();
	}

	private static void checkCatalogStorePersistence() {
		Path temporaryDirectory = null;
		Path catalogFile = null;
		try {
			temporaryDirectory = Files.createTempDirectory("geileraddons-miniboss-catalog-");
			catalogFile = temporaryDirectory.resolve("nested/geileraddons/miniboss-appearance-signatures.json");

			var missing = MinibossAppearanceCatalogStore.loadFromPathForChecks(catalogFile);
			check(missing.catalog().reviewedEntryCount() == 0 && missing.status().isEmpty(),
				"a missing local catalog loads empty without a warning");
			check(Files.isRegularFile(catalogFile)
				&& MinibossAppearanceCatalog.emptyTemplate().equals(Files.readString(catalogFile)),
				"a missing local catalog creates the frozen empty v1 template at the existing config-relative path");

			String reviewedSignature = "sha256:" + "a".repeat(64);
			String unreviewedSignature = "sha256:" + "b".repeat(64);
			String validJson = catalogJson(profileJson(reviewedSignature, true), profileJson(unreviewedSignature, false));
			Files.writeString(catalogFile, validJson, StandardCharsets.UTF_8);
			var valid = MinibossAppearanceCatalogStore.loadFromPathForChecks(catalogFile);
			check(valid.catalog().reviewedEntryCount() == 1
				&& "Frozen Adventurer".equals(valid.catalog().labelFor(
					new MinibossAppearanceCapture.AppearanceIdentity(reviewedSignature, "classic")))
				&& valid.catalog().labelFor(
					new MinibossAppearanceCapture.AppearanceIdentity(unreviewedSignature, "classic")) == null,
				"the production reader loads reviewed signatures while keeping unreviewed entries inactive");
			check("ignored 1 unreviewed appearance entries".equals(valid.status())
				&& validJson.equals(Files.readString(catalogFile)),
				"partial-load diagnostics are detailed and successful reads do not rewrite user data");

			byte[] invalidJson = "{ invalid catalog".getBytes(StandardCharsets.UTF_8);
			Files.write(catalogFile, invalidJson);
			var invalid = MinibossAppearanceCatalogStore.loadFromPathForChecks(catalogFile);
			check(invalid.catalog().reviewedEntryCount() == 0 && invalid.status().startsWith("invalid catalog:")
				&& Arrays.equals(invalidJson, Files.readAllBytes(catalogFile)),
				"invalid JSON fails closed and preserves the user's exact bytes");

			byte[] malformedUtf8 = validJson.getBytes(StandardCharsets.UTF_8);
			int sourceText = validJson.indexOf("Mojang profile texture property")
				+ "Mojang profile texture ".length();
			malformedUtf8[sourceText] = (byte) 0xFF;
			Files.write(catalogFile, malformedUtf8);
			var replacementDecoded = MinibossAppearanceCatalogStore.loadFromPathForChecks(catalogFile);
			check(replacementDecoded.catalog().reviewedEntryCount() == 1
				&& "Frozen Adventurer".equals(replacementDecoded.catalog().labelFor(
					new MinibossAppearanceCapture.AppearanceIdentity(reviewedSignature, "classic")))
				&& "ignored 1 unreviewed appearance entries".equals(replacementDecoded.status())
				&& Arrays.equals(malformedUtf8, Files.readAllBytes(catalogFile)),
				"malformed UTF-8 retains the legacy replacement-decoding behavior without rewriting source bytes");

			byte[] oversized = new byte[MinibossAppearanceCatalog.MAX_BYTES + 1];
			Arrays.fill(oversized, (byte) 'x');
			Files.write(catalogFile, oversized);
			var tooLarge = MinibossAppearanceCatalogStore.loadFromPathForChecks(catalogFile);
			check(tooLarge.catalog().reviewedEntryCount() == 0
				&& tooLarge.status().contains("64 KiB")
				&& Arrays.equals(oversized, Files.readAllBytes(catalogFile)),
				"oversized catalogs fail closed without truncating or replacing user data");

			String longKey = "unknown-" + "x".repeat(300) + "\nInjected log line";
			String escapedKey = longKey.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\r", "\\r").replace("\n", "\\n");
			byte[] diagnosticInput = ("{\"" + escapedKey + "\":true}").getBytes(StandardCharsets.UTF_8);
			Files.write(catalogFile, diagnosticInput);
			var boundedDiagnostic = MinibossAppearanceCatalogStore.loadFromPathForChecks(catalogFile);
			check(boundedDiagnostic.status().length() <= 240
				&& boundedDiagnostic.status().chars().noneMatch(Character::isISOControl)
				&& boundedDiagnostic.status().startsWith("invalid catalog:")
				&& Arrays.equals(diagnosticInput, Files.readAllBytes(catalogFile)),
				"catalog diagnostics are length-bounded, single-line, and leave rejected bytes untouched");
		} catch (IOException error) {
			throw new AssertionError("isolated miniboss catalog persistence check failed", error);
		} finally {
			if (temporaryDirectory != null && catalogFile != null) deleteTemporaryCatalog(temporaryDirectory, catalogFile);
		}
	}

	private static String catalogJson(String... profiles) {
		return "{\n  \"schema\": \"" + MinibossAppearanceCatalog.SCHEMA + "\",\n"
			+ "  \"version\": " + MinibossAppearanceCatalog.VERSION + ",\n"
			+ "  \"profiles\": [" + String.join(",", profiles) + "]\n}\n";
	}

	private static String profileJson(String signature, boolean reviewed) {
		return "{\"signature\":\"" + signature + "\",\"skinModel\":\"classic\","
			+ "\"label\":\"Frozen Adventurer\",\"source\":\"Mojang profile texture property\","
			+ "\"sourceReference\":\"manual local capture\",\"license\":\"Mojang profile data\","
			+ "\"evidence\":\"human-reviewed texture signature and model\",\"reviewed\":" + reviewed + "}";
	}

	private static void deleteTemporaryCatalog(Path root, Path file) {
		try {
			Files.deleteIfExists(file);
			Files.deleteIfExists(file.getParent());
			Files.deleteIfExists(file.getParent().getParent());
			Files.deleteIfExists(root);
		} catch (IOException error) {
			throw new AssertionError("could not remove isolated miniboss catalog fixture", error);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
