package geiler.addons.client.dungeon.odin;

import geiler.addons.client.dungeon.DungeonClass;
import geiler.addons.client.party.PartyMember;

import java.util.List;

/** Focused checks for party-chat and client-position correlation of incoming leap arrivals. */
public final class SpiritLeapIncomingChecks {
	private SpiritLeapIncomingChecks() { }
	public static void main(String[] args) { run(); }

	public static void run() {
		checkIncomingRecordCompatibility();
		List<String> roster = List.of("Self", "Alice", "Bob");
		var candidate = SpiritLeapIncomingRules.parse(
			"Party > [MVP+] Alice: Self has the route notes", "Self", roster).orElseThrow();
		equals("Alice", candidate.teammateName(), "the current party sender is retained as the candidate teammate");
		equals("Self", candidate.targetName(), "the mentioned name is the local target");
		check(SpiritLeapIncomingRules.parse("Party > Self: Alice leaped here; Bob is next", "Self", roster).isEmpty(),
			"self-authored party chat cannot trigger incoming feedback");
		check(SpiritLeapIncomingRules.parse("Party > Alice: Bobber is ready", "Bob", roster).isEmpty(),
			"username substring without a token boundary does not match");
		check(SpiritLeapIncomingRules.parse("Party > Stranger: Self is ready", "Self", roster).isEmpty(),
			"unverified party sender is ignored");
		check(SpiritLeapIncomingRules.parse("Party > Alice: Self is ready", "Self", List.of("Alice")).isEmpty(),
			"local player must be present in the verified roster");
		check(SpiritLeapIncomingRules.parse("You have teleported to Alice!", "Self", roster).isEmpty(),
			"server-confirmed outgoing completion is not an incoming party signal");
		check(SpiritLeapIncomingRules.parse("Guild > Alice: Self is ready", "Self", roster).isEmpty(),
			"guild chat cannot trigger incoming leap feedback");
		check(SpiritLeapIncomingRules.parse("Party > Alice: Self is ready", "Self", List.of()).isEmpty(),
			"unverified roster cannot trigger incoming feedback");
		var wordingVariants = SpiritLeapIncomingRules.parse(
			"Party > Alice: tp successful to Self; can you enter now?", "Self", roster).orElseThrow();
		check(wordingVariants.equals(candidate),
			"incoming party detection uses an explicit roster name mention rather than announce wording");
		check(SpiritLeapIncomingRules.parse("Party > Alice: tp successful; can you enter now?", "Self", roster).isEmpty(),
			"incoming detection does not infer the local target when its name is absent");
		var confirmedIncoming = candidate;
		var nullEntryTitle = SpiritLeapIncomingPresentation.title(confirmedIncoming,
			java.util.Arrays.asList(null, new PartyMember("Alice", null, DungeonClass.MAGE)),
			java.util.Arrays.asList(null, new OdinDungeonPlayerMap.TabPlayer("Alice", "Archer", false)), 80)
			.orElseThrow();
		equals("Leap arrival: Alice · Archer", nullEntryTitle.text(),
			"nullable Java roster and tab-list entries are skipped while resolving the incoming title");
		var title = SpiritLeapIncomingPresentation.title(confirmedIncoming, List.of(
			new PartyMember("Self", null, DungeonClass.ARCHER),
			new PartyMember("Alice", null, DungeonClass.MAGE)), List.of(
				new OdinDungeonPlayerMap.TabPlayer("Alice", "Archer", false)), 80).orElseThrow();
		equals("Leap arrival: Alice · Archer", title.text(),
			"incoming title uses the tab class when it conflicts with the party snapshot class");
		equals(80, title.durationTicks(), "incoming title uses its configured duration");
		var unknownClass = SpiritLeapIncomingPresentation.title(confirmedIncoming, List.of(
			new PartyMember("Alice", null, DungeonClass.MAGE)), List.of(
				new OdinDungeonPlayerMap.TabPlayer("Alice", "Unknown", false)), 0).orElseThrow();
		equals("Leap arrival: Alice · Class unknown", unknownClass.text(),
			"invalid tab class data is explicit and does not fall back to the party class");
		equals(SpiritLeapIncomingPresentation.MIN_DURATION_TICKS, unknownClass.durationTicks(),
			"incoming title duration is clamped to its minimum");
		equals(SpiritLeapIncomingPresentation.MAX_DURATION_TICKS,
			SpiritLeapIncomingPresentation.title(confirmedIncoming, List.of(new PartyMember("Alice", null, null)),
				List.of(new OdinDungeonPlayerMap.TabPlayer("Alice", "Mage", false)),
				Integer.MAX_VALUE).orElseThrow().durationTicks(), "incoming title duration is clamped to its maximum");
		var missingTabClass = SpiritLeapIncomingPresentation.title(confirmedIncoming,
			List.of(new PartyMember("Alice", null, DungeonClass.MAGE)), List.of(), 80).orElseThrow();
		equals("Leap arrival: Alice · Class unknown", missingTabClass.text(),
			"missing tab class data shows unknown instead of falling back to the party class");
		check(SpiritLeapIncomingPresentation.title(confirmedIncoming, List.of(),
			List.of(new OdinDungeonPlayerMap.TabPlayer("Alice", "Mage", false)), 80).isEmpty(),
			"title still requires the arrival teammate to be in the current party snapshot");

		SpiritLeapIncomingRules.Coalescer coalescer = new SpiritLeapIncomingRules.Coalescer();
		check(coalescer.accept(confirmedIncoming, 100L), "first chat-derived arrival is accepted");
		check(!coalescer.accept(wordingVariants, 100L + SpiritLeapIncomingRules.COALESCE_NANOS),
			"duplicate lines from the same teammate coalesce even when their wording differs");
		check(coalescer.accept(confirmedIncoming, 101L + SpiritLeapIncomingRules.COALESCE_NANOS),
			"same teammate can alert again after coalescing expires");
		coalescer.clear();
		check(coalescer.accept(confirmedIncoming, 200L), "reset clears incoming signal coalescing");
	}

