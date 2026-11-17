package geiler.addons.client.mining;

import java.util.List;

/** Offline checks for heat, cold, goblin, Fetchur, Puzzler and mining event text rules. */
public final class MiningConditionsChecks {
	private MiningConditionsChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		require(Integer.valueOf(14).equals(MiningConditions.heat(List.of("Purse: 5", "Heat: 14♨"))), "heat is read");
		require(Integer.valueOf(0).equals(MiningConditions.heat(List.of("Heat: IMMUNE"))), "immune heat reads as zero");
		require(MiningConditions.heat(List.of("Cold: -3❄")) == null, "no heat line is unknown");
		require(Integer.valueOf(3).equals(MiningConditions.cold(List.of("Cold: -3❄"))), "cold is read as a positive number");
		require(MiningConditions.cold(List.of()) == null, "no cold line is unknown");
		require(MiningConditions.coldAlpha(40, 50, 60) == 0f, "no overlay under the threshold");
		require(Math.abs(MiningConditions.coldAlpha(100, 50, 60) - 0.6f) < 1e-6, "full cold shows the maximum opacity");
		require(Math.abs(MiningConditions.coldAlpha(75, 50, 100) - 0.5f) < 1e-6, "opacity rises linearly");
		require(MiningConditions.coldAlpha(90, 100, 50) == 0f, "a threshold of 100 never shows");
		require(MiningConditions.isHeatSound("minecraft:entity.wolf.pant", 1.0f, 0.0f), "the heat pant sound is matched");
		require(!MiningConditions.isHeatSound("minecraft:entity.wolf.pant", 1.0f, 1.0f), "a normal pant is not the heat sound");

		require(MiningConditions.isGoldenGoblinSpawn("A Golden Goblin has spawned!") && MiningConditions.isDiamondGoblinSpawn("A Diamond Goblin has spawned!"), "spawn messages match");
		require(!MiningConditions.isGoldenGoblinSpawn("A Goblin has spawned!"), "other messages do not");
		require(MiningConditions.isGoblinLabel("Golden Goblin") && !MiningConditions.isGoblinLabel("Goblin"), "only the rare goblin labels match");
		require(MiningConditions.isDonExpressoFull("[NPC] Don Expresso: I DON'T FEEL SO GOOD..."), "Don Expresso full message");

		require("Mithril".equals(MiningConditions.fetchurItem("[NPC] Fetchur: its expensive minerals")), "a hint maps to its item");
		require("Superboom TNT".equals(MiningConditions.fetchurItem("[NPC] Fetchur: theyre explosive but more than usual")), "theyre also matches");
		require("something odd".equals(MiningConditions.fetchurItem("[NPC] Fetchur: its something odd")), "an unknown hint is shown as it is");
		require(MiningConditions.fetchurItem("[NPC] Dulin: hi") == null, "other NPCs are ignored");

		kotlin.Pair<Integer, Integer> start = MiningConditions.puzzlerSpot("[NPC] Puzzler: ▲▲▲▲▲▲▲▲▲▲");
		require(start != null && start.getFirst() == 181 && start.getSecond() == 145, "ten up arrows walk ten blocks +z");
		kotlin.Pair<Integer, Integer> mixed = MiningConditions.puzzlerSpot("[NPC] Puzzler: ◀◀◀▶▼▼▲▲▲▲");
		require(mixed != null && mixed.getFirst() == 183 && mixed.getSecond() == 137, "arrows combine, got " + mixed);
		require(MiningConditions.puzzlerSpot("[NPC] Puzzler: ▲▲") == null, "fewer than ten arrows is not a puzzle");

		MiningConditions.Event event = MiningConditions.miningEvent(List.of("Area: Dwarven Mines", "Event: 2X POWDER", " Ends in: 12m 30s", "Pet: None"));
		require(event != null && "2X POWDER".equals(event.getName()) && Long.valueOf(750).equals(event.getSecondsLeft()), "the event and its time are read");
		MiningConditions.Event clock = MiningConditions.miningEvent(List.of("Event: GOBLIN RAID", "Ends in: 04:05"));
		require(clock != null && Long.valueOf(245).equals(clock.getSecondsLeft()), "a mm:ss time is read");
		MiningConditions.Event untimed = MiningConditions.miningEvent(List.of("Event: GONE WITH THE WIND"));
		require(untimed != null && untimed.getSecondsLeft() == null, "no time row means unknown time");
		require(MiningConditions.miningEvent(List.of("Event: Jacob's Contest")) == null, "other events are not mining events");
		require(MiningConditions.miningEvent(List.of("Area: Hub")) == null, "no widget means no event");
		require(Long.valueOf(3725).equals(MiningConditions.seconds("1h 2m 5s")), "hours, minutes and seconds add up");
		require("2x Powder".equals(MiningConditions.eventTitle("2X POWDER")) || "2x Powder".equalsIgnoreCase(MiningConditions.eventTitle("2X POWDER")), "event titles are readable");
		System.out.println("Mining conditions checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
