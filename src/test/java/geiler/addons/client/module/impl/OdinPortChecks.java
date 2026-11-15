package geiler.addons.client.module.impl;

/** Offline checks for the Odin-derived Gyro Wand, Mimic, Terracotta Timer, Spring Boots and Ragnarock rules. */
public final class OdinPortChecks {
	private OdinPortChecks() { }

	public static void run() {
		checkGyroWand();
		checkMimic();
		checkTerracotta();
		checkSpringBoots();
		checkRagnarock();
	}

	private static void checkGyroWand() {
		GyroWandRules.BurstDetector burst = new GyroWandRules.BurstDetector();
		boolean fired = false;
		for (int i = 0; i < GyroWandRules.BURST_COUNT - 1; i++) fired |= burst.onParticle(1000L + i, 64.0);
		check(!fired && burst.onParticle(1010L, 64.0), "five particles at one height inside the window are one cast");
		check(!burst.onParticle(1020L, 64.0), "the burst counter restarts after a cast");

		GyroWandRules.BurstDetector slow = new GyroWandRules.BurstDetector();
		boolean slowFired = false;
		for (int i = 0; i < 10; i++) slowFired |= slow.onParticle(1000L + i * 200L, 64.0);
		check(!slowFired, "particles spread wider than the window never count as a cast");
		GyroWandRules.BurstDetector scattered = new GyroWandRules.BurstDetector();
		boolean scatteredFired = false;
		for (int i = 0; i < 10; i++) scatteredFired |= scattered.onParticle(1000L + i, 64.0 + i * 0.5);
		check(!scatteredFired, "particles at different heights never count as a cast");

		check(GyroWandRules.isStillBurstParticle(true, 0.0, 0.0, 0.0) && !GyroWandRules.isStillBurstParticle(false, 0.0, 0.0, 0.0)
			&& !GyroWandRules.isStillBurstParticle(true, 0.0, 0.1, 0.0), "only a still witch particle belongs to the burst");
		check(GyroWandRules.cooldownMs(1.0) == 30_000L && GyroWandRules.cooldownMs(0.31) == 9_300L, "the cooldown follows the Mage multiplier");
		check(GyroWandRules.eyeHeight(true, true) == 0.4 && GyroWandRules.eyeHeight(false, true) == 1.27
			&& GyroWandRules.eyeHeight(false, false) == 1.62, "the aim ray starts at the pose's eye height");

		int[] hit = GyroWandRules.firstNonAir(0.5, 64.5, 0.5, 10.5, 64.5, 0.5,
			(x, y, z) -> x == 3 && y == 64 && z == 0 ? GyroWandRules.SOLID : GyroWandRules.AIR);
		check(hit != null && hit[0] == 3 && hit[1] == 64 && hit[2] == 0, "the voxel trace stops at the first non-air block");
		check(GyroWandRules.firstNonAir(0.5, 64.5, 0.5, 10.5, 64.5, 0.5, (x, y, z) -> GyroWandRules.AIR) == null,
			"a ray that ends in air has no target");
		check(GyroWandRules.firstNonAir(0.5, 64.5, 0.5, 10.5, 64.5, 0.5, (x, y, z) -> x == 2 ? GyroWandRules.UNLOADED : GyroWandRules.AIR) == null,
			"a ray that reaches an unloaded chunk has no target");
		int[] diagonal = GyroWandRules.firstNonAir(0.5, 70.5, 0.5, 8.5, 60.5, 8.5,
			(x, y, z) -> y <= 65 ? GyroWandRules.SOLID : GyroWandRules.AIR);
		check(diagonal != null && diagonal[1] == 65, "a downward ray hits the ground at its first solid height");

		GyroWandModule module = GyroWandModule.INSTANCE;
		check("Gyro Wand".equals(module.name()) && !module.defaultEnabled() && module.booleanSettings().size() == 3
			&& module.colorSettings().size() == 2, "Gyro Wand has Show Cooldown, Depth Check and Fill switches and two colors");
		check(module.colorSettings().get(0).argb() == 0x80AA00AA, "the range color defaults to 50% dark purple");
		check(module.colorSettings().get(1).argb() == 0x80FF5555, "the cooldown color defaults to 50% red");
		check(module.booleanSettings().get(0).value() && module.booleanSettings().get(1).value(), "Show Cooldown and Depth Check default on");
		check("Fill".equals(module.booleanSettings().get(2).name()) && module.booleanSettings().get(2).value(), "Fill is the third switch and defaults on");
	}

	private static void checkMimic() {
		check(MimicRules.isMimicFloor(6) && MimicRules.isMimicFloor(7) && !MimicRules.isMimicFloor(5) && !MimicRules.isMimicFloor(null),
			"the Mimic is announced on floors 6 and 7 only");
		check(MimicRules.isMimicDeath(3, true, true) && !MimicRules.isMimicDeath(2, true, true)
			&& !MimicRules.isMimicDeath(3, false, true) && !MimicRules.isMimicDeath(3, true, false),
			"the Mimic is a baby zombie dying with event 3");
		check(MimicRules.isPrinceLine("A Prince falls. +1 Bonus Score") && !MimicRules.isPrinceLine("A Prince falls. +1 Bonus Score!")
			&& !MimicRules.isPrinceLine("Prince"), "the Prince line matches exactly");
		check(MimicRules.isBatLine("A Bat has been slain. +1 Bonus Score") && !MimicRules.isBatLine("A Bat has been slain.")
			&& !MimicRules.isBatLine("A Prince falls. +1 Bonus Score"), "the Bat line matches exactly");
		MimicAnnouncerModule module = MimicAnnouncerModule.INSTANCE;
		check("Mimic Announcer".equals(module.name()) && !module.defaultEnabled() && module.booleanSettings().size() == 3
			&& module.booleanSettings().stream().allMatch(setting -> setting.value()), "Mimic Announcer has three switches, all on");
	}

