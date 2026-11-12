package geiler.addons.client.dungeon;

import java.util.List;
import java.util.UUID;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;

/** Pure checks for pickup parsing, verified attribution, ESP independence, and duplicate suppression. */
public final class DungeonKeyAlertChecks {
	private DungeonKeyAlertChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		checkRecordAbi();

		DungeonKeyAlertRules.PickupEvent wither = DungeonKeyAlertRules.parsePickup("§cA Wither Key was picked up!");
		check(wither != null && wither.keyKind() == DungeonKeyAlertRules.KeyKind.WITHER && wither.actor() == null,
			"the exact formatted system announcement is recognized and remains unattributed");
		DungeonKeyAlertRules.PickupEvent javaTrimmed =
			DungeonKeyAlertRules.parsePickup("\u0001A Wither Key was picked up!\u0001");
		check(javaTrimmed != null && javaTrimmed.keyKind() == DungeonKeyAlertRules.KeyKind.WITHER,
			"pickup parsing keeps Java String.trim control-character handling");
		DungeonKeyAlertRules.PickupEvent unicodePadded =
			DungeonKeyAlertRules.parsePickup("\u2003A Wither Key was picked up!\u2003");
		check(unicodePadded != null && unicodePadded.keyKind() == DungeonKeyAlertRules.KeyKind.WITHER,
			"chat parsing retains ChatText's Unicode space canonicalization");
		DungeonKeyAlertRules.PickupEvent blood = DungeonKeyAlertRules.parsePickup("A Blood Key was picked up!");
		check(blood != null && blood.keyKind() == DungeonKeyAlertRules.KeyKind.BLOOD && blood.actor() == null,
			"the exact Blood announcement remains unattributed");

