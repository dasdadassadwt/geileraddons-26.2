package geiler.addons.client.dungeon.score;

import geiler.addons.client.dungeon.DungeonFloor;
import java.util.ArrayList;
import java.util.List;

/** Offline checks for the Odin-style latched dungeon score state (tab, team, chat, boss, Paul, titles). */
public final class DungeonScoreStateChecks {
	private DungeonScoreStateChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		checkTotalRoomsUsesRawCompletedRooms();
		checkLatchingThroughMissingRows();
		checkBonusesFromPartyChat();
		checkBloodDoneSequence();
		checkBossFlag();
		checkPaul();
		checkMissingInputsDefaultLikeOdin();
		checkFloorIsReadOnce();
		checkNeededSecretsAndFloorRequirement();
		checkScoreCallbackAndReset();
		checkBossPositions();
		checkTitleTracker();
		checkPaulParse();
	}

	/** F7, 8 of 50 secrets, 23 rooms at 92 percent, three puzzles done, 3 crypts: the 22 Nov log fixture. */
	private static DungeonScoreCore fixture() {
		DungeonScoreCore core = new DungeonScoreCore();
		core.onTeamText("The Catacombs (F7)");
		core.onTeamText("Cleared: 92% (46)");
		core.onTabRows(tab());
		return core;
	}

	private static List<String> tab() {
		return List.of(" Secrets Found: 8", " Secrets Found: 16.0%", " Completed Rooms: 23", "Puzzles: (3)",
			"Team Deaths: 0", " Crypts: 3", " Creeper Beams: [✔] (Alice)", " Water Board: [✔]", " Boulder: [✔] (Bob)",
			" Time: 12m 3s");
	}

	private static void checkTotalRoomsUsesRawCompletedRooms() {
		DungeonScoreCore core = fixture();
		check(core.getTotalRooms() == 25, "23 rooms at 92 percent is 25 total rooms, not 27 (raw tab count, not padded)");
		check(core.getTotalSecrets() == 50, "8 found at 16 percent is 50 secrets");
		check(core.getScore() == 269, "fixture score is 6 + 60 + 100 + 3 + 100 = 269, got " + core.getScore());
		check("12m 3s".equals(core.getElapsedTime()), "time row is read");
	}

	private static void checkLatchingThroughMissingRows() {
		DungeonScoreCore core = fixture();
		core.onTabRows(List.of(" Some Player [Lv50]", ""));
		check(core.getScore() == 269 && core.getSecretCount() == 8 && core.getCompletedRooms() == 23
			&& core.getCryptCount() == 3, "rows that are absent keep the last value instead of going unknown");
		core.onTabRows(List.of(" Secrets Found: 9"));
		check(core.getSecretCount() == 9 && core.getCompletedRooms() == 23, "a single new row updates only its own field");
	}

	private static void checkBonusesFromPartyChat() {
		DungeonScoreCore core = fixture();
		core.onChat("Party > [MVP+] Bob: Mimic Killed!");
		check(core.getMimicKilled() && core.getScore() == 271, "mimic killed on F7 adds 2");
		core.onChat("Party > Bob: prince killed");
		check(core.getPrinceKilled() && core.getScore() == 272, "prince adds 1");
		core.onChat("Party > Bob: bat dead");
		core.onChat("Party > Bob: bat dead!");
		check(core.getNumBatsKilled() == 1 && core.getScore() == 273, "a bat killer counts once");
		core.onChat("Party > Alice: bat slain");
		check(core.getNumBatsKilled() == 2 && core.getScore() == 274, "each different killer adds 1");
		core.onChat("Party > Alice: hello there");
		check(core.getScore() == 274, "other party chat changes nothing");

		DungeonScoreCore f5 = new DungeonScoreCore();
		f5.onTeamText("The Catacombs (F5)");
		f5.onChat("Party > Bob: mimic killed");
		check(!f5.getMimicKilled(), "a Mimic line on a floor without a Mimic is ignored");

		DungeonScoreCore blaze = fixture();
		blaze.onTabRows(List.of(" Higher Or Lower: [✖] (Alice)", "Puzzles: (4)"));
		int before = blaze.getScore();
		blaze.onChat("Party > Bob: blaze done!");
		check(blaze.getScore() == before + 10, "the blaze party line completes the Blaze puzzle (+10 penalty removed)");
	}

	private static void checkBloodDoneSequence() {
		DungeonScoreCore core = fixture();
		core.onTeamText("Cleared: 93% (47)");
		check(!core.getBloodDone(), "a clear percent change alone does not mean blood is done");
		core.onChat("[BOSS] The Watcher: You have proven yourself. You may pass.");
		check(!core.getBloodDone(), "the watcher line only arms the next update");
		core.onTeamText("Cleared: 93% (47)");
		check(!core.getBloodDone(), "an unchanged percent does not finish blood");
		core.onTeamText("Cleared: 95% (48)");
		check(core.getBloodDone() && core.getPercentCleared() == 95, "the next percent change after the watcher line finishes blood");
	}

	private static void checkBossFlag() {
		DungeonScoreCore core = fixture();
		core.setBoss(true);
		check(core.getInBoss() && core.getScore() == 262, "in boss the boss room stops counting: 6 + 57 + 96 + 3 + 100 = 262, got "
			+ core.getScore());
		core.setBoss(false);
		check(core.getScore() == 269, "leaving boss restores the clear score");
	}

	private static void checkPaul() {
		DungeonScoreCore core = fixture();
		core.setPaul(true);
		check(core.getScore() == 279, "Paul adds 10");
		core.setPaulOverride(Boolean.FALSE);
		check(core.getScore() == 269, "an override of NO beats the detected Paul");
		core.setPaulOverride(Boolean.TRUE);
		core.setPaul(false);
		check(core.getScore() == 279, "an override of YES beats a missing Paul");
		core.setPaulOverride(null);
		check(core.getScore() == 269, "Auto uses the detected value");
	}

	private static void checkMissingInputsDefaultLikeOdin() {
		DungeonScoreCore noFloor = new DungeonScoreCore();
		noFloor.onTabRows(List.of(" Time: 1s"));
		check(noFloor.getScore() == 124, "no floor and no rows: exploration 0, skill 24, plus 100 is 124, got " + noFloor.getScore());
		DungeonScoreCore floorOnly = new DungeonScoreCore();
		floorOnly.onTeamText("The Catacombs (F7)");
		floorOnly.onTabRows(List.of(" Time: 1s"));
		check(floorOnly.getScore() == 127, "floor only: 3 + 24 + 100 = 127 with the 36 room fallback, got " + floorOnly.getScore());
		check(floorOnly.getTotalRooms() == 0 && floorOnly.getTotalSecrets() == 0, "totals stay zero until their inputs arrive");
	}

	private static void checkFloorIsReadOnce() {
		DungeonScoreCore core = new DungeonScoreCore();
		check(core.onTeamText("The Catacombs (M7)"), "the first floor text reports a new floor");
		check(core.getFloor() == DungeonFloor.M7, "M7 is parsed");
		check(!core.onTeamText("The Catacombs (F1)") && core.getFloor() == DungeonFloor.M7, "a later floor text is ignored");
		DungeonScoreCore entrance = new DungeonScoreCore();
		entrance.onTeamText("The Catacombs (E)");
		check(entrance.getFloor() == DungeonFloor.ENTRANCE, "E is the entrance");
		DungeonScoreCore other = new DungeonScoreCore();
		check(!other.onTeamText("Some Other Area (X)") && other.getFloor() == null, "non catacombs text is not a floor");
	}

	private static void checkNeededSecretsAndFloorRequirement() {
		DungeonScoreCore core = fixture();
		check(core.getNeededSecrets() == 47, "F7 needs ceil(50 * 1.0 * (40 - 3) / 40) = 47, got " + core.getNeededSecrets());
		check(core.getBonusScore() == 3, "bonus is the crypts");
		DungeonScoreCore f5 = new DungeonScoreCore();
		f5.onTeamText("The Catacombs (F5)");
		f5.onTabRows(tab());
		f5.onTeamText("Cleared: 92% (46)");
		check(f5.getScore() == 272, "F5 requires 70 percent: secret score 9 instead of 6, got " + f5.getScore());
		check(DungeonScoreCore.requiredPercentage(DungeonFloor.F6) == 0.85f
			&& DungeonScoreCore.requiredPercentage(DungeonFloor.ENTRANCE) == 0.3f
			&& DungeonScoreCore.requiredPercentage(DungeonFloor.M3) == 1f
			&& DungeonScoreCore.requiredPercentage(null) == 1f, "per floor secret requirements match Odin");
	}

	private static void checkScoreCallbackAndReset() {
		DungeonScoreCore core = new DungeonScoreCore();
		List<Integer> seen = new ArrayList<>();
		core.setOnScore(score -> { seen.add(score); return kotlin.Unit.INSTANCE; });
		core.onTeamText("The Catacombs (F7)");
		core.onTeamText("Cleared: 92% (46)");
		core.onTabRows(tab());
		core.onTabRows(tab());
		check(seen.size() >= 1 && seen.get(seen.size() - 1) == 269, "the callback reports the new score");
		int count = seen.size();
		core.onTabRows(tab());
		check(seen.size() == count, "an unchanged score is not reported again");
		core.reset();
		check(core.getScore() == 0 && core.getFloor() == null && core.getSecretCount() == 0 && !core.getInBoss()
			&& core.getCryptCount() == 0 && !core.getBloodDone(), "reset clears the whole run");
	}

	private static void checkBossPositions() {
		check(DungeonScoreCore.inBossAt(7, -6.0, -6.0) && !DungeonScoreCore.inBossAt(7, -8.0, -6.0), "F7 boss starts at -7/-7");
		check(DungeonScoreCore.inBossAt(1, -70.0, -38.0) && !DungeonScoreCore.inBossAt(1, -72.0, -38.0), "F1 boss starts at -71/-39");
		check(DungeonScoreCore.inBossAt(3, -38.0, -38.0) && !DungeonScoreCore.inBossAt(3, -38.0, -40.0), "F2-F4 boss starts at -39/-39");
		check(DungeonScoreCore.inBossAt(6, -38.0, -6.0) && !DungeonScoreCore.inBossAt(6, -38.0, -8.0), "F5-F6 boss starts at -39/-7");
		check(!DungeonScoreCore.inBossAt(0, 100.0, 100.0), "the entrance has no boss room");
	}

	private static void checkTitleTracker() {
		ScoreTitleTracker tracker = new ScoreTitleTracker();
		check(tracker.update(269).isEmpty(), "269 fires nothing");
		check(tracker.update(270).equals(List.of(270)), "270 fires the 270 title");
		check(tracker.update(272).isEmpty() && tracker.update(270).isEmpty(), "270 fires only once per run");
		check(tracker.update(300).equals(List.of(300)), "300 fires the 300 title");
		check(tracker.update(305).isEmpty(), "300 fires only once per run");
		tracker.reset();
		check(tracker.update(310).equals(List.of(270, 300)), "a first score above both fires both once after a reset");
		check(tracker.update(-1).isEmpty(), "invalid scores are ignored");
	}

	private static void checkPaulParse() {
		check(DungeonPaulLookup.parse("{\"success\":true,\"mayor\":{\"name\":\"Paul\",\"perks\":[{\"name\":\"EZPZ\"}]}}"),
			"Paul with EZPZ is the bonus");
		check(!DungeonPaulLookup.parse("{\"mayor\":{\"name\":\"Paul\",\"perks\":[{\"name\":\"Marauder\"}]}}"),
			"Paul without EZPZ is not");
		check(!DungeonPaulLookup.parse("{\"mayor\":{\"name\":\"Diaz\",\"perks\":[{\"name\":\"EZPZ\"}]}}"),
			"another mayor is not");
		check(!DungeonPaulLookup.parse("not json") && !DungeonPaulLookup.parse(null) && !DungeonPaulLookup.parse("{}"),
			"broken or empty input is not the bonus");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("DungeonScoreStateChecks: " + message);
	}
}
