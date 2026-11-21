package geiler.addons.client.slayer;

/** Offline checks for the Slayer HUD's label reading and fight timing. */
public final class SlayerHudChecks {
	private SlayerHudChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		checkHealth();
		checkFight();
		checkPhases();
		checkBossPhases();
		System.out.println("Slayer HUD checks passed");
	}

	private static SlayerBossPhases.Trigger trigger(SlayerBossPhases.Tier tier, String name) {
		for (SlayerBossPhases.Trigger trigger : tier.getTriggers()) if (trigger.getName().equals(name)) return trigger;
		throw new AssertionError("tier " + tier.getNumber() + " has no trigger " + name);
	}

	private static void checkBossPhases() {
		SlayerBossPhases.Tier voidT4 = SlayerBossPhases.resolveTier(SlayerRules.Family.VOIDGLOOM, 209_000_000L);
		require(voidT4 != null && voidT4.getNumber() == 4 && voidT4.getMaxHealth() == 210_000_000L, "a first read just under 210M is Voidgloom IV");
		SlayerBossPhases.Tier voidT2 = SlayerBossPhases.resolveTier(SlayerRules.Family.VOIDGLOOM, 11_900_000L);
		require(voidT2 != null && voidT2.getNumber() == 2, "a first read just under 12M is Voidgloom II");
		require(SlayerBossPhases.resolveTier(SlayerRules.Family.VOIDGLOOM, 999_000_000L) == null, "a read above every tier resolves no tier");
		require(SlayerBossPhases.threshold(voidT4, trigger(voidT4, "Nukekubi Heads")) == 70_000_000L,
			"Voidgloom IV spawns its heads at 70M");
		require(SlayerBossPhases.threshold(voidT4, trigger(voidT4, "Radiation")) > 0, "Radiation has a threshold");
		require(SlayerBossPhases.resolveTier(SlayerRules.Family.TARANTULA, 20_000_000L).getNumber() == 6, "20M is the Conjoined Brood");
		require(SlayerBossPhases.resolveTier(SlayerRules.Family.REVENANT, 1_400_000L).getTriggers().isEmpty(), "Revenant IV has no HP phase");
		require(SlayerBossPhases.resolveTier(SlayerRules.Family.REVENANT, 9_500_000L).getTriggers().size() == 1, "Atoned Horror has one");
		SlayerBossPhases.Tier vampire = SlayerBossPhases.resolveTier(SlayerRules.Family.VAMPIRE, 1_700L);
		require(vampire.getNumber() == 3 && SlayerBossPhases.threshold(vampire, trigger(vampire, "Steak Stake")) == 360L,
			"Bloodfiend III is stakeable at 360 health");
		SlayerBossPhases.Tier demon = SlayerBossPhases.resolveTier(SlayerRules.Family.DEMONLORD, 149_000_000L);
		require(demon.getNumber() == 4 && SlayerBossPhases.threshold(demon, trigger(demon, "Fire Pillars")) == 100_000_000L,
			"Demonlord IV starts fire pillars at 100M");
		require(SlayerBossPhases.resolveTier(SlayerRules.Family.SVEN, 700_000L).getTriggers().size() == 1
				&& SlayerBossPhases.resolveTier(SlayerRules.Family.SVEN, 30_000L).getTriggers().isEmpty(),
			"Sven calls pups from tier III only");

		require(SlayerBossPhases.notches(voidT4) == SlayerBossPhases.Notches.SIX, "sixth-step triggers use the six-notch bar");
		require(SlayerBossPhases.notches(vampire) == SlayerBossPhases.Notches.TWENTY, "Bloodfiend III needs twenty notches");
		require(SlayerBossPhases.notches(SlayerBossPhases.resolveTier(SlayerRules.Family.REVENANT, 1_400_000L)) == SlayerBossPhases.Notches.NONE,
			"no triggers means a smooth bar");

		SlayerPhaseProgress progress = new SlayerPhaseProgress(voidT4);
		require(progress.update(210_000_000L).isEmpty(), "a full-health boss has passed nothing");
		java.util.List<SlayerPhaseProgress.Row> rows = progress.rows(210_000_000L);
		require(rows.get(0).getStatus() == SlayerPhaseProgress.Status.NEXT && rows.get(0).getHealthToGo() == 35_000_000L
				&& rows.get(1).getStatus() == SlayerPhaseProgress.Status.UPCOMING, "the first trigger is next, the rest upcoming");
		require(progress.update(139_000_000L).size() == 3, "dropping to 139M passes Radiation, the shield and glyphs");
		require(progress.update(200_000_000L).isEmpty(), "regeneration passes nothing new");
		rows = progress.rows(200_000_000L);
		require(rows.get(0).getStatus() == SlayerPhaseProgress.Status.PASSED && rows.get(3).getStatus() == SlayerPhaseProgress.Status.NEXT
				&& rows.get(3).getHealthToGo() == 95_000_000L, "passed triggers stay passed after regeneration");

		require("33M / 50M · 66%".equals(SlayerBossPhases.title(33_000_000L, 50_000_000L)), "the bar title shows health and percent");
		require("0 / 500 · 0%".equals(SlayerBossPhases.title(0L, 500L)) && "500 / 500 · 100%".equals(SlayerBossPhases.title(900L, 500L)),
			"the title clamps to 0 to 100 percent");
		require("> Nukekubi Heads 33% · 16.7M".equals(SlayerBossPhases.rowText(new SlayerPhaseProgress.Row(
			"Nukekubi Heads", 1.0 / 3, SlayerPhaseProgress.Status.NEXT, 16_700_000L))), "the next row shows the health left to it");
		require("x Hitshield 67%".equals(SlayerBossPhases.rowText(new SlayerPhaseProgress.Row(
			"Hitshield", 2.0 / 3, SlayerPhaseProgress.Status.PASSED, 0L))), "a passed row has no health left");
	}

	private static void checkPhases() {
		java.util.List<String> ashen = java.util.List.of("Inferno Demonlord 5M❤", "ASHEN 4 hits");
		require("Ashen".equals(SlayerPhaseRules.phase(SlayerRules.Family.DEMONLORD, ashen)), "a Blaze label names its attunement");
		require(SlayerPhaseRules.phase(SlayerRules.Family.DEMONLORD, java.util.List.of("Inferno Demonlord 5M❤")) == null,
			"a Blaze fight with no attunement text names no phase");
		require("Stake".equals(SlayerPhaseRules.phase(SlayerRules.Family.VAMPIRE, java.util.List.of("Bloodfiend 1M❤ ҉")))
				&& "Twinclaws".equals(SlayerPhaseRules.phase(SlayerRules.Family.VAMPIRE, java.util.List.of("TWINCLAWS 3s")))
				&& "Normal".equals(SlayerPhaseRules.phase(SlayerRules.Family.VAMPIRE, java.util.List.of("Bloodfiend 1M❤"))),
			"a Bloodfiend is in Stake, Twinclaws or Normal");
		require(SlayerPhaseRules.phase(SlayerRules.Family.VOIDGLOOM, ashen) == null && SlayerPhaseRules.phase(null, ashen) == null,
			"slayers without a verified phase signal name none");

		SlayerPhaseTimes times = new SlayerPhaseTimes();
		require(times.rows(0L).isEmpty(), "no phase yet means no rows");
		times.update("Ashen", 1_000L);
		times.update(null, 5_000L);
		require(times.rows(11_000L).size() == 1 && times.rows(11_000L).get(0).getSeconds() == 10.0f, "an unnamed tick keeps the phase running");
		times.update("Spirit", 11_000L);
		times.update("Ashen", 16_000L);
		java.util.List<SlayerPhaseTimes.Row> rows = times.rows(18_000L);
		require(rows.size() == 2 && "Ashen".equals(rows.get(0).getName()) && rows.get(0).getSeconds() == 12.0f
				&& "Spirit".equals(rows.get(1).getName()) && rows.get(1).getSeconds() == 5.0f,
			"a phase revisited adds to its total and keeps first-seen order");
		times.clear();
		require(times.rows(20_000L).isEmpty(), "clear forgets every phase");
		for (int i = 0; i < SlayerPhaseTimes.MAX_PHASES + 5; i++) times.update("P" + i, i * 1_000L);
		require(times.rows(99_000L).size() == SlayerPhaseTimes.MAX_PHASES, "the phase list is bounded");
		require("Ashen 12.3s, Spirit 1m 05s".equals(SlayerPhaseTimes.summary(java.util.List.of(
			new SlayerPhaseTimes.Row("Ashen", 12.34f), new SlayerPhaseTimes.Row("Spirit", 65.4f)))), "the summary joins name and time");
	}

	private static void checkHealth() {
		require(SlayerHudText.parseHealth("☠ Voidgloom Seraph 12M❤") == 12_000_000L, "M suffix");
		require(SlayerHudText.parseHealth("Revenant Horror 3.5M❤") == 3_500_000L, "decimal M");
		require(SlayerHudText.parseHealth("Atoned Horror 850k❤") == 850_000L, "k suffix");
		require(SlayerHudText.parseHealth("Tarantula Broodfather 1,250❤") == 1_250L, "comma grouping");
		require(SlayerHudText.parseHealth("Inferno Demonlord 1.2B❤") == 1_200_000_000L, "B suffix");
		require(SlayerHudText.parseHealth("Revenant Horror 0❤") == 0L, "dead label reads zero");
		require(SlayerHudText.parseHealth("Revenant Horror") == null, "no health token");
		require(SlayerHudText.parseHealth(null) == null, "null label");
		require(SlayerHudText.parseHealth("A 5M❤ B 3M❤") == 3_000_000L, "the last token wins");

		require("12M".equals(SlayerHudText.formatHealth(12_000_000L)), "whole millions");
		require("3.5M".equals(SlayerHudText.formatHealth(3_500_000L)), "one decimal");
		require("850k".equals(SlayerHudText.formatHealth(850_000L)), "thousands");
		require("999".equals(SlayerHudText.formatHealth(999L)), "small numbers print plain");
		require("1.2B".equals(SlayerHudText.formatHealth(1_200_000_000L)), "billions");

		require("Voidgloom Seraph".equals(SlayerHudText.bossName("☠ Voidgloom Seraph 12M❤")), "name drops the skull and health");
		require("Revenant Horror".equals(SlayerHudText.bossName("Revenant Horror 3.5M❤")), "name without a skull");
		require("12.3s".equals(SlayerHudText.duration(12.34f)), "under a minute prints seconds");
		require("1m 05s".equals(SlayerHudText.duration(65.4f)), "a minute or more prints m ss");
	}

	private static void checkFight() {
		SlayerFight fight = new SlayerFight();
		require(fight.update(false, false, false, null, 0L) == null && !fight.inBoss(), "idle stays idle");

		fight.update(true, false, false, null, 1_000L);
		require(!fight.inBoss(), "a quest alone is not a boss fight");

		fight.update(false, true, false, 5_000_000L, 11_000L);
		require(fight.inBoss() && fight.getHealth() == 5_000_000L && fight.getMaxHealth() == 5_000_000L, "the boss starts the fight");
		fight.update(false, true, false, 3_000_000L, 21_000L);
		require(fight.getHealth() == 3_000_000L && fight.getMaxHealth() == 5_000_000L, "max health is the highest seen");
		fight.update(false, true, false, null, 22_000L);
		require(fight.getHealth() == 3_000_000L, "a missing label keeps the last health");
		require(fight.fightSeconds(31_000L) == 20.0f, "the fight clock runs from the boss spawn");

		SlayerFight.Result result = fight.update(false, false, true, 0L, 41_000L);
		require(result != null && result.getSpawnSeconds() != null && result.getSpawnSeconds() == 10.0f
			&& result.getKillSeconds() == 30.0f, "boss slain reports spawn and kill time");
		require(!fight.inBoss(), "the fight is over after the kill");
		require(fight.update(false, false, true, null, 42_000L) == null, "slain is reported once");

		SlayerFight direct = new SlayerFight();
		direct.update(false, true, false, 1_000L, 5_000L);
		SlayerFight.Result noSpawn = direct.update(false, false, true, null, 9_000L);
		require(noSpawn != null && noSpawn.getSpawnSeconds() == null && noSpawn.getKillSeconds() == 4.0f,
			"a fight first seen mid-boss has no spawn time");

		SlayerFight failed = new SlayerFight();
		failed.update(true, false, false, null, 0L);
		failed.update(false, true, false, 1_000L, 1_000L);
		require(failed.update(false, false, false, null, 2_000L) == null && !failed.inBoss(), "a boss that vanishes without slain ends quietly");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
