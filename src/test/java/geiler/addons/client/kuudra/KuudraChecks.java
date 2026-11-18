package geiler.addons.client.kuudra;

import java.util.List;

/** Offline checks for the Kuudra rules and bundled data: phases, tier, build progress, health, piles, pearls, waypoints, danger. */
public final class KuudraChecks {
	private KuudraChecks() { }

	public static void run() {
		checkPhases();
		checkTextRules();
		checkGeometry();
		checkBundledData();
		checkParsers();
	}

	private static void checkPhases() {
		check(KuudraPhase.fromMessage("[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!") == KuudraPhase.SUPPLIES, "supplies line");
		check(KuudraPhase.fromMessage("[NPC] Elle: OMG! Great work collecting my supplies!") == KuudraPhase.BUILD, "build line");
		check(KuudraPhase.fromMessage("[NPC] Elle: Phew! The Ballista is finally ready! It should be strong enough to tank Kuudra's blows now!") == KuudraPhase.EATEN, "eaten line");
		check(KuudraPhase.fromMessage("Sam has been eaten by Kuudra!") == KuudraPhase.STUN, "someone eaten starts the stun");
		check(KuudraPhase.fromMessage("[NPC] Elle: Sam has been eaten by Kuudra!") == null, "Elle's own eaten line is not the stun");
		check(KuudraPhase.fromMessage("Sam destroyed one of Kuudra's pods!") == KuudraPhase.DPS, "a destroyed pod starts the DPS phase");
		check(KuudraPhase.fromMessage("[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!") == KuudraPhase.SKIP, "skip line");
		check(KuudraPhase.fromMessage("KUUDRA DOWN!") == KuudraPhase.COMPLETED && KuudraPhase.fromMessage("Sam: KUUDRA DOWN!") == null, "completed needs the exact line");
		check(KuudraPhase.fromMessage("Sending to server mini1A") == KuudraPhase.NONE && KuudraPhase.fromMessage("hello") == null, "leaving and ordinary chat");
		check(KuudraPhase.SUPPLIES.isInRun() && KuudraPhase.BOSS.isInRun() && !KuudraPhase.COMPLETED.isInRun() && !KuudraPhase.NONE.isInRun(), "which phases are in a run");
		check(KuudraPhase.STUN.isCombat() && !KuudraPhase.BUILD.isCombat(), "combat phases");
	}

	private static void checkTextRules() {
		check(Integer.valueOf(5).equals(KuudraRules.tierFrom("SKYBLOCK\nKuudra's Hollow (T5)\nLobby")) && KuudraRules.tierFrom("The Catacombs") == null
			&& KuudraRules.tierFrom("Kuudra's Hollow (T9)") == null, "tier is T1-T5 from the sidebar");
		check(Integer.valueOf(45).equals(KuudraRules.pilePercent("PROGRESS: 45%")) && KuudraRules.pilePercent("PROGRESS: 145%") == null
			&& Integer.valueOf(7).equals(KuudraRules.pilePercent("PROGRESS: §a7%")) && KuudraRules.pilePercent("Progress") == null, "unfinished pile name");
		int[] build = KuudraRules.buildStatus("Building Progress 67% (3 Players Helping)");
		check(build != null && build[0] == 67 && build[1] == 3 && KuudraRules.buildStatus("Building Progress soon") == null, "build progress and helpers");
		check("§a".equals(KuudraRules.healthColor(100_000)) && "§4".equals(KuudraRules.healthColor(10_000)), "health colours");
		check("§a100k/100k❤".equals(KuudraRules.healthText(100_000f, 4, true)), "default readout is thousands of 100k");
		check(KuudraRules.healthText(25_000f, 5, true).contains("240M") && !KuudraRules.healthText(25_000f, 5, false).contains("240M")
			&& !KuudraRules.healthText(30_000f, 5, true).contains("240M") && !KuudraRules.healthText(25_000f, 4, true).contains("240M"),
			"scaling needs the option, 25k or less, and tier 5");
		check(KuudraRules.shouldStun(3, 93, 93) && !KuudraRules.shouldStun(2, 100, 93) && !KuudraRules.shouldStun(5, 92, 93) && !KuudraRules.shouldStun(5, 100, 0),
			"stun needs tier 3+, the threshold reached, and a non-zero threshold");
		check("§a".equals(KuudraRules.buildersColor(3)) && "§e".equals(KuudraRules.buildersColor(2)) && "§c".equals(KuudraRules.buildersColor(1)), "builder colours");
		check(KuudraRules.buildProgressColor(20) == 0xFFA80000 && KuudraRules.buildProgressColor(21) == 0xFFFF0000 && KuudraRules.buildProgressColor(41) == 0xFFFF8700
			&& KuudraRules.buildProgressColor(61) == 0xFF2E8200 && KuudraRules.buildProgressColor(81) == 0xFF7DDA58, "pile colours step at 20, 40, 60 and 80 percent");
		check(KuudraRules.dangerLevel("minecraft:green_terracotta") == 0 && KuudraRules.dangerLevel("minecraft:lime_terracotta") == 0
			&& KuudraRules.dangerLevel("minecraft:yellow_terracotta") == 1 && KuudraRules.dangerLevel("minecraft:red_terracotta") == 1
			&& KuudraRules.dangerLevel("minecraft:stone") == -1, "danger zones are the green to red terracotta; yellow and up means jump");
	}