	private static void checkIncomingRecordCompatibility() {
		checkRecordComponents(SpiritLeapIncomingRules.Incoming.class,
			new String[] { "teammateName", "targetName" }, String.class, String.class);
		checkRecordComponents(SpiritLeapIncomingPresentation.Title.class,
			new String[] { "playerName", "className", "durationTicks" },
			String.class, String.class, int.class);

		var incoming = new SpiritLeapIncomingRules.Incoming(null, null);
		var equalIncoming = new SpiritLeapIncomingRules.Incoming(null, null);
		var differentIncoming = new SpiritLeapIncomingRules.Incoming(null, "");
		check(incoming.equals(equalIncoming) && equalIncoming.equals(incoming)
			&& incoming.hashCode() == equalIncoming.hashCode() && !incoming.equals(differentIncoming)
			&& incoming.toString().equals("Incoming[teammateName=null, targetName=null]"),
			"Incoming accepts nullable components and keeps Java record value methods");
		check(!new SpiritLeapIncomingRules.Coalescer().accept(incoming, 1L),
			"incoming coalescing still rejects a record with null names");

		var title = new SpiritLeapIncomingPresentation.Title(null, null, 0);
		var equalTitle = new SpiritLeapIncomingPresentation.Title(null, null, 0);
		var differentTitle = new SpiritLeapIncomingPresentation.Title(null, null, 1);
		check(title.equals(equalTitle) && equalTitle.equals(title)
			&& title.hashCode() == equalTitle.hashCode() && !title.equals(differentTitle)
			&& title.toString().equals("Title[playerName=null, className=null, durationTicks=0]")
			&& title.text().equals("Leap arrival: null · null"),
			"Title accepts nullable components and preserves record values plus Java string formatting");
	}

	private static void checkRecordComponents(Class<?> type, String[] names, Class<?>... componentTypes) {
		var components = type.getRecordComponents();
		check(type.isRecord() && components != null && components.length == names.length
			&& componentTypes.length == names.length,
			type.getSimpleName() + " retains Java record metadata and component count");
		for (int index = 0; index < names.length; index++) {
			check(components[index].getName().equals(names[index])
				&& components[index].getType() == componentTypes[index]
				&& components[index].getAccessor().getName().equals(names[index])
				&& components[index].getAccessor().getReturnType() == componentTypes[index],
				type.getSimpleName() + " retains record component " + index + " name, order, and type");
		}
		try {
			type.getConstructor(componentTypes);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(type.getSimpleName() + " retains its public canonical constructor", failure);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void equals(Object expected, Object actual, String message) {
		if (expected == null ? actual != null : !expected.equals(actual))
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
}