		DungeonKeyAlertRules.PickupEvent self = DungeonKeyAlertRules.parsePickup("Local_1 has obtained Wither Key!");
		DungeonKeyAlertRules.PickupEvent teammate = DungeonKeyAlertRules.parsePickup("Teammate has obtained Blood Key!");
		DungeonKeyAlertRules.PickupEvent articleForm = DungeonKeyAlertRules.parsePickup("Teammate has obtained a Blood Key!");
		DungeonKeyAlertRules.PickupEvent ranked = DungeonKeyAlertRules.parsePickup("[MVP++] Teammate has obtained Wither Key!");
		DungeonKeyAlertRules.PickupEvent rankedArticle = DungeonKeyAlertRules.parsePickup("[VIP+] Teammate has obtained a Blood Key!");
		check(self != null && "Local_1".equals(self.actor()), "the exact named-pickup form captures only its username actor");
		check(teammate != null && teammate.keyKind() == DungeonKeyAlertRules.KeyKind.BLOOD,
			"the exact named-pickup form captures the key type");
		check(articleForm != null && articleForm.keyKind() == DungeonKeyAlertRules.KeyKind.BLOOD
			&& "Teammate".equals(articleForm.actor()),
			"the named-pickup parser accepts Hypixel's article-bearing key message");
		check(ranked != null && "Teammate".equals(ranked.actor())
			&& ranked.keyKind() == DungeonKeyAlertRules.KeyKind.WITHER,
			"an Odin-style optional rank prefix is stripped before actor attribution");
		check(rankedArticle != null && "Teammate".equals(rankedArticle.actor())
			&& rankedArticle.keyKind() == DungeonKeyAlertRules.KeyKind.BLOOD,
			"ranked named messages retain the supported article form");
		List<String> currentParty = DungeonKeyAlertRules.trustedPartyNames(true, true, List.of("Local_1", "Teammate"));
		check(DungeonKeyAlertRules.classifyActor("local_1", "Local_1", currentParty) == DungeonKeyAlertRules.ActorKind.SELF,
			"case-insensitive local username is self only while the fresh roster verifies it");
		check(DungeonKeyAlertRules.classifyActor("teammate", "Local_1", currentParty) == DungeonKeyAlertRules.ActorKind.PARTY,
			"an exact current party member is classified as party");
		check(DungeonKeyAlertRules.classifyActor("Local_1", "Local_1", List.of()) == DungeonKeyAlertRules.ActorKind.UNKNOWN,
			"a local username without a fresh verified party roster is not attributed");
		check(DungeonKeyAlertRules.classifyActor("NearbyPlayer", "Local_1", currentParty) == DungeonKeyAlertRules.ActorKind.UNKNOWN,
			"proximity alone never attributes a pickup to the party");
		check(DungeonKeyAlertRules.classifyActor("Teammate", "Local_1",
			DungeonKeyAlertRules.trustedPartyNames(false, true, List.of("Teammate"))) == DungeonKeyAlertRules.ActorKind.UNKNOWN,
			"a stale or incomplete party snapshot does not verify an actor");
		check(DungeonKeyAlertRules.classifyActor("Teammate", "Local_1",
			DungeonKeyAlertRules.trustedPartyNames(true, false, List.of("Teammate"))) == DungeonKeyAlertRules.ActorKind.UNKNOWN,
			"party names are not trusted when the snapshot says there is no active party");
		DungeonKeyAlertRules.PickupAlert namedSelf = DungeonKeyAlertRules.resolveAlert(self, "Local_1", currentParty);
		check(namedSelf.actorKind() == DungeonKeyAlertRules.ActorKind.SELF
			&& "You picked up Wither Key".equals(namedSelf.text()) && !namedSelf.exactSystemLine(),
			"a named pickup is personalized only when the local account is listed in the verified roster");
		DungeonKeyAlertRules.PickupAlert namedParty = DungeonKeyAlertRules.resolveAlert(teammate, "Local_1", currentParty);
		check(namedParty.actorKind() == DungeonKeyAlertRules.ActorKind.PARTY
			&& "Teammate picked up Blood Key".equals(namedParty.text()),
			"a verified party actor remains named in the alert");
		DungeonKeyAlertRules.PickupAlert unknownActor = DungeonKeyAlertRules.resolveAlert(teammate, "Local_1", List.of());
		check(unknownActor.actorKind() == DungeonKeyAlertRules.ActorKind.UNATTRIBUTED
			&& "Blood Key was picked up".equals(unknownActor.text())
			&& !unknownActor.text().contains("Teammate"),
			"an unverified username falls back to a generic unattributed alert without leaking the name");
		DungeonKeyAlertRules.PickupAlert exactSystem = DungeonKeyAlertRules.resolveAlert(blood, "Local_1", currentParty);
		check(exactSystem.exactSystemLine() && exactSystem.actorKind() == DungeonKeyAlertRules.ActorKind.UNATTRIBUTED
			&& "Blood Key was picked up".equals(exactSystem.text()),
			"the exact server pickup message stays unattributed and has its own alert classification");

		check(DungeonKeyAlertRules.parsePickup("A Wither Key was picked up by Teammate!") == null,
			"an unapproved actor-bearing system-message variant is rejected");
		check(DungeonKeyAlertRules.parsePickup("Teammate has obtained Wither Key! extra") == null,
			"trailing text does not match the exact named message");
		check(DungeonKeyAlertRules.parsePickup("Teammate has obtained a Wither Key! extra") == null,
			"article-bearing named messages also reject trailing text");
		check(DungeonKeyAlertRules.parsePickup("Teammate Smith has obtained Wither Key!") == null,
			"actor parsing accepts one exact username token rather than a nearby name fragment");
		check(DungeonKeyAlertRules.parsePickup("A Wither Key was picked up") == null,
			"missing punctuation does not match the exact generic message");
		check(DungeonKeyAlertRules.parsePickup("a Wither Key was picked up!") == null,
			"generic pickup matching remains case-sensitive to the observed server line");
		check(DungeonKeyAlertRules.parsePickup("teammate has obtained Wither Key!") != null
			&& DungeonKeyAlertRules.classifyActor("teammate", "Local_1", currentParty)
			== DungeonKeyAlertRules.ActorKind.PARTY,
			"named pickup actor resolution ignores username case only after exact roster verification");
		check(DungeonKeyAlertRules.parsePickup("Teammate has obtained wither Key!") == null,
			"named pickup matching rejects an unobserved key-name case variant");

