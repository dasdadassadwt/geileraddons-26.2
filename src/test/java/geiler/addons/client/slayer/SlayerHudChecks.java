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
		System.out.println("Slayer HUD checks passed");
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
