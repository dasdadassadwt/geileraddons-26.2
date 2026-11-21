package geiler.addons.client.dungeon.helpers;

import java.util.List;
import java.util.Map;

/** Pure acceptance checks for confirmed Spirit Leap messages and duplicate alerts. */
public final class SpiritLeapArrivalChecks {
	private SpiritLeapArrivalChecks() { }

	public static void run() {
		checkArrivalRecordCompatibility();
		SpiritLeapArrivalRules.Arrival own = SpiritLeapArrivalRules.parse("You have teleported to ArcherOne!").orElseThrow();
		require(own.playerName().equals("ArcherOne"),
			"outgoing leap alerts require the completed teleport confirmation");
		require(SpiritLeapArrivalRules.parse("§eYou have teleported to TankOne!").orElseThrow()
			.playerName().equals("TankOne"), "outgoing confirmation parsing tolerates formatting");
		require(SpiritLeapArrivalRules.parse("§eTankOne has teleported to you!").isEmpty()
			&& SpiritLeapArrivalRules.parse("Teleporting to ArcherOne.").isEmpty()
			&& SpiritLeapArrivalRules.parse("You have teleported to an invalid-name!").isEmpty(),
			"unverified received-leap text, pending, and malformed lines cannot create an alert");

		SpiritLeapArrivalRules.Coalescer coalescer = new SpiritLeapArrivalRules.Coalescer();
		require(coalescer.accept(own, 100L)
			&& !coalescer.accept(own, 100L + SpiritLeapArrivalRules.COALESCE_NANOS)
			&& coalescer.accept(own, 101L + SpiritLeapArrivalRules.COALESCE_NANOS),
			"duplicate outgoing confirmations coalesce for two seconds and can alert again afterward");

		List<SpiritLeapRoster.Card> classCards = List.of(new SpiritLeapRoster.Card(0,
			new SpiritLeapRoster.Member("BerserkerOne", "Berserker", 70, true)));
		var classTarget = SpiritLeapSelection.targetForKey(true, "Spirit Leap", "2",
			SpiritLeapSelection.KeyMode.CLASS, Map.of(), Map.of("Berserk", "2"), classCards,
			List.of(new SpiritLeapSelection.MenuHead(12, "BerserkerOne", true)));
		require(classTarget.isPresent() && classTarget.orElseThrow().containerSlot() == 12,
			"a configured class key resolves Berserker/Berserk to the direct menu slot");
		require(SpiritLeapSelection.targetForKey(true, "Chest", "2", SpiritLeapSelection.KeyMode.CLASS,
			Map.of(), Map.of("Berserk", "2"), classCards,
			List.of(new SpiritLeapSelection.MenuHead(12, "BerserkerOne", true))).isEmpty(),
			"class keys do not click outside the validated leap menu");
	}

	private static void checkArrivalRecordCompatibility() {
		var components = SpiritLeapArrivalRules.Arrival.class.getRecordComponents();
		require(SpiritLeapArrivalRules.Arrival.class.isRecord() && components != null
			&& components.length == 1 && components[0].getName().equals("playerName")
			&& components[0].getType() == String.class
			&& components[0].getAccessor().getName().equals("playerName"),
			"Arrival keeps the Java record component name, order, and type");
		try {
			SpiritLeapArrivalRules.Arrival.class.getConstructor(String.class);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("Arrival keeps its public canonical constructor", failure);
		}

		var absentName = new SpiritLeapArrivalRules.Arrival(null);
		var equalAbsentName = new SpiritLeapArrivalRules.Arrival(null);
		require(absentName.equals(equalAbsentName) && absentName.hashCode() == equalAbsentName.hashCode()
			&& absentName.toString().equals("Arrival[playerName=null]"),
			"Arrival accepts a null component and keeps record value methods");
		require(!new SpiritLeapArrivalRules.Coalescer().accept(absentName, 1L),
			"the arrival coalescer still rejects a record with a null player name");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