		check(DungeonKeyAlertRules.parsePickup("Wither Key") == null,
			"an old nearby item's display name alone is not a pickup-alert event");

		check(DungeonKeyAlertRules.matchArmorStandName(true, "Wither Key")
			== DungeonKeyAlertRules.KeyKind.WITHER,
			"the exact Wither Key ArmorStand name is recognized");
		check(DungeonKeyAlertRules.matchArmorStandName(true, "Blood Key")
			== DungeonKeyAlertRules.KeyKind.BLOOD,
			"the exact Blood Key ArmorStand name is recognized");
		check(DungeonKeyAlertRules.matchArmorStandName(false, "Wither Key") == null,
			"an exact key name on a non-ArmorStand entity is ignored");
		check(DungeonKeyAlertRules.matchArmorStandName(true, "Wither Keychain") == null
			&& DungeonKeyAlertRules.matchArmorStandName(true, "withER Key") == null
			&& DungeonKeyAlertRules.matchArmorStandName(true, " Blood Key") == null,
			"key-stand detection rejects substring, case, and whitespace variants");
		check(DungeonKeyAlertRules.matchArmorStandName(true, "Wither Key Item") == null,
			"an item display name cannot create an ArmorStand visual target");
		check(DungeonKeyAlertRules.shouldCollectRenderTargets(false, true)
			&& DungeonKeyAlertRules.shouldCollectRenderTargets(true, false)
			&& !DungeonKeyAlertRules.shouldCollectRenderTargets(false, false),
			"render targets are collected for highlight-only or tracer-only mode");

		DungeonKeyAlertRules.PickupDeduplicator duplicateLines = new DungeonKeyAlertRules.PickupDeduplicator();
		String namedLine = "Teammate has obtained Blood Key!";
		long now = 1_000_000_000L;
		check(!duplicateLines.shouldSuppress(namedLine, now), "the first confirmed line is not suppressed");
		check(duplicateLines.shouldSuppress("§aTeammate has obtained Blood Key!", now + 100_000_000L),
			"a formatting-only duplicate callback is suppressed");
		check(!duplicateLines.shouldSuppress(namedLine, now + 900_000_001L),
			"a later identical line after the short duplicate window is accepted again");
		DungeonKeyAlertRules.PickupDeduplicator trimDuplicates = new DungeonKeyAlertRules.PickupDeduplicator();
		check(!trimDuplicates.shouldSuppress("\u0001A Wither Key was picked up!\u0001", now),
			"the first control-padded message is accepted");
		check(trimDuplicates.shouldSuppress("A Wither Key was picked up!", now + 1),
			"duplicate suppression normalizes control padding like Java String.trim");
		DungeonKeyAlertRules.PickupDeduplicator unicodeTrimDuplicates = new DungeonKeyAlertRules.PickupDeduplicator();
		check(!unicodeTrimDuplicates.shouldSuppress("\u2003A Wither Key was picked up!\u2003", now),
			"the first Unicode-padded message is accepted");
		check(unicodeTrimDuplicates.shouldSuppress("A Wither Key was picked up!", now + 1),
			"duplicate suppression retains ChatText's Unicode space canonicalization");
		check(!duplicateLines.shouldSuppress("OtherPlayer has obtained Blood Key!", now + 950_000_000L),
			"a distinct actor's pickup line is not suppressed");