	private static void checkTerracotta() {
		check(TerracottaTimerRules.totalSeconds(false) == 15f && TerracottaTimerRules.totalSeconds(true) == 12f, "Master shortens the respawn to 12 s");
		check(Math.abs(TerracottaTimerRules.remainingSeconds(15f, 20L) - 14f) < 1e-4
			&& TerracottaTimerRules.remainingSeconds(15f, 300L) <= 0.0001f, "each server tick takes 0.05 s off the timer");
		check(TerracottaTimerRules.color(6f) == 0xFF55FF55 && TerracottaTimerRules.color(5f) == 0xFFFFAA00
			&& TerracottaTimerRules.color(2.5f) == 0xFFFFAA00 && TerracottaTimerRules.color(2f) == 0xFFFF5555,
			"the countdown is green above 5 s, gold above 2 s and red below");
		check("12.3S".equals(TerracottaTimerRules.label(12.34f)) && "0.0S".equals(TerracottaTimerRules.label(-1f)),
			"the label has one decimal and the S glyph");
		check(!TerracottaTimerModule.INSTANCE.defaultEnabled() && !TerracottaTimerModule.INSTANCE.hasSettings(),
			"Terracotta Timer is off by default and has no settings");
		check(TerracottaTimerRules.HUD_SCALE == 1.0f,
			"the Terracotta label is projected HUD text at the real font scale, like Tiki labels, not vector strokes");
	}

	private static void checkSpringBoots() {
		check(SpringBootsRules.tableSize() == 45, "the height table has 45 entries");
		SpringBootsRules.State state = new SpringBootsRules.State();
		state.onPling(SpringBootsRules.LOW_PITCH);
		state.onPling(SpringBootsRules.LOW_PITCH);
		state.onPling(SpringBootsRules.LOW_PITCH);
		check(state.getLow() == 2 && state.getBlocks() == 6.5f, "low notes are capped at two");
		state.onPling(0.82539684f);
		check(state.getHigh() == 1 && state.getBlocks() == 9.0f, "a high note raises the height by one table step");
		state.onPling(0.5f);
		check(state.getHigh() == 1, "an unknown pitch changes nothing");
		state.onLaunch(1.0f);
		check(state.getBlocks() == 9.0f, "a firework sound with another pitch does not reset");
		state.onLaunch(0.0952381f);
		check(state.getHigh() == 0 && state.getLow() == 0 && state.getBlocks() == 0f, "the launch pitch resets the charge");
		for (int i = 0; i < 80; i++) state.onPling(1.7777778f);
		check(state.getBlocks() == 61.0f, "the height saturates at the end of the table");
		state.reset();
		check(state.getBlocks() == 0f, "reset clears the height");
		check(SpringBootsRules.color(13.5f) == 0xFFFF5555 && SpringBootsRules.color(22.5f) == 0xFFFFFF55
			&& SpringBootsRules.color(33.0f) == 0xFFFFAA00 && SpringBootsRules.color(43.5f) == 0xFF55FF55
			&& SpringBootsRules.color(44.0f) == 0xFF55FFFF, "the height colors follow the thresholds");
		check(!SpringBootsModule.INSTANCE.defaultEnabled() && "spring_boots".equals(SpringBootsModule.INSTANCE.id()),
			"Spring Boots is a HUD module that is off by default");
	}

	private static void checkRagnarock() {
		check(RagnarockRules.isCancelLine("Ragnarock was cancelled due to being hit!")
			&& RagnarockRules.isCancelLine("Ragnarock was cancelled due to taking damage!")
			&& !RagnarockRules.isCancelLine("Ragnarock was cancelled!"), "both cancel lines are recognised");
		check(RagnarockRules.loreStrength(java.util.List.of("Damage: +200", "Strength: +150 (+30)")) == 150
			&& RagnarockRules.loreStrength(java.util.List.of("Damage: +200")) == 0, "the axe's strength is read from its lore");
		check(RagnarockRules.strengthGained(150) == 225 && RagnarockRules.strengthGained(101) == 151, "the gain is 1.5 times the strength, truncated");
		check(RagnarockRules.isCast(1.4920635f, true, true) && !RagnarockRules.isCast(1.4920635f, false, true)
			&& !RagnarockRules.isCast(1.4920635f, true, false) && !RagnarockRules.isCast(1.0f, true, true),
			"a cast needs the exact pitch, the axe in hand and a wolf death sound");
		RagnarockModule module = RagnarockModule.INSTANCE;
		check(!module.defaultEnabled() && module.booleanSettings().size() == 4 && module.booleanSettings().get(0).value()
			&& module.booleanSettings().get(1).value() && module.booleanSettings().get(2).value() && !module.booleanSettings().get(3).value(),
			"Ragnarock defaults: cast, cancel and strength on; announce off");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
