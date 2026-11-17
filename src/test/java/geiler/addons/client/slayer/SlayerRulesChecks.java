package geiler.addons.client.slayer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline checks for the Slayer boss-family rules, the laser phase timer and the Yang Glyph tracker. */
public final class SlayerRulesChecks {
	private SlayerRulesChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		checkBossFamilies();
		checkOwnerLabel();
		checkMinibosses();
		checkBlazeText();
		checkVampireText();
		checkEndermanText();
		checkLaserTimer();
		checkGlyphTracker();
		System.out.println("Slayer rules checks passed");
	}

	private static void checkBossFamilies() {
		require(SlayerRules.bossFamily("☠ Voidgloom Seraph 12M❤") == SlayerRules.Family.VOIDGLOOM, "voidgloom label");
		require(SlayerRules.bossFamily("☠ Inferno Demonlord 2M❤") == SlayerRules.Family.DEMONLORD, "inferno label");
		require(SlayerRules.bossFamily("☠ Atoned Horror 500❤") == SlayerRules.Family.REVENANT, "atoned maps to revenant");
		require(SlayerRules.bossFamily("☠ Conjoined Brood 1M❤") == SlayerRules.Family.TARANTULA, "conjoined maps to tarantula");
		require(SlayerRules.bossFamily("☠ Sven Packmaster 2K❤") == SlayerRules.Family.SVEN, "sven label");
		require(SlayerRules.bossFamily("Bloodfiend 400❤") == SlayerRules.Family.VAMPIRE, "bare bloodfiend maps to vampire");
		require(SlayerRules.bossFamily("Voidling Devotee") == null, "a miniboss is not a boss label");
		require(SlayerRules.bossFamily(null) == null && SlayerRules.bossFamily("") == null, "null and empty are no boss");
		require(SlayerRules.isLiveBossLabel("☠ Voidgloom Seraph 12M❤"), "a label with health is live");
		require(!SlayerRules.isLiveBossLabel("☠ Voidgloom Seraph 0❤"), "a label at zero health is dead");
		require(!SlayerRules.isLiveBossLabel("Spawned by: Steve"), "an owner label is not a boss label");
	}

	private static void checkOwnerLabel() {
		require(SlayerRules.isSpawnedByLabel("Spawned by: Steve", "Steve"), "the owner label names the player");
		require(!SlayerRules.isSpawnedByLabel("Spawned by: Alex", "Steve"), "another owner is not mine");
		require(!SlayerRules.isSpawnedByLabel("Spawned by: Steve", ""), "an empty player name never matches");
		require(!SlayerRules.isSpawnedByLabel("Spawned by: Steve", null), "a null player name never matches");
		require(!SlayerRules.isSpawnedByLabel("☠ Voidgloom Seraph 12M❤ Steve", "Steve"),
			"a boss label is never an owner label");
	}

	private static void checkMinibosses() {
		require(SlayerRules.isMinibossName("Voidcrazed Maniac 100K❤", SlayerRules.Family.VOIDGLOOM), "enderman miniboss");
		require(!SlayerRules.isMinibossName("Voidcrazed Maniac 100K❤", SlayerRules.Family.DEMONLORD),
			"a miniboss belongs to its family only");
		require(SlayerRules.isMinibossName("Kindleheart Demon 1M❤", null), "no family accepts any family's miniboss");
		require(SlayerRules.isMinibossName("Primordial Jockey 9M❤", SlayerRules.Family.TARANTULA), "tarantula tier 5 miniboss");
		require(!SlayerRules.isMinibossName("Zealot", null), "a plain mob is no miniboss");
		require(!SlayerRules.isMinibossName("Bloodfiend 400❤", SlayerRules.Family.VAMPIRE), "vampire has no minibosses");
		require("Voidling Devotee".equals(SlayerRules.minibossName("§5Voidling Devotee 12M❤")),
			"the miniboss alert name drops the health that follows it");
		require("Pack Enforcer".equals(SlayerRules.minibossName("pack enforcer 300K❤")), "names are title-cased");
		require(SlayerRules.minibossName("Zealot") == null && SlayerRules.minibossName(null) == null, "no miniboss, no name");
	}

	private static void checkBlazeText() {
		require(SlayerRules.attunement("ASHEN ♨ 1M") == SlayerRules.Attunement.ASHEN, "ashen");
		require(SlayerRules.attunement("♨ SPIRIT") == SlayerRules.Attunement.SPIRIT, "spirit");
		require(SlayerRules.attunement("CRYSTAL") == SlayerRules.Attunement.CRYSTAL, "crystal");
		require(SlayerRules.attunement("AURIC") == SlayerRules.Attunement.AURIC, "auric");
		require(SlayerRules.attunement("IMMUNE") == SlayerRules.Attunement.IMMUNE, "immune");
		require(SlayerRules.Attunement.ASHEN.getArgb() == 0xFF000000 && SlayerRules.Attunement.AURIC.getArgb() == 0xFFFFFF55
			&& SlayerRules.Attunement.CRYSTAL.getArgb() == 0xFF5555FF && SlayerRules.Attunement.SPIRIT.getArgb() == 0xFFFFFFFF,
			"ashen is black, auric yellow, crystal blue and spirit white");
		require(SlayerRules.attunement("Ashen") == null, "attunement is matched in upper case only");
		require(SlayerRules.attunement(null) == null, "null text has no attunement");
		require(SlayerRules.firePillarSeconds("5s 3 hits") == 5, "pillar seconds are read");
		require(SlayerRules.firePillarSeconds("12s 10 hits") == 12, "two digit seconds are read");
		require(SlayerRules.firePillarSeconds("Fire Pillar 5s 3 hits") == -1, "the pillar label matches whole");
		require(SlayerRules.firePillarSeconds("5s") == -1 && SlayerRules.firePillarSeconds(null) == -1, "partial text is no pillar");
	}

	private static void checkVampireText() {
		require(SlayerRules.hasTwinclaws("TWINCLAWS 3s"), "twinclaws marker");
		require(!SlayerRules.hasTwinclaws("Twinclaws"), "twinclaws is matched in upper case only");
		require(SlayerRules.hasStakeMarker("Bloodfiend ҉ 400❤"), "stake marker glyph");
		require(!SlayerRules.hasStakeMarker("Bloodfiend 400❤"), "no glyph no stake");
		String ichor = skin("c0340923a6de4825a176813d133503eff186db0896e32b6704928c2a2bf68422");
		String spring = skin("77f7a7bc8ac86f23ca7bf98afeb76960227e1832fe209a3026f6ceb8bde74f54");
		require(SlayerRules.isBloodIchorTexture(ichor) && !SlayerRules.isBloodIchorTexture(spring),
			"the Blood Ichor skull is told apart from the Killer Spring skull");
		require(SlayerRules.isKillerSpringTexture(spring) && !SlayerRules.isKillerSpringTexture(ichor),
			"the Killer Spring skull is told apart from the Blood Ichor skull");
		require(!SlayerRules.isBloodIchorTexture(null) && !SlayerRules.isBloodIchorTexture("")
			&& !SlayerRules.isKillerSpringTexture("not base64!"), "empty or malformed textures match nothing");
	}

	private static String skin(String hash) {
		String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
		return java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static void checkEndermanText() {
		String nukekubi = "eyJ0aW1lc3RhbXAiOjE1MzQ5NjM0MzU5NjIsInByb2ZpbGVJZCI6ImQzNGFhMmI4MzFkYTRkMjY5NjU1ZTMzYzE0M2YwOTZjIiwicHJvZmlsZU5hbWUiOiJFbmRlckRyYWdvbiIsInNpZ25hdHVyZVJlcXVpcmVkIjp0cnVlLCJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZWIwNzU5NGUyZGYyNzM5MjFhNzdjMTAxZDBiZmRmYTExMTVhYmVkNWI5YjIwMjllYjQ5NmNlYmE5YmRiYjRiMyJ9fX0=";
		require(SlayerRules.isNukekubiTexture(nukekubi), "the known Nukekubi skull texture matches");
		require(!SlayerRules.isNukekubiTexture(nukekubi + "x") && !SlayerRules.isNukekubiTexture(null)
			&& !SlayerRules.isNukekubiTexture(""), "any other texture does not");
		require(SlayerRules.isPupLabel("Sven Pup 500❤") && SlayerRules.isPupLabel("[Lv1] sven pup"), "pup labels");
		require(!SlayerRules.isPupLabel("Sven Packmaster 2K❤"), "the boss is not a pup");
	}

	private static void checkLaserTimer() {
		LaserPhaseTimer timer = new LaserPhaseTimer();
		require(timer.remainingTicks(0) == 0 && !timer.active(0), "idle at start");
		timer.update(false, 10);
		require(!timer.active(10), "an unmounted boss starts nothing");
		timer.update(true, 20);
		require(timer.remainingTicks(20) == LaserPhaseTimer.DURATION_TICKS, "rising edge starts the full duration");
		require(timer.remainingTicks(80) == LaserPhaseTimer.DURATION_TICKS - 60, "time counts down");
		timer.update(true, 90);
		require(timer.remainingTicks(90) == LaserPhaseTimer.DURATION_TICKS - 70, "staying mounted does not restart");
		require(timer.remainingTicks(20 + LaserPhaseTimer.DURATION_TICKS) == 0, "the timer ends");
		timer.update(false, 100);
		timer.update(true, 120);
		require(timer.remainingTicks(120) == 60, "a second edge inside the cooldown does not restart it");
		timer.update(false, 150);
		timer.update(true, 20 + LaserPhaseTimer.COOLDOWN_TICKS);
		require(timer.remainingTicks(20 + LaserPhaseTimer.COOLDOWN_TICKS) == LaserPhaseTimer.DURATION_TICKS,
			"a new edge after the cooldown restarts it");
		timer.reset();
		require(!timer.active(0), "reset clears it");
	}

	private static void checkGlyphTracker() {
		Set<String> beacons = new HashSet<>();
		YangGlyphTracker tracker = new YangGlyphTracker((x, y, z) -> beacons.contains(x + "," + y + "," + z));

		int fresh = tracker.update(Map.of(7, new double[] {10.5, 70.0, 20.5}), 0);
		require(fresh == 1, "a new beacon stand is reported once");
		fresh = tracker.update(Map.of(7, new double[] {11.5, 69.0, 20.5}), 1);
		require(fresh == 0, "the same stand is not new again");
		require(tracker.beacons(1).isEmpty(), "no landed beacon yet");

		beacons.add("11,68,20");
		tracker.update(Map.of(), 2);
		List<YangGlyphTracker.Beacon> landed = tracker.beacons(2);
		require(landed.size() == 1 && landed.get(0).getX() == 11 && landed.get(0).getY() == 68
			&& landed.get(0).getZ() == 20, "the landed beacon is found near the stand's last position");
		require(tracker.beacons(2 + YangGlyphTracker.BEACON_TICKS - 1).size() == 1, "it lasts the beacon duration");
		require(tracker.beacons(2 + YangGlyphTracker.BEACON_TICKS).isEmpty(), "it expires after the duration");

		beacons.clear();
		tracker.reset();
		tracker.update(Map.of(9, new double[] {0.5, 70.0, 0.5}), 10);
		tracker.update(Map.of(), 11);
		require(tracker.pendingCount() == 1, "a vanished stand waits for its beacon");
		tracker.update(Map.of(), 11 + YangGlyphTracker.LANDING_WINDOW_TICKS + 1);
		require(tracker.pendingCount() == 0 && tracker.beacons(100).isEmpty(), "no beacon in time drops the pending stand");

		beacons.add("30,70,30");
		tracker.reset();
		tracker.update(Map.of(3, new double[] {30.5, 70.5, 30.5}), 200);
		tracker.update(Map.of(), 201);
		require(tracker.beacons(201).size() == 1, "found in the first scan after the stand vanishes");
		beacons.clear();
		tracker.update(Map.of(), 202);
		require(tracker.beacons(202).isEmpty(), "a beacon whose block is gone is dropped");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