		DungeonKeyAlertRules.SpawnAnnouncementTracker spawnAnnouncements =
			new DungeonKeyAlertRules.SpawnAnnouncementTracker();
		UUID youngKey = UUID.fromString("50000000-0000-0000-0000-000000000001");
		check(spawnAnnouncements.shouldAnnounce(youngKey), "a newly metadata-detected key is eligible for one spawn alert");
		check(!spawnAnnouncements.shouldAnnounce(youngKey), "repeated metadata updates for one UUID never duplicate the alert");
		UUID existingKey = UUID.fromString("50000000-0000-0000-0000-000000000002");
		spawnAnnouncements.rememberExisting(existingKey);
		check(!spawnAnnouncements.shouldAnnounce(existingKey), "a bootstrap-seeded existing key does not trigger a spawn alert");
		spawnAnnouncements.clear();
		check(spawnAnnouncements.shouldAnnounce(youngKey), "a context reset permits UUIDs to be observed in a later dungeon");

		DungeonKeyAlertRules.KeyPickupArbitrator arbitrator = new DungeonKeyAlertRules.KeyPickupArbitrator();
		long packetTime = 5_000_000_000L;
		arbitrator.observeKeyEntity(41, DungeonKeyAlertRules.KeyKind.WITHER, packetTime);
		check(!arbitrator.beginPickupPacket(41, false, packetTime + 10)
			&& !arbitrator.beginPickupPacket(404, true, packetTime + 20),
			"non-local collectors and unobserved entity IDs cannot confirm a key pickup");
		arbitrator.observeKeyEntity(42, DungeonKeyAlertRules.KeyKind.BLOOD, packetTime + 100);
		check(arbitrator.beginPickupPacket(42, true, packetTime + 200),
			"a local pickup packet accepts a freshly observed exact key entity");
		check(arbitrator.takeReadyPacketPickups(packetTime + 200
			+ DungeonKeyAlertRules.POST_SPAWN_PICKUP_ARBITRATION_NANOS - 1).isEmpty(),
			"the packet fallback waits through the full chat arbitration window");
		DungeonKeyAlertRules.ChatPickupDecision packetThenChat = arbitrator.observeChatPickup(
			DungeonKeyAlertRules.KeyKind.BLOOD, "Local_1", "local_1", packetTime + 300);
		check(packetThenChat.canceledPendingPacket() && !packetThenChat.suppressDuplicateChat()
			&& arbitrator.takeReadyPacketPickups(packetTime + 1_000_000_000L).isEmpty(),
			"a matching local chat confirmation cancels the pending packet fallback and remains the sole alert source");

		arbitrator.observeKeyEntity(43, DungeonKeyAlertRules.KeyKind.WITHER, packetTime + 2_000_000_000L);
		arbitrator.observeChatPickup(DungeonKeyAlertRules.KeyKind.WITHER, "Local_1", "local_1",
			packetTime + 2_000_000_000L);
		check(!arbitrator.beginPickupPacket(43, true, packetTime + 2_000_000_001L),
			"a matching chat that arrives before its pickup packet prevents a duplicate packet fallback");
		check(arbitrator.takeReadyPacketPickups(packetTime + 3_000_000_000L).isEmpty(),
			"chat-first ordering does not queue a packet alert");