	private static void checkGeometry() {
		double[] crate = KuudraRules.crateFor(0.0, 0.0, 0.0f);
		check(Math.abs(crate[0] - 3.7 * Math.cos(Math.toRadians(130.0))) < 1.0e-9 && Math.abs(crate[2] - 3.7 * Math.sin(Math.toRadians(130.0))) < 1.0e-9
			&& crate[1] == 75.0, "a crate is 3.7 blocks from its giant along yaw plus 130 degrees, at y 75");
		check(KuudraRules.pileCompleted(-98.5, 78.4, -113.5, -98.0, 77.0, -113.0) && !KuudraRules.pileCompleted(-98.5, 78.4, -113.5, -90.0, 77.0, -113.0)
			&& !KuudraRules.pileCompleted(-98.5, 78.4, -113.5, -98.0, 90.0, -113.0), "a received-stand within 1.5 blocks and 5 in height completes a pile");
		check(KuudraRules.MAX_PILES == 16 && KuudraRules.MAX_GIANTS == 16, "tracked entity lists are bounded");
	}

	private static void checkBundledData() {
		List<KuudraData.Pile> piles = KuudraData.piles();
		check(piles.size() == 6 && "Shop".equals(piles.get(0).getName()) && piles.get(0).getNoPre() == 7, "six bundled piles, Shop first");
		List<KuudraData.PearlArea> areas = KuudraData.pearlAreas();
		check(areas.size() >= 4, "pearl areas are bundled");
		KuudraData.PearlArea first = areas.get(0);
		check(first.contains(-139.0, -150.0) && !first.contains(0.0, 0.0), "an area contains points between its corners, either order");
		check(!first.getSpots().isEmpty() && first.getSpots().get(0).getText().length() > 0, "areas carry timed pearl spots");
		List<KuudraData.Waypoint> safe = KuudraData.safeSpots();
		check(safe.size() >= 10 && safe.stream().allMatch(waypoint -> !waypoint.getPositions().isEmpty()) && safe.stream().anyMatch(waypoint -> waypoint.getShowIn().contains(KuudraPhase.SUPPLIES)),
			"safe spots are bundled and shown in the supplies phase");
		List<KuudraData.Waypoint> custom = KuudraData.customWaypoints();
		check(custom.stream().anyMatch(waypoint -> "Skip Spot".equals(waypoint.getName()) && waypoint.getShowIn().contains(KuudraPhase.DPS))
			&& custom.stream().anyMatch(waypoint -> waypoint.getPositions().size() == 3), "skip and multi-position DPS waypoints are bundled");
	}

	private static void checkParsers() {
		List<KuudraData.Waypoint> parsed = KuudraData.parseWaypoints("{\"categories\":[{\"name\":\"A\",\"enabled\":true,\"waypoints\":[{\"name\":\"w\",\"position\":[1,2,3],\"color\":\"#FF0000\",\"alpha\":0.5,\"text\":\"t\",\"showInPhases\":[\"STUN\",\"nonsense\"],\"hideInPhases\":[],\"maxRenderDistance\":-1}]},"
			+ "{\"name\":\"B\",\"enabled\":false,\"waypoints\":[{\"name\":\"off\",\"position\":[0,0,0]}]}]}");
		check(parsed.size() == 1 && parsed.get(0).getPositions().get(0)[1] == 2.0 && parsed.get(0).getShowIn().equals(java.util.Set.of(KuudraPhase.STUN)),
			"waypoint parsing keeps enabled categories, positions and known phases only");
		check((parsed.get(0).getArgb() & 0x00FFFFFF) == 0xFF0000 && (parsed.get(0).getArgb() >>> 24) == 128, "hex colour and alpha become ARGB");
		check(KuudraData.parseWaypoints("not json").isEmpty() && KuudraData.parsePiles("{}").isEmpty() && KuudraData.parsePearlAreas("[]").isEmpty(), "bad data yields nothing instead of throwing");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError("KuudraChecks: " + message);
	}
}
