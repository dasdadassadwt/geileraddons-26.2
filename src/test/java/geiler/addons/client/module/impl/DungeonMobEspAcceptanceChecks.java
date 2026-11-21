package geiler.addons.client.module.impl;

import com.mojang.authlib.GameProfile;
import geiler.addons.client.dungeon.DungeonRoomTracker;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Isolated no-server checks for room filtering, markers, miniboss identity, and capture boundaries. */
public final class DungeonMobEspAcceptanceChecks {
	private DungeonMobEspAcceptanceChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		DungeonMobEspModuleKotlinChecks.run();
		MinibossProfileMatchPolicyChecks.run();
		checkConfirmedRoomFootprints();
		checkFelMarkerAnchor();
		checkShadowAssassinIdentityAndRendering();
		checkDevonianMobClassificationAndPacketTracking();
		checkManualCaptureReadiness();
		checkAppearanceCaptureBoundaries();
		checkJavaStringSemantics();
		checkReviewedAppearanceMatching();
		checkLoadedEntityMemory();
	}

	private static void checkAppearanceCaptureBoundaries() {
		String url = "http://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef";
		String firstValue = texturePropertyValue(100L, url, "slim");
		String renewedValue = texturePropertyValue(200L, url, "slim");
		var firstIdentity = MinibossAppearanceCapture.appearanceIdentityFromTexturePropertyValue(firstValue);
		var renewedIdentity = MinibossAppearanceCapture.appearanceIdentityFromTexturePropertyValue(renewedValue);
		String firstSignature = firstIdentity == null ? null : firstIdentity.textureSignature();
		String renewedSignature = renewedIdentity == null ? null : renewedIdentity.textureSignature();
		check(firstSignature != null && firstSignature.equals(renewedSignature)
			&& firstSignature.matches("sha256:[0-9a-f]{64}") && "slim".equals(firstIdentity.model()),
			"texture identity hashing stays stable across refreshed profile payloads and records a safe model variant");
		var classicIdentity = MinibossAppearanceCapture.appearanceIdentityFromTexturePropertyValue(
			texturePropertyValue(250L, url, null));
		var unknownModelIdentity = MinibossAppearanceCapture.appearanceIdentityFromTexturePropertyValue(
			texturePropertyValue(260L, url, "unrecognized-model"));
		check(classicIdentity != null && "classic".equals(classicIdentity.model())
			&& unknownModelIdentity != null && "unknown".equals(unknownModelIdentity.model()),
			"model metadata is normalized to classic, slim, or unknown without preserving arbitrary values");
		check(MinibossAppearanceCapture.skinSignatureFromTexturePropertyValue(
			texturePropertyValue(300L, "https://example.com/texture/0123456789abcdef0123456789abcdef", "slim")) == null,
			"appearance capture rejects non-Mojang texture URLs");
		check(MinibossAppearanceCapture.skinSignatureFromTexturePropertyValue("not-base64") == null,
			"malformed profile texture data fails closed");
		String deepJson = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}},\"extra\":"
			+ "[".repeat(20) + "0" + "]".repeat(20) + "}";
		check(MinibossAppearanceCapture.skinSignatureFromTexturePropertyValue(encodeJson(deepJson)) == null,
			"deeply nested profile JSON is rejected before Gson tree parsing");
		String flatJson = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}},\"extra\":["
			+ "0,".repeat(2_300) + "0]}";
		check(MinibossAppearanceCapture.skinSignatureFromTexturePropertyValue(encodeJson(flatJson)) == null,
			"flat profile arrays exceeding the token budget are rejected before Gson tree parsing");

		UUID localId = UUID.randomUUID();
		UUID partyId = UUID.randomUUID();
		UUID npcId = UUID.randomUUID();
		check(MinibossAppearanceCapture.isExcludedPlayerProfile(localId, localId, "Local", false,
			Set.of(), Set.of()), "manual appearance capture excludes the local player");
		check(MinibossAppearanceCapture.isExcludedPlayerProfile(localId, partyId, "PartyMate", false,
			Set.of(partyId), Set.of()), "manual appearance capture excludes party-member UUIDs");
		check(MinibossAppearanceCapture.isExcludedPlayerProfile(localId, UUID.randomUUID(), "PartyMate", false,
			Set.of(), Set.of("partymate")), "manual appearance capture excludes party names case-insensitively");
		check(MinibossAppearanceCapture.isExcludedPlayerProfile(localId, UUID.randomUUID(), "OnlinePlayer", true,
			Set.of(), Set.of()), "manual appearance capture excludes profiles listed as online players");
		check(!MinibossAppearanceCapture.isExcludedPlayerProfile(localId, npcId, "Lost Adventurer", false,
			Set.of(partyId), Set.of("PartyMate")), "unlisted non-party NPC profile remains eligible for manual review");
		var currentIdentities = new MinibossProfileMatchPolicy.IdentitySnapshot(localId, "LocalPlayer",
			Set.of(partyId), Set.of("PartyMate"));
		check(!MinibossProfileMatchPolicy.allowsAutomaticCapture(localId, "Shadow Assassin", currentIdentities)
			&& !MinibossProfileMatchPolicy.allowsAutomaticCapture(partyId, "Shadow Assassin", currentIdentities)
			&& MinibossProfileMatchPolicy.allowsAutomaticCapture(npcId, "NPC-42", currentIdentities),
			"automatic capture excludes local and party identities even when the recognized label looks like a miniboss");

		MinibossAppearanceCapture capture = new MinibossAppearanceCapture();
		List<String> output = new ArrayList<>();
		GameProfile profile = new GameProfile(UUID.randomUUID(), "Lost Adventurer");
		var recorded = capture.record(MinibossAppearanceCapture.Source.MANUAL_CONFIRMATION,
			"Lost \"Adventurer\"\nmanual", profile, 71, firstSignature, "slim", 123L, output::add);
		check(recorded == MinibossAppearanceCapture.RecordResult.QUEUED && output.size() == 1,
			"manual confirmation queues one bounded JSON observation without claiming it is flushed");
		String row = output.getFirst();
		check(row.contains("\"schema\":\"geileraddons-miniboss-appearance-v2\"")
			&& row.contains("\\\"Adventurer\\\"") && row.contains("\\n") && !row.contains(firstValue)
			&& !row.contains(url) && row.contains(firstSignature) && row.contains("\"skinModel\":\"slim\"")
			&& !row.contains(profile.id().toString()) && !row.contains("profileId"),
			"output escapes labels, keeps safe texture/model identity, and omits URL, payload, and profile UUID");
		check(capture.record(MinibossAppearanceCapture.Source.MANUAL_CONFIRMATION,
			"Lost \"Adventurer\"\nmanual", profile, 71, firstSignature, "slim", 124L, output::add)
			== MinibossAppearanceCapture.RecordResult.DUPLICATE && output.size() == 1,
			"same confirmed profile, skin hash, and model is deduplicated while queued or flushed");
		check(capture.record(MinibossAppearanceCapture.Source.MANUAL_CONFIRMATION,
			"Lost \"Adventurer\"\nmanual", profile, 71, firstSignature, "classic", 125L, output::add)
			== MinibossAppearanceCapture.RecordResult.QUEUED && output.size() == 2,
			"the same texture with a different model variant remains a distinct appearance observation");
		check(capture.forgetUnflushedRecord(profile, "Lost \"Adventurer\"\nmanual", firstSignature, "slim")
			&& capture.recordedCount() == 1
			&& capture.record(MinibossAppearanceCapture.Source.MANUAL_CONFIRMATION,
				"Lost \"Adventurer\"\nmanual", profile, 71, firstSignature, "slim", 126L, output::add)
			== MinibossAppearanceCapture.RecordResult.QUEUED && output.size() == 3,
			"a failed asynchronous write releases its reservation so an explicit retry can be queued");
		GameProfile rejectedProfile = new GameProfile(UUID.randomUUID(), "Lost Adventurer");
		check(capture.record(MinibossAppearanceCapture.Source.MANUAL_CONFIRMATION,
			"retry", rejectedProfile, 72, firstSignature, "slim", 127L, line -> false)
			== MinibossAppearanceCapture.RecordResult.QUEUE_REJECTED && capture.recordedCount() == 2
			&& capture.record(MinibossAppearanceCapture.Source.MANUAL_CONFIRMATION,
				"retry", rejectedProfile, 72, firstSignature, "slim", 128L, output::add)
			== MinibossAppearanceCapture.RecordResult.QUEUED && output.size() == 4,
			"a rejected queue attempt consumes no dedup or session-cap slot and can be retried");
		check(!MinibossAppearanceCapture.shouldMarkAutomaticallyInspected(
				MinibossAppearanceCapture.RecordResult.QUEUE_REJECTED)
			&& !MinibossAppearanceCapture.shouldMarkAutomaticallyInspected(
				MinibossAppearanceCapture.RecordResult.INVALID)
			&& MinibossAppearanceCapture.shouldMarkAutomaticallyInspected(
				MinibossAppearanceCapture.RecordResult.QUEUED)
			&& MinibossAppearanceCapture.shouldMarkAutomaticallyInspected(
				MinibossAppearanceCapture.RecordResult.DUPLICATE)
			&& MinibossAppearanceCapture.shouldMarkAutomaticallyInspected(
				MinibossAppearanceCapture.RecordResult.LIMIT_REACHED),
			"automatic capture retries queue-rejected or invalid profiles but suppresses queued, duplicate, and capped results");
		MinibossAppearanceCapture loggerRecoveryRetry = new MinibossAppearanceCapture();
		GameProfile recoveryProfile = new GameProfile(UUID.randomUUID(), "Lost Adventurer");
		List<String> recoveredOutput = new ArrayList<>();
		boolean[] loggerRecovered = {false};
		MinibossAppearanceCapture.JsonLineSink recoveredSink = line -> {
			if (!loggerRecovered[0]) return false;
			recoveredOutput.add(line);
			return true;
		};
		var unavailable = loggerRecoveryRetry.record(MinibossAppearanceCapture.Source.CONFIRMED_PROFILE,
			"Shadow Assassin", recoveryProfile, 73, firstSignature, "slim", 129L, recoveredSink);
		check(unavailable == MinibossAppearanceCapture.RecordResult.QUEUE_REJECTED
			&& !MinibossAppearanceCapture.shouldMarkAutomaticallyInspected(unavailable)
			&& !loggerRecoveryRetry.wasAutomaticallyInspected(recoveryProfile.id())
			&& loggerRecoveryRetry.recordedCount() == 0,
			"automatic capture keeps the profile retryable while diagnostics are unavailable");
		loggerRecovered[0] = true;
		var acceptedAfterRecovery = loggerRecoveryRetry.record(MinibossAppearanceCapture.Source.CONFIRMED_PROFILE,
			"Shadow Assassin", recoveryProfile, 73, firstSignature, "slim", 130L, recoveredSink);
		if (MinibossAppearanceCapture.shouldMarkAutomaticallyInspected(acceptedAfterRecovery)) {
			loggerRecoveryRetry.markAutomaticallyInspected(recoveryProfile.id());
		}
		check(acceptedAfterRecovery == MinibossAppearanceCapture.RecordResult.QUEUED
			&& loggerRecoveryRetry.wasAutomaticallyInspected(recoveryProfile.id())
			&& loggerRecoveryRetry.recordedCount() == 1 && recoveredOutput.size() == 1,
			"the same automatic profile queues after logger recovery and becomes inspected");

		MinibossAppearanceCapture automaticRetry = new MinibossAppearanceCapture();
		GameProfile autoProfile = new GameProfile(UUID.randomUUID(), "Lost Adventurer");
		String autoLabel = "Shadow Assassin";
		List<String> retryOutput = new ArrayList<>();
		check(automaticRetry.record(MinibossAppearanceCapture.Source.CONFIRMED_PROFILE, autoLabel,
			autoProfile, 73, firstSignature, "slim", 129L, retryOutput::add)
			== MinibossAppearanceCapture.RecordResult.QUEUED,
			"automatic capture reserves its first asynchronous record");
		automaticRetry.markAutomaticallyInspected(autoProfile.id());
		check(automaticRetry.wasAutomaticallyInspected(autoProfile.id()),
			"a queued automatic capture suppresses duplicate scanning while its write is pending");
		automaticRetry.completeAsyncWrite(autoProfile, autoLabel, firstSignature, "slim", true, false);
		check(!automaticRetry.wasAutomaticallyInspected(autoProfile.id()) && automaticRetry.recordedCount() == 0,
			"a failed automatic flush releases both dedupe and inspected reservations");
		check(automaticRetry.record(MinibossAppearanceCapture.Source.CONFIRMED_PROFILE, autoLabel,
			autoProfile, 73, firstSignature, "slim", 130L, retryOutput::add)
			== MinibossAppearanceCapture.RecordResult.QUEUED,
			"a failed automatic flush allows the same profile to be retried");
		automaticRetry.markAutomaticallyInspected(autoProfile.id());
		automaticRetry.completeAsyncWrite(autoProfile, autoLabel, firstSignature, "slim", true, true);
		check(automaticRetry.wasAutomaticallyInspected(autoProfile.id())
			&& automaticRetry.record(MinibossAppearanceCapture.Source.CONFIRMED_PROFILE, autoLabel,
				autoProfile, 73, firstSignature, "slim", 131L, retryOutput::add)
			== MinibossAppearanceCapture.RecordResult.DUPLICATE && retryOutput.size() == 2,
			"a successful retry retains dedupe and automatic-inspection state");

		MinibossAppearanceCapture bounded = new MinibossAppearanceCapture();
		List<String> boundedOutput = new ArrayList<>();
		for (int index = 0; index < MinibossAppearanceCapture.MAX_RECORDS_PER_CLIENT_SESSION; index++) {
			String signature = "sha256:" + String.format(java.util.Locale.ROOT, "%064x", index);
			var result = bounded.record(MinibossAppearanceCapture.Source.CONFIRMED_PROFILE,
				"profile-" + index, new GameProfile(UUID.randomUUID(), "Shadow Assassin"),
				index, signature, "classic", index, boundedOutput::add);
			check(result == MinibossAppearanceCapture.RecordResult.QUEUED,
				"capture queues records up to its session bound");
		}
		check(boundedOutput.size() == MinibossAppearanceCapture.MAX_RECORDS_PER_CLIENT_SESSION
			&& bounded.recordedCount() == MinibossAppearanceCapture.MAX_RECORDS_PER_CLIENT_SESSION
			&& bounded.record(MinibossAppearanceCapture.Source.CONFIRMED_PROFILE, "overflow",
				new GameProfile(UUID.randomUUID(), "Shadow Assassin"), 999,
				"sha256:" + "f".repeat(64), "classic", 999, boundedOutput::add)
			== MinibossAppearanceCapture.RecordResult.LIMIT_REACHED
			&& boundedOutput.size() == MinibossAppearanceCapture.MAX_RECORDS_PER_CLIENT_SESSION,
			"capture output stops at the fixed per-client-session record limit");
	}

	private static void checkManualCaptureReadiness() {
		check(!DungeonMobEspCaptureReadiness.isReady(true, false, true),
			"manual appearance capture requires a player even when the level and dungeon are present");
		check(!DungeonMobEspCaptureReadiness.isReady(false, true, true)
			&& !DungeonMobEspCaptureReadiness.isReady(true, true, false),
			"manual appearance capture still requires a level and dungeon context");
		check(DungeonMobEspCaptureReadiness.isReady(true, true, true),
			"manual appearance capture is ready only when level, player, and dungeon context are present");
	}

	private static void checkJavaStringSemantics() {
		String emSpace = "\u2003";
		String nonBreakingSpace = "\u00a0";
		check((emSpace + "✯").equals(DungeonMobEspSupport.javaStringTrim(emSpace + "✯"))
			&& !DungeonMobEspSupport.isStarred(emSpace + "✯")
			&& !DungeonStarredMobTracker.hasDevonianStarEvidence(emSpace + "✯"),
			"raw Java trim preserves non-ASCII whitespace while chat normalization still flattens it before star checks");
		check(nonBreakingSpace.equals(DungeonMobEspSupport.javaStringTrim(" " + nonBreakingSpace + " "))
			&& !DungeonMobEspSupport.javaStringIsBlank(nonBreakingSpace)
			&& DungeonMobEspSupport.javaStringIsBlank(emSpace),
			"shared whitespace helpers match Java trim and isBlank boundaries");

		UUID localId = UUID.randomUUID();
		UUID targetId = UUID.randomUUID();
		check(!MinibossAppearanceCapture.isExcludedPlayerProfile(localId, targetId,
			nonBreakingSpace + "Alice" + nonBreakingSpace, false, Set.of(), Set.of("alice")),
			"profile-name normalization preserves Java trim and does not remove NBSP");

		MinibossAppearanceCapture capture = new MinibossAppearanceCapture();
		var result = capture.record(MinibossAppearanceCapture.Source.MANUAL_CONFIRMATION,
			nonBreakingSpace, new GameProfile(UUID.randomUUID(), nonBreakingSpace), 1,
			"sha256:" + "0".repeat(64), "classic", 1L, line -> true);
		check(result == MinibossAppearanceCapture.RecordResult.QUEUED,
			"appearance capture retains Java isBlank treatment of NBSP labels and profile names");
	}

	private static String texturePropertyValue(long timestamp, String url, String model) {
		String metadata = model == null ? "" : ",\"metadata\":{\"model\":\"" + model + "\"}";
		String json = "{\"timestamp\":" + timestamp + ",\"signatureRequired\":true,\"textures\":{\"SKIN\":{\"url\":\""
			+ url + "\"" + metadata + "}}}";
		return encodeJson(json);
	}

	private static String encodeJson(String json) {
		return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private static void checkReviewedAppearanceMatching() {
		String knownUrl = "https://textures.minecraft.net/texture/abcdef0123456789abcdef0123456789";
		var knownAppearance = MinibossAppearanceCapture.appearanceIdentityFromTexturePropertyValue(
			texturePropertyValue(400L, knownUrl, "classic"));
		var unknownAppearance = MinibossAppearanceCapture.appearanceIdentityFromTexturePropertyValue(
			texturePropertyValue(401L,
				"https://textures.minecraft.net/texture/1234567890abcdef1234567890abcdef", "classic"));
		check(knownAppearance != null && unknownAppearance != null,
			"catalog matching fixtures use validated Minecraft texture identities");
		String entry = catalogEntry(knownAppearance.textureSignature(), knownAppearance.model(),
			"Frozen Adventurer", true, "local capture review", "logs/session/Dungeon_Mob_ESP.log#12",
			"user-local", "crosshair-confirmed Frozen Adventurer in an F7 run");
		MinibossAppearanceCatalog.ParseResult parsed = MinibossAppearanceCatalog.parse(catalogJson(entry));
		check(parsed.valid() && parsed.entryCount() == 1 && parsed.unreviewedCount() == 0
			&& "Frozen Adventurer".equals(parsed.catalog().labelFor(knownAppearance)),
			"a complete human-reviewed signature record enables early matching for its exact skin model");
		check(parsed.catalog().labelFor(unknownAppearance) == null,
			"an unknown texture signature remains unmatched");

		MinibossAppearanceCatalog profileConflict = MinibossAppearanceCatalog.parse(catalogJson(
			catalogEntry(knownAppearance.textureSignature(), knownAppearance.model(), "Frozen Adventurer", true,
				"local capture review", "session-log#13", "user-local", "reviewed visual identity"))).catalog();
		UUID profileId = UUID.randomUUID();
		DungeonMinibossProfileTracker exactNameWins = new DungeonMinibossProfileTracker();
		var matchingIdentity = new MinibossProfileMatchPolicy.IdentitySnapshot(profileId, "Diamond Guy",
			Set.of(profileId), Set.of("Diamond Guy"));
		exactNameWins.onPlayerInfo(profileId, "Diamond Guy", knownAppearance, profileConflict, matchingIdentity);
		exactNameWins.onPlayerSpawn(profileId, 88, true);
		check("Diamond Guy".equals(exactNameWins.minibossLabel(88))
			&& "Diamond Guy".equals(exactNameWins.profileName(88))
			&& exactNameWins.shouldCaptureKnownAppearance(88),
			"an exact recognized player-profile name takes precedence over a conflicting reviewed skin mapping");

		UUID mappedProfile = UUID.randomUUID();
		DungeonMinibossProfileTracker signatureTracker = new DungeonMinibossProfileTracker();
		signatureTracker.onPlayerInfo(mappedProfile, "NPC-4821", knownAppearance, parsed.catalog(),
			MinibossProfileMatchPolicy.IdentitySnapshot.empty());
		signatureTracker.onPlayerSpawn(mappedProfile, 89, true);
		check("Frozen Adventurer".equals(signatureTracker.minibossLabel(89))
			&& "NPC-4821".equals(signatureTracker.profileName(89))
			&& !signatureTracker.shouldCaptureKnownAppearance(89)
			&& signatureTracker.packetDiagnostics().contains("reviewedAppearanceProfiles=1"),
			"a reviewed skin mapping provides the label but is not captured as independent confirmed-profile evidence");

		UUID partyProfile = UUID.randomUUID();
		var currentParty = new MinibossProfileMatchPolicy.IdentitySnapshot(UUID.randomUUID(), "LocalPlayer",
			Set.of(partyProfile), Set.of("PartyMate"));
		DungeonMinibossProfileTracker partyExcluded = new DungeonMinibossProfileTracker();
		partyExcluded.onPlayerInfo(partyProfile, "NPC-PARTY", knownAppearance, parsed.catalog(), currentParty);
		partyExcluded.onPlayerSpawn(partyProfile, 91, true);
		check(!partyExcluded.isTrackedEntity(91)
			&& partyExcluded.packetDiagnostics().contains("appearanceIdentityExclusions=1"),
			"a catalog skin match is rejected when its UUID belongs to the current party identity snapshot");
		DungeonMinibossProfileTracker partyNameExcluded = new DungeonMinibossProfileTracker();
		UUID otherPartyProfile = UUID.randomUUID();
		partyNameExcluded.onPlayerInfo(otherPartyProfile, "PartyMate", knownAppearance, parsed.catalog(), currentParty);
		partyNameExcluded.onPlayerSpawn(otherPartyProfile, 93, true);
		check(!partyNameExcluded.isTrackedEntity(93),
			"a catalog skin match is rejected when its profile name belongs to the current party identity snapshot");
		DungeonMinibossProfileTracker localExcluded = new DungeonMinibossProfileTracker();
		UUID localProfile = currentParty.localPlayerId();
		localExcluded.onPlayerInfo(localProfile, "NPC-LOCAL", knownAppearance, parsed.catalog(), currentParty);
		localExcluded.onPlayerSpawn(localProfile, 92, true);
		check(!localExcluded.isTrackedEntity(92),
			"a catalog skin match is rejected when its UUID belongs to the local player");

		MinibossAppearanceCatalog.ParseResult unreviewed = MinibossAppearanceCatalog.parse(catalogJson(
			catalogEntry(knownAppearance.textureSignature(), knownAppearance.model(), "Frozen Adventurer", false,
				"pending review", "session-log#14", "user-local", "not yet confirmed")));
		check(unreviewed.valid() && unreviewed.unreviewedCount() == 1
			&& unreviewed.catalog().labelFor(knownAppearance) == null,
			"unreviewed records remain in the file for review but cannot drive entity matching");

		String duplicate = catalogEntry(knownAppearance.textureSignature(), knownAppearance.model(),
			"Frozen Adventurer", true, "source", "ref#1", "user-local", "evidence") + ","
			+ catalogEntry(knownAppearance.textureSignature(), knownAppearance.model(),
				"Frozen Adventurer", true, "source", "ref#2", "user-local", "evidence");
		check(!MinibossAppearanceCatalog.parse(catalogJson(duplicate)).valid(),
			"duplicate signature/model mappings invalidate the whole catalog instead of choosing a row silently");
		String invalidSignature = catalogEntry("sha256:" + "z".repeat(64), "classic", "Frozen Adventurer",
			true, "source", "ref", "user-local", "evidence");
		String invalidLabel = catalogEntry(knownAppearance.textureSignature(), "classic", "Fel", true,
			"source", "ref", "user-local", "evidence");
		check(!MinibossAppearanceCatalog.parse(catalogJson(invalidSignature)).valid()
			&& !MinibossAppearanceCatalog.parse(catalogJson(invalidLabel)).valid(),
			"malformed hashes and labels outside the focused miniboss roster fail closed");
		String duplicateField = catalogJson(entry).replace("\"reviewed\":true", "\"reviewed\":true,\"reviewed\":false");
		check(!MinibossAppearanceCatalog.parse(duplicateField).valid(),
			"duplicate JSON object fields are rejected instead of using last-field-wins behavior");
		check(!MinibossAppearanceCatalog.parse(" ".repeat(MinibossAppearanceCatalog.MAX_BYTES + 1)).valid(),
			"catalog input is bounded before parsing");

		MinibossAppearanceCapture capture = new MinibossAppearanceCapture();
		List<String> captureRecords = new ArrayList<>();
		capture.record(MinibossAppearanceCapture.Source.MANUAL_CONFIRMATION, "Frozen Adventurer",
			new GameProfile(UUID.randomUUID(), "NPC-9999"), 90, knownAppearance.textureSignature(),
			knownAppearance.model(), 500L, captureRecords::add);
		check(captureRecords.size() == 1 && !MinibossAppearanceCatalog.parse(captureRecords.getFirst()).valid(),
			"capture-session JSON does not conform to the reviewed catalog schema and is never auto-trusted");

		String confirmedLabel = MinibossProfileMatchPolicy.uniqueRecognizedMinibossLabel(
			List.of("Frozen Adventurer", "Frozen Adventurer"));
		MinibossAppearanceCapture automaticCapture = new MinibossAppearanceCapture();
		List<String> automaticRecords = new ArrayList<>();
		GameProfile nameplateProfile = new GameProfile(UUID.randomUUID(), "Dungeon-NPC-42");
		var automaticResult = automaticCapture.record(MinibossAppearanceCapture.Source.CONFIRMED_NAMEPLATE,
			confirmedLabel, nameplateProfile, 91, knownAppearance.textureSignature(), knownAppearance.model(),
			501L, automaticRecords::add);
		if (MinibossAppearanceCapture.shouldMarkAutomaticallyInspected(automaticResult)) {
			automaticCapture.markAutomaticallyInspected(nameplateProfile.id());
		}
		check("Frozen Adventurer".equals(confirmedLabel)
			&& automaticResult == MinibossAppearanceCapture.RecordResult.QUEUED
			&& automaticCapture.wasAutomaticallyInspected(nameplateProfile.id())
			&& automaticRecords.size() == 1 && automaticRecords.getFirst().contains("\"source\":\"confirmed_nameplate\"")
			&& !MinibossAppearanceCatalog.parse(automaticRecords.getFirst()).valid(),
			"a unique recognized nameplate queues one shareable candidate record and cannot promote itself into the active catalog");
		check(automaticCapture.record(MinibossAppearanceCapture.Source.CONFIRMED_NAMEPLATE,
			confirmedLabel, nameplateProfile, 91, knownAppearance.textureSignature(), knownAppearance.model(),
			502L, automaticRecords::add) == MinibossAppearanceCapture.RecordResult.DUPLICATE
			&& automaticRecords.size() == 1,
			"repeated nameplate observations for one profile are exact-once within a client session");
	}

	private static String catalogJson(String profiles) {
		return "{\"schema\":\"" + MinibossAppearanceCatalog.SCHEMA + "\",\"version\":"
			+ MinibossAppearanceCatalog.VERSION + ",\"profiles\":[" + profiles + "]}";
	}

	private static String catalogEntry(String signature, String model, String label, boolean reviewed,
		String source, String sourceReference, String license, String evidence) {
		return "{\"signature\":\"" + signature + "\",\"skinModel\":\"" + model
			+ "\",\"label\":\"" + label + "\",\"reviewed\":" + reviewed
			+ ",\"source\":\"" + source + "\",\"sourceReference\":\"" + sourceReference
			+ "\",\"license\":\"" + license + "\",\"evidence\":\"" + evidence + "\"}";
	}

	private static void checkConfirmedRoomFootprints() {
		var cell = new DungeonRoomTracker.CellKey(2, 3);
		check(DungeonMobEspSupport.confirmedRoomFootprint(false, List.of(cell)) == null,
			"uncalibrated map geometry cannot enable current-room-only ESP");
		check(DungeonMobEspSupport.confirmedRoomFootprint(true, List.of()) == null,
			"an empty physical footprint cannot enable current-room-only ESP");
		var footprint = DungeonMobEspSupport.confirmedRoomFootprint(true, List.of(cell));
		check(footprint != null && footprint.cells().equals(List.of(cell))
			&& "cells:2,3".equals(footprint.footprintKey()),
			"calibrated geometry keeps the confirmed physical cells in a geometry-derived key");
		int roomX = geiler.addons.client.dungeon.DungeonGridRules.GRID_MIN
			+ cell.x() * geiler.addons.client.dungeon.DungeonGridRules.CELL_STRIDE;
		int roomZ = geiler.addons.client.dungeon.DungeonGridRules.GRID_MIN
			+ cell.z() * geiler.addons.client.dungeon.DungeonGridRules.CELL_STRIDE;
		check(DungeonMobEspSupport.isAllowedByRoomFilter(true, footprint,
			new BlockPos(roomX + 8, 70, roomZ + 8)), "a target in the local physical room passes the strict Fel room filter");
		check(!DungeonMobEspSupport.isAllowedByRoomFilter(true, footprint,
			new BlockPos(roomX + geiler.addons.client.dungeon.DungeonGridRules.CELL_STRIDE + 8, 70, roomZ + 8)),
			"a target in the adjacent physical cell is excluded when Fels are limited to this room");
	}

	private static void checkFelMarkerAnchor() {
		var bounds = FelSkullMarkerGeometry.bounds(4.0, 70.0, -3.0);
		check(Math.abs(bounds.centerY() - 71.27) < 0.0001,
			"the Fel skull marker is rendered one block above the armor-stand entity anchor");
		check(Math.abs(bounds.centerX() - 4.0) < 0.0001 && Math.abs(bounds.centerZ() + 3.0) < 0.0001,
			"raising the marker preserves its horizontal position");
		check(Math.abs((bounds.maxY() - bounds.minY()) - 0.6) < 0.0001,
			"raising the marker preserves the source 0.6-block geometry");
	}

	private static void checkShadowAssassinIdentityAndRendering() {
		check("Frozen Adventurer".equals(DungeonMobEspSupport.recognizedMinibossLabel(
			"✯ [Lv 100] Frozen Adventurer 12,000/12,000❤"))
			&& "Diamond Guy".equals(DungeonMobEspSupport.recognizedMinibossLabel("Healthy Diamond Guy"))
			&& "King Midas".equals(DungeonMobEspSupport.recognizedMinibossLabel(
				"✯ [Lv 100] King Midas 12,000/12,000❤"))
			&& DungeonMobEspSupport.recognizedMinibossLabel("Unrecognized Dungeon Mob") == null,
			"automatic capture canonicalizes King Midas and other recognized miniboss nameplates while rejecting unknown labels");
		check(DungeonMobEspSupport.isShadowAssassinProfileName("Shadow Assassin"),
			"the verified profile name identifies the hidden Shadow Assassin body");
		check(DungeonMobEspSupport.isExactDungeonMinibossProfileName("Lost Adventurer")
			&& DungeonMobEspSupport.isExactDungeonMinibossProfileName("Diamond Guy")
			&& DungeonMobEspSupport.isExactDungeonMinibossProfileName("King Midas"),
			"the other reference-verified exact player profiles belong to the existing miniboss roster");
		check(!DungeonMobEspSupport.isExactDungeonMinibossProfileName("Frozen Adventurer")
			&& !DungeonMobEspSupport.isExactDungeonMinibossProfileName("Angry Archaeologist")
			&& !DungeonMobEspSupport.isExactDungeonMinibossProfileName("shadow assassin"),
			"profile-only recovery remains exact and excludes label-only targets and case variants");
		check(!DungeonMobEspSupport.isShadowAssassinProfileName("shadow assassin"),
			"a similarly named player profile does not match the exact NPC name");
		check(DungeonMobEspSupport.isNamedShadowAssassinArmorStandAssociation(
			"✯ Shadow Assassin 100,000❤", 42, 41, true, false, 9_000_000.0),
			"the named armor stand resolves to its adjacent living body without a distance cutoff");
		check(!DungeonMobEspSupport.isNamedShadowAssassinArmorStandAssociation(
			"Lost Adventurer", 42, 41, true, false, 1.0),
			"another miniboss name cannot claim the Shadow Assassin body association");
		check(DungeonMobEspSupport.isEligibleMinibossName("Shadow Assassin", true)
			&& DungeonMobEspSupport.isEligibleMinibossName("[Lv 100] ✯ Shadow Assassin", true)
			&& DungeonMobEspSupport.isEligibleMinibossName("✯ Shadow Assassin", true),
			"Shadow Assassin eligibility is independent of star evidence");
		check(!DungeonMobEspSupport.isEligibleMinibossName("Lost Adventurer", true)
			&& DungeonMobEspSupport.isEligibleMinibossName("[Lv 100] ✯ Lost Adventurer", true)
			&& !DungeonMobEspSupport.isEligibleMinibossName("King Midas", true)
			&& DungeonMobEspSupport.isEligibleMinibossName("[Lv 100] ✯ King Midas", true),
			"Only Starred keeps filtering every miniboss, including King Midas, by star evidence");

		UUID profile = UUID.randomUUID();
		DungeonMinibossProfileTracker tracker = new DungeonMinibossProfileTracker();
		tracker.onPlayerInfo(profile, "Shadow Assassin");
		tracker.onPlayerSpawn(profile, 71, true);
		check(tracker.isTrackedEntity(71) && "Shadow Assassin".equals(tracker.profileName(71)),
			"player info followed by spawn associates the exact profile and NPC");
		tracker.clear();
		tracker.onPlayerSpawn(profile, 72, true);
		tracker.onPlayerInfo(profile, "Lost Adventurer");
		check(tracker.isTrackedEntity(72) && "Lost Adventurer".equals(tracker.profileName(72)),
			"spawn followed by player info associates another exact miniboss profile");
		tracker.onEntityRemoved(72);
		check(!tracker.isTrackedEntity(72), "entity removal clears the packet association");

		double previousX = 0, previousY = 70, previousZ = 0;
		double currentX = 4, currentY = 72, currentZ = -2;
		float partialTick = 0.5f;
		var interpolatedBounds = DungeonMobEspSupport.fullShadowAssassinBounds(
			previousX, previousY, previousZ, currentX, currentY, currentZ, partialTick);
		check(Math.abs(interpolatedBounds.getCenter().x - 2.0) < 0.0001
			&& Math.abs(interpolatedBounds.minY - 71.0) < 0.0001
			&& Math.abs(interpolatedBounds.getCenter().z + 1.0) < 0.0001,
			"the expanded Shadow Assassin body box uses the supplied interpolated scalar position");
	}

	private static void checkDevonianMobClassificationAndPacketTracking() {
		check(DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ Shadow Assassin 80,000❤")
			== DungeonStarredMobTracker.MobClass.SHADOW_ASSASSIN
			&& DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ Fels 80,000❤")
			== DungeonStarredMobTracker.MobClass.FEL
			&& DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ Skeleton Master 80,000❤")
			== DungeonStarredMobTracker.MobClass.SKELETON_MASTER,
			"Devonian's Shadow Assassin, Fel, and Skeleton Master nameplate classes are recognized");
		check(DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ Withermancer 80,000❤")
			== DungeonStarredMobTracker.MobClass.WITHERMANCER
			&& DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ Zombie Lord 80,000❤")
			== DungeonStarredMobTracker.MobClass.LORD
			&& DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ Zombie Commander 80,000❤")
			== DungeonStarredMobTracker.MobClass.ZOMBIE_COMMANDER
			&& DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ Super Archer 80,000❤")
			== DungeonStarredMobTracker.MobClass.SUPER_ARCHER,
			"Withermancer, Lord, Zombie Commander, and Super Archer classes match Devonian labels");
		check(DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ Lost Adventurer 80,000❤")
			== DungeonStarredMobTracker.MobClass.ADVENTURER
			&& DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ Angry Archaeologist 80,000❤")
			== DungeonStarredMobTracker.MobClass.ANGRY_ARCHAEOLOGIST
			&& DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ King Midas 80,000❤")
			== DungeonStarredMobTracker.MobClass.KING_MIDAS
			&& DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ Crypt Dreadlord 80,000❤")
			== DungeonStarredMobTracker.MobClass.STARRED_MOB,
			"Adventurer, Angry Archaeologist, King Midas, and generic fallback classes are recognized");
		check(DungeonStarredMobTracker.classifyStarredNameplate("Shadow Assassin") == null
			&& DungeonStarredMobTracker.classifyStarredNameplate("✯ Shadow Assassin") == null
			&& DungeonStarredMobTracker.classifyStarredNameplate("[Lv 100] ✯ Shadow Assassin")
			== DungeonStarredMobTracker.MobClass.SHADOW_ASSASSIN,
			"nameplate classification requires the star at an index of at least one");
		check(DungeonStarredMobTracker.bodyEntityId(42, DungeonStarredMobTracker.MobClass.SHADOW_ASSASSIN) == 41
			&& DungeonStarredMobTracker.bodyEntityId(42, DungeonStarredMobTracker.MobClass.WITHERMANCER) == 39,
			"the normal and Withermancer body offsets are minus one and minus three");
		check(DungeonMobEspSupport.isExactDungeonMinibossProfileName("Shadow Assassin")
			&& DungeonMobEspSupport.isExactDungeonMinibossProfileName("Lost Adventurer")
			&& DungeonMobEspSupport.isExactDungeonMinibossProfileName("Diamond Guy")
			&& DungeonMobEspSupport.isExactDungeonMinibossProfileName("King Midas")
			&& !DungeonMobEspSupport.isExactDungeonMinibossProfileName("Frozen Adventurer"),
			"only Devonian's four exact player profile names create profile-based miniboss targets");
		check(DungeonMobEspSupport.shouldAcceptMinibossProfile(false, false, false)
			&& !DungeonMobEspSupport.shouldAcceptMinibossProfile(true, false, false)
			&& DungeonMobEspSupport.shouldAcceptMinibossProfile(true, true, false)
			&& DungeonMobEspSupport.shouldAcceptMinibossProfile(true, false, true),
			"Only Starred filters ordinary profiles while Shadow Assassin identity remains independent of stars");
		check(!DungeonMobEspSupport.isOutsideRememberedHudRange(DungeonMobTargetMemory.Group.STARRED,
			1_000_000_000.0, 8)
			&& !DungeonMobEspSupport.isOutsideRememberedHudRange(DungeonMobTargetMemory.Group.MINIBOSS,
				1_000_000_000.0, 8)
			&& DungeonMobEspSupport.isOutsideRememberedHudRange(DungeonMobTargetMemory.Group.FEL_MOVING,
				1_000_000_000.0, 8),
			"retained Starred and Miniboss HUD targets bypass range while Fel remains range-limited");

		Object firstWorld = new Object();
		DungeonStarredMobTracker tracker = new DungeonStarredMobTracker();
		tracker.onWorld(firstWorld);
		tracker.onArmorStandSpawn(firstWorld, 42);
		tracker.onNameplateMetadata(firstWorld, 42, "[Lv 100] ✯ Shadow Assassin");
		check(tracker.resolveLoadedBodies(entityId -> false).isEmpty(),
			"a nameplate remains pending while its associated body has not arrived");
		var profileFirst = tracker.resolveLoadedBodies(entityId -> entityId == 41);
		check(profileFirst.size() == 1 && profileFirst.getFirst().bodyId() == 41
			&& profileFirst.getFirst().mobClass() == DungeonStarredMobTracker.MobClass.SHADOW_ASSASSIN,
			"the body can load after its starred nameplate and resolve without a range parameter");
		check(profileFirst.getFirst().source() == DungeonStarredMobTracker.ResolutionSource.ENTITY_ID_OFFSET,
			"the Devonian entity-ID offset remains the primary association path");

		DungeonStarredMobTracker spatialTracker = new DungeonStarredMobTracker();
		spatialTracker.onNameplateMetadata(firstWorld, 62, "[Lv 100] ✯ Skeleton Master");
		var spatialMatches = spatialTracker.resolveLoadedBodies(entityId -> entityId == 700,
			entityId -> entityId == 62 ? new DungeonStarredMobTracker.LabelPosition(10, 72.4, 10) : null,
			List.of(new DungeonStarredMobTracker.BodyCandidate(700, 10, 70, 10)));
		check(spatialMatches.size() == 1 && spatialMatches.getFirst().bodyId() == 700
			&& spatialMatches.getFirst().source() == DungeonStarredMobTracker.ResolutionSource.SPATIAL_FALLBACK,
			"when the ID offset is unavailable, one loaded body directly below the label resolves spatially");
		var ambiguousSpatial = DungeonStarredMobTracker.resolveSpatialBodyId(
			new DungeonStarredMobTracker.LabelPosition(10, 72.4, 10), List.of(
				new DungeonStarredMobTracker.BodyCandidate(700, 10, 70, 10),
				new DungeonStarredMobTracker.BodyCandidate(701, 10.2, 70, 10)));
		check(ambiguousSpatial.bodyId() < 0 && ambiguousSpatial.ambiguous(),
			"overlapping bodies below one label are rejected rather than highlighting the wrong mob");
		check(DungeonStarredMobTracker.resolveSpatialBodyId(new DungeonStarredMobTracker.LabelPosition(0, 70, 0),
			List.of(new DungeonStarredMobTracker.BodyCandidate(702, 0, 71, 0))).bodyId() < 0,
			"spatial fallback never associates a body above its nameplate");

		tracker.onNameplateMetadata(firstWorld, 52, "[Lv 100] ✯ Withermancer");
		tracker.onArmorStandSpawn(firstWorld, 52);
		var nameFirst = tracker.resolveLoadedBodies(entityId -> entityId == 49);
		check(nameFirst.size() == 1 && nameFirst.getFirst().bodyId() == 49
			&& nameFirst.getFirst().mobClass() == DungeonStarredMobTracker.MobClass.WITHERMANCER,
			"a nameplate metadata event arriving before the spawn callback keeps the minus-three association");

		tracker.onArmorStandSpawn(firstWorld, 44);
		tracker.onNameplateMetadata(firstWorld, 44, "[Lv 100] ✯ Withermancer");
		check(tracker.resolveLoadedBodies(entityId -> entityId == 41).size() == 1,
			"duplicate labels resolving to the same body are deduplicated");
		tracker.onEntityRemoved(41);
		check(tracker.resolveLoadedBodies(entityId -> entityId == 41).isEmpty(),
			"removing an associated body clears all labels pointing at it");
		tracker.onEntityRemoved(42);
		check(tracker.trackedNameplates() == 1,
			"removing a nameplate clears its association without clearing unrelated labels");
		check(tracker.onEntityRemoved(52).equals(List.of(49)) && tracker.trackedNameplates() == 0,
			"removing a nameplate reports its body ID so remembered ESP state can be cleared too");
		tracker.onWorld(new Object());
		check(tracker.trackedNameplates() == 0 && tracker.resolveLoadedBodies(entityId -> true).isEmpty(),
			"a world change clears packet associations and queued targets");
		check(!DungeonMinibossProfileTracker.isOutsideConfiguredRange(
			MinibossProfileMatchPolicy.MatchSource.EXACT_PROFILE_NAME, 1_000_000_000.0, 8)
			&& DungeonMinibossProfileTracker.isOutsideConfiguredRange(
				MinibossProfileMatchPolicy.MatchSource.REVIEWED_APPEARANCE, 1_000_000_000.0, 8),
			"exact miniboss profiles have no mod-side range cutoff while reviewed appearance scans keep theirs");

		String[] exactProfiles = {"Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas"};
		DungeonMinibossProfileTracker batchedTracker = new DungeonMinibossProfileTracker();
		batchedTracker.onPlayerInfoUpdate(true, exactProfiles.length);
		for (int index = 0; index < exactProfiles.length; index++) {
			UUID profileId = UUID.randomUUID();
			batchedTracker.onPlayerInfo(profileId, exactProfiles[index]);
			batchedTracker.onPlayerSpawn(profileId, 80 + index, true);
		}
		check(batchedTracker.trackedCount() == exactProfiles.length
			&& batchedTracker.packetDiagnostics().contains("entries=4/add=4"),
			"batched player-info entries associate all four exact profile names without dropping later entries");
	}

	private static void checkLoadedEntityMemory() {
		check(DungeonMobTargetMemory.isPersistent(DungeonMobTargetMemory.Group.STARRED)
			&& DungeonMobTargetMemory.isPersistent(DungeonMobTargetMemory.Group.MINIBOSS)
			&& !DungeonMobTargetMemory.isPersistent(DungeonMobTargetMemory.Group.FEL_SKULL)
			&& !DungeonMobTargetMemory.isPersistent(DungeonMobTargetMemory.Group.FEL_MOVING)
			&& !DungeonMobTargetMemory.isPersistent(null),
			"only starred/miniboss snapshots persist across unloads; transient and missing groups do not");
		UUID normalizedId = UUID.randomUUID();
		var validBounds = new DungeonMobTargetMemory.Bounds(1, 2, 3, 4, 5, 6);
		var normalized = new DungeonMobTargetMemory.Target(normalizedId, 7,
			DungeonMobTargetMemory.Group.MINIBOSS, null, null, validBounds, "F7:room", 10);
		var explicitDefaults = new DungeonMobTargetMemory.Target(normalizedId, 7,
			DungeonMobTargetMemory.Group.MINIBOSS, "unknown", "", validBounds, "F7:room", 10, false);
		check(normalized.type().equals("unknown") && normalized.label().isEmpty()
			&& !normalized.fullShadowAssassin() && normalized.equals(explicitDefaults),
			"target snapshots normalize nullable labels and preserve record-style value equality");
		boolean invalidBoundsRejected = false;
		try {
			new DungeonMobTargetMemory.Bounds(Double.NaN, 0, 0, 1, 1, 1);
		} catch (IllegalArgumentException expected) {
			invalidBoundsRejected = true;
		}
		check(invalidBoundsRejected, "target bounds reject non-finite coordinates");

		DungeonMobTargetMemory memory = new DungeonMobTargetMemory();
		var first = target(UUID.randomUUID(), "F7:source-room", 100);
		memory.update(List.of(first), 100,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> first);
		long laterTick = 100 + 10_000_000L;
		var refreshed = target(first.uuid(), "F7:source-room", laterTick);
		check(memory.update(List.of(), laterTick,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> refreshed).equals(List.of(refreshed)),
			"a living loaded UUID remains highlighted through a very long gap with refreshed bounds");
		check(refreshed.fullShadowAssassin(),
			"memory refresh preserves the reference-confirmed Shadow Assassin render identity");
		check(memory.update(List.of(), laterTick + 1,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null).equals(List.of(refreshed))
			&& memory.size() == 1,
			"a starred/miniboss UUID remains in memory during chunk unload with its last known position");
		memory.removeEntityId(first.entityId());
		check(memory.update(List.of(), laterTick + 2,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null).isEmpty()
			&& memory.size() == 0,
			"an authoritative entity removal packet clears its remembered UUID immediately");

		var next = target(UUID.randomUUID(), "F7:source-room", laterTick + 2);
		memory.update(List.of(next), laterTick + 2,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> next);
		var movedRoom = target(next.uuid(), "F7:other-room", laterTick + 3);
		check(memory.update(List.of(), laterTick + 3,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> movedRoom).equals(List.of(movedRoom))
			&& memory.size() == 1,
			"room transitions keep a living UUID and refresh its current room association");
		check(memory.update(List.of(), laterTick + 4,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_GONE, ignored -> null).isEmpty()
			&& memory.size() == 0,
			"a loaded dead or removed entity is cleared from memory");
		memory.update(List.of(next), laterTick + 5,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> next);
		memory.clear();
		check(memory.size() == 0,
			"dungeon reset clears persistent UUID memory");

		List<DungeonMobTargetMemory.Target> overLimit = new ArrayList<>();
		for (int index = 0; index <= DungeonMobTargetMemory.MAX_TARGETS; index++) {
			overLimit.add(target(UUID.randomUUID(), "F7:bounded-room", laterTick + 6,
				DungeonMobTargetMemory.Group.MINIBOSS));
		}
		memory.update(overLimit, laterTick + 6,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> null);
		List<DungeonMobTargetMemory.Target> retainedAtLimit = memory.update(List.of(), laterTick + 7,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null);
		check(memory.size() == DungeonMobTargetMemory.MAX_TARGETS
			&& retainedAtLimit.size() == DungeonMobTargetMemory.MAX_TARGETS
			&& retainedAtLimit.stream().noneMatch(remembered -> remembered.uuid().equals(overLimit.getFirst().uuid())),
			"the 512-entry bound evicts the oldest UUID while retaining the newest persistent targets");

		memory.clear();
		var starred = target(UUID.randomUUID(), "F7:starred-room", laterTick + 8,
			DungeonMobTargetMemory.Group.STARRED);
		memory.update(List.of(starred), laterTick + 8,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> starred);
		check(memory.update(List.of(), laterTick + 9,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null).equals(List.of(starred)),
			"starred UUIDs remain remembered across chunk unloads like miniboss UUIDs");

		memory.clear();
		var felSkull = target(UUID.randomUUID(), "F7:fel-room", laterTick + 10,
			DungeonMobTargetMemory.Group.FEL_SKULL);
		memory.update(List.of(felSkull), laterTick + 10,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> felSkull);
		check(memory.update(List.of(), laterTick + 11,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null).isEmpty()
			&& memory.size() == 0,
			"transient Fel skull snapshots are discarded when their entity unloads");
	}

	private static DungeonMobTargetMemory.Target target(UUID uuid, String roomKey, long tick) {
		return target(uuid, roomKey, tick, DungeonMobTargetMemory.Group.MINIBOSS);
	}

	private static DungeonMobTargetMemory.Target target(UUID uuid, String roomKey, long tick,
		DungeonMobTargetMemory.Group group) {
		boolean miniboss = group == DungeonMobTargetMemory.Group.MINIBOSS;
		String type = group == DungeonMobTargetMemory.Group.STARRED ? "minecraft:skeleton"
			: miniboss ? "minecraft:player" : "minecraft:armor_stand";
		String label = switch (group) {
			case STARRED -> "Crypt Dreadlord";
			case MINIBOSS -> "Shadow Assassin";
			case FEL_SKULL, FEL_MOVING -> "Fel";
		};
		return new DungeonMobTargetMemory.Target(uuid, 7, group, type, label,
			new DungeonMobTargetMemory.Bounds(1, 2, 3, 2, 4, 5), roomKey, tick, miniboss);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