		arbitrator.observeKeyEntity(44, DungeonKeyAlertRules.KeyKind.WITHER, packetTime + 4_000_000_000L);
		check(arbitrator.beginPickupPacket(44, true, packetTime + 4_000_000_001L),
			"a later local pickup of the same key kind is accepted after the chat debounce expires");
		List<DungeonKeyAlertRules.PickupConfirmation> packetFallback = arbitrator.takeReadyPacketPickups(
			packetTime + 4_000_000_001L + DungeonKeyAlertRules.POST_SPAWN_PICKUP_ARBITRATION_NANOS);
		check(packetFallback.size() == 1 && packetFallback.getFirst().itemEntityId() == 44
			&& packetFallback.getFirst().kind() == DungeonKeyAlertRules.KeyKind.WITHER,
			"an exact key packet produces one delayed local fallback after the chat window expires");
		check(arbitrator.observeChatPickup(DungeonKeyAlertRules.KeyKind.WITHER, null, "Local_1",
			packetTime + 4_000_000_001L + DungeonKeyAlertRules.POST_SPAWN_PICKUP_ARBITRATION_NANOS + 1)
			.suppressDuplicateChat(),
			"a late matching system chat is suppressed after a packet fallback already alerted");
		arbitrator.clear();
		check(!arbitrator.beginPickupPacket(44, true, packetTime + 5_000_000_000L)
			&& arbitrator.takeReadyPacketPickups(packetTime + 5_000_000_000L).isEmpty(),
			"level reset clears observed keys, packet fallbacks, and pickup deduplication state");
	}

	private static void checkRecordAbi() {
		checkRecordShape(DungeonKeyAlertRules.PickupEvent.class,
			new String[] { "keyKind", "actor" },
			new Class<?>[] { DungeonKeyAlertRules.KeyKind.class, String.class });
		checkRecordShape(DungeonKeyAlertRules.PickupAlert.class,
			new String[] { "actorKind", "text", "exactSystemLine" },
			new Class<?>[] { DungeonKeyAlertRules.ActorKind.class, String.class, boolean.class });
		checkRecordShape(DungeonKeyAlertRules.ChatPickupDecision.class,
			new String[] { "canceledPendingPacket", "suppressDuplicateChat" },
			new Class<?>[] { boolean.class, boolean.class });
		checkRecordShape(DungeonKeyAlertRules.PickupConfirmation.class,
			new String[] { "itemEntityId", "kind" },
			new Class<?>[] { int.class, DungeonKeyAlertRules.KeyKind.class });

		DungeonKeyAlertRules.PickupEvent event =
			new DungeonKeyAlertRules.PickupEvent(DungeonKeyAlertRules.KeyKind.WITHER, "Local_1");
		DungeonKeyAlertRules.PickupEvent sameEvent =
			new DungeonKeyAlertRules.PickupEvent(DungeonKeyAlertRules.KeyKind.WITHER, "Local_1");
		check(event.equals(sameEvent) && event.hashCode() == sameEvent.hashCode()
			&& !event.equals(new DungeonKeyAlertRules.PickupEvent(DungeonKeyAlertRules.KeyKind.BLOOD, "Local_1")),
			"PickupEvent retains component-based equality and hash behavior");
		check("PickupEvent[keyKind=WITHER, actor=Local_1]".equals(event.toString()),
			"PickupEvent retains Java record toString formatting");
		DungeonKeyAlertRules.PickupEvent nullEvent = new DungeonKeyAlertRules.PickupEvent(null, null);
		check(nullEvent.keyKind() == null && nullEvent.actor() == null
			&& "PickupEvent[keyKind=null, actor=null]".equals(nullEvent.toString()),
			"PickupEvent canonical construction and accessors preserve nullable reference components");

		DungeonKeyAlertRules.PickupAlert alert = new DungeonKeyAlertRules.PickupAlert(
			DungeonKeyAlertRules.ActorKind.PARTY, "Teammate picked up Blood Key", false);
		DungeonKeyAlertRules.PickupAlert sameAlert = new DungeonKeyAlertRules.PickupAlert(
			DungeonKeyAlertRules.ActorKind.PARTY, "Teammate picked up Blood Key", false);
		check(alert.equals(sameAlert) && alert.hashCode() == sameAlert.hashCode()
			&& !alert.equals(new DungeonKeyAlertRules.PickupAlert(
				DungeonKeyAlertRules.ActorKind.PARTY, "Other text", false)),
			"PickupAlert retains component-based equality and hash behavior");
		check("PickupAlert[actorKind=PARTY, text=Teammate picked up Blood Key, exactSystemLine=false]"
			.equals(alert.toString()), "PickupAlert retains Java record toString formatting");
		DungeonKeyAlertRules.PickupAlert nullAlert = new DungeonKeyAlertRules.PickupAlert(null, null, false);
		check(nullAlert.actorKind() == null && nullAlert.text() == null
			&& "PickupAlert[actorKind=null, text=null, exactSystemLine=false]".equals(nullAlert.toString()),
			"PickupAlert canonical construction and accessors preserve nullable reference components");

		DungeonKeyAlertRules.ChatPickupDecision decision = new DungeonKeyAlertRules.ChatPickupDecision(true, false);
		DungeonKeyAlertRules.ChatPickupDecision sameDecision = new DungeonKeyAlertRules.ChatPickupDecision(true, false);
		check(decision.equals(sameDecision) && decision.hashCode() == sameDecision.hashCode()
			&& !decision.equals(new DungeonKeyAlertRules.ChatPickupDecision(true, true)),
			"ChatPickupDecision retains component-based equality and hash behavior");
		check("ChatPickupDecision[canceledPendingPacket=true, suppressDuplicateChat=false]"
			.equals(decision.toString()), "ChatPickupDecision retains Java record toString formatting");

		DungeonKeyAlertRules.PickupConfirmation confirmation =
			new DungeonKeyAlertRules.PickupConfirmation(41, DungeonKeyAlertRules.KeyKind.WITHER);
		DungeonKeyAlertRules.PickupConfirmation sameConfirmation =
			new DungeonKeyAlertRules.PickupConfirmation(41, DungeonKeyAlertRules.KeyKind.WITHER);
		check(confirmation.equals(sameConfirmation) && confirmation.hashCode() == sameConfirmation.hashCode()
			&& !confirmation.equals(new DungeonKeyAlertRules.PickupConfirmation(42, DungeonKeyAlertRules.KeyKind.WITHER)),
			"PickupConfirmation retains component-based equality and hash behavior");
		check("PickupConfirmation[itemEntityId=41, kind=WITHER]".equals(confirmation.toString()),
			"PickupConfirmation retains Java record toString formatting");
		DungeonKeyAlertRules.PickupConfirmation nullConfirmation =
			new DungeonKeyAlertRules.PickupConfirmation(41, null);
		check(nullConfirmation.kind() == null
			&& "PickupConfirmation[itemEntityId=41, kind=null]".equals(nullConfirmation.toString()),
			"PickupConfirmation canonical construction and accessors preserve nullable reference components");
	}

	private static void checkRecordShape(Class<?> type, String[] componentNames, Class<?>[] componentTypes) {
		check(type.isRecord(), type.getSimpleName() + " retains Java record metadata");
		check(type.getEnclosingClass() == DungeonKeyAlertRules.class
			&& Modifier.isPublic(type.getModifiers()) && Modifier.isStatic(type.getModifiers()),
			type.getSimpleName() + " remains a public nested type of DungeonKeyAlertRules");
		RecordComponent[] components = type.getRecordComponents();
		check(components.length == componentNames.length,
			type.getSimpleName() + " retains its record component count");
		for (int i = 0; i < components.length; i++) {
			RecordComponent component = components[i];
			check(component.getName().equals(componentNames[i]) && component.getType() == componentTypes[i],
				type.getSimpleName() + " component " + i + " retains its name, order, and type");
			check(component.getAccessor().getName().equals(componentNames[i])
				&& component.getAccessor().getReturnType() == componentTypes[i],
				type.getSimpleName() + " exposes the baseline Java component accessor " + componentNames[i]);
		}
		try {
			Constructor<?> canonicalConstructor = type.getDeclaredConstructor(componentTypes);
			check(Modifier.isPublic(canonicalConstructor.getModifiers()),
				type.getSimpleName() + " retains its public canonical constructor");
		} catch (NoSuchMethodException exception) {
			throw new IllegalStateException(type.getSimpleName() + " is missing its canonical record constructor", exception);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
