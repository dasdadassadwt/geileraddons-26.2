package geiler.addons.client.dungeon;

import java.util.List;

/** Offline checks for the Dungeon Map Info panel text, colours, formulas and layout (Odin Full Hud rules). */
public final class DungeonMapInfoChecks {
	private DungeonMapInfoChecks() { }

	public static void run() {
		checkFormulas();
		checkColours();
		checkText();
		checkLayout();
	}

	private static DungeonMapInfoLogic.Info info(int found, double percent, int deaths, int crypts, boolean mimic,
		boolean prince, int bats, boolean paul, int floor, double required, Integer score, int known) {
		return new DungeonMapInfoLogic.Info(found, percent, deaths, crypts, mimic, prince, bats, paul, floor, required,
			score, known);
	}

	private static void checkFormulas() {
		check(DungeonMapInfoLogic.totalSecrets(0, 50.0) == 0, "no found secrets means a total of zero");
		check(DungeonMapInfoLogic.totalSecrets(10, 0.0) == 0, "a zero percentage means a total of zero");
		check(DungeonMapInfoLogic.totalSecrets(25, 50.0) == 50, "25 found at 50 percent is 50 total");
		check(DungeonMapInfoLogic.totalSecrets(7, 14.0) == 50, "7 found at 14 percent rounds to 50 total");
		check(DungeonMapInfoLogic.bonusScore(9, true, true, 2, true) == 5 + 2 + 1 + 2 + 10,
			"bonus caps crypts at 5 and adds mimic 2, prince 1, one per bat killer and Paul 10");
		check(DungeonMapInfoLogic.bonusScore(2, false, false, 0, false) == 2, "bonus is just the crypts when nothing else is known");
		check(DungeonMapInfoLogic.neededSecrets(50, 1.0, 0, 0) == 50,
			"no bonus and no deaths at full requirement needs every secret");
		check(DungeonMapInfoLogic.neededSecrets(50, 1.0, 0, 1) == 52, "one death costs one point: ceil(50 * 41 / 40)");
		check(DungeonMapInfoLogic.neededSecrets(60, 0.85, 7, 0) == 43, "F6 style requirement with 7 bonus: ceil(60*0.85*33/40)");
		check(DungeonMapInfoLogic.neededSecrets(60, 0.0, 0, 0) == 0, "an unknown floor needs nothing");
	}

	private static void checkColours() {
		check("§c269".equals(DungeonMapInfoLogic.colorScore(269)) && "§e270".equals(DungeonMapInfoLogic.colorScore(270))
			&& "§e299".equals(DungeonMapInfoLogic.colorScore(299)) && "§a300".equals(DungeonMapInfoLogic.colorScore(300)),
			"score is red below 270, yellow below 300, green from 300");
		check("§c2".equals(DungeonMapInfoLogic.colorCrypts(2)) && "§e3".equals(DungeonMapInfoLogic.colorCrypts(3))
			&& "§e4".equals(DungeonMapInfoLogic.colorCrypts(4)) && "§a5".equals(DungeonMapInfoLogic.colorCrypts(5)),
			"crypts are red below 3, yellow below 5, green at 5");
		check("§a0".equals(DungeonMapInfoLogic.colorDeaths(0, 7)), "zero deaths are green");
		check("§e2".equals(DungeonMapInfoLogic.colorDeaths(2, 5)) && "§c3".equals(DungeonMapInfoLogic.colorDeaths(3, 5))
			&& "§44".equals(DungeonMapInfoLogic.colorDeaths(4, 5)), "F1-F5: yellow up to 2, red at 3, dark red above");
		check("§e3".equals(DungeonMapInfoLogic.colorDeaths(3, 6)) && "§c4".equals(DungeonMapInfoLogic.colorDeaths(4, 7))
			&& "§45".equals(DungeonMapInfoLogic.colorDeaths(5, 7)), "F6+: yellow up to 3, red at 4, dark red above");
	}

	private static void checkText() {
		DungeonMapInfoLogic.Options plain = new DungeonMapInfoLogic.Options(false, false, false);
		DungeonMapInfoLogic.Info base = info(20, 40.0, 1, 3, true, false, 0, false, 7, 1.0, 295, 40);
		check(base.getTotalSecrets() == 50 && base.getNeededSecrets() == 45, "total and needed derive from the tab values");
		check("§7Secrets: §b20§7-§e25§7-§c50".equals(DungeonMapInfoLogic.secretText(base, plain)),
			"minimum mode shows needed minus found");
		check("§7Secrets: §b20§7-§e45§7-§c50".equals(DungeonMapInfoLogic.secretText(base,
			new DungeonMapInfoLogic.Options(false, true, false))), "remaining mode shows the full needed amount");
		check("§7Secrets: §b20§7-§d25§7-§e45§7-§c50".equals(DungeonMapInfoLogic.secretText(base,
			new DungeonMapInfoLogic.Options(true, false, false))), "include remaining adds the remaining column and the needed amount");
		check("§7Score: §e295".equals(DungeonMapInfoLogic.scoreText(base)), "score text uses the score colour");
		check("§7Score: §8?".equals(DungeonMapInfoLogic.scoreText(info(0, 0, 0, 0, false, false, 0, false, 7, 1.0, null, 0))),
			"an unavailable score shows a question mark");
		check("§7Deaths: §c§e1".equals(DungeonMapInfoLogic.deathsText(base, plain)), "deaths text keeps Odin's red prefix before the coloured count");
		check("§7Unfound: §e10".equals(DungeonMapInfoLogic.deathsText(base, new DungeonMapInfoLogic.Options(false, false, true))),
			"unfound is total minus known secrets");
		check("§7Unfound: §e0".equals(DungeonMapInfoLogic.deathsText(info(20, 40.0, 0, 0, false, false, 0, false, 7, 1.0, 0, 90),
			new DungeonMapInfoLogic.Options(false, false, true))), "unfound never goes below zero");
		check("§aM §8| §cP §8| §cB".equals(DungeonMapInfoLogic.mimicText(base)), "floor 7 shows M, P and B with their colours");
		check("§cP §8| §aB".equals(DungeonMapInfoLogic.mimicText(info(0, 0, 0, 0, false, false, 1, false, 5, 0.7, 0, 0))),
			"floors without a Mimic hide the M marker");
		check("§7Crypts: §a5".equals(DungeonMapInfoLogic.cryptsText(info(0, 0, 0, 9, false, false, 0, false, 7, 1.0, 0, 0))),
			"crypts are capped at 5 for display");
	}

	private static int width(String text) {
		return text.replaceAll("§.", "").length() * 6;
	}

	private static void checkLayout() {
		DungeonMapInfoLogic.Info base = info(20, 40.0, 1, 3, true, false, 0, false, 7, 1.0, 295, 40);
		List<DungeonMapInfoLogic.Line> lines = DungeonMapInfoLogic.layout(150, base,
			new DungeonMapInfoLogic.Options(false, false, false), DungeonMapInfoChecks::width);
		check(lines.size() == 5, "the panel draws five strings");
		check(lines.get(0).getX() == 1 && lines.get(0).getY() == 0, "secrets sit at x=1 on the first row");
		check(lines.get(1).getY() == 0 && lines.get(1).getX() == 150 - 1 - width(lines.get(1).getText()),
			"score is right aligned one unit from the edge on the first row");
		check(lines.get(2).getX() == 1 && lines.get(2).getY() == 10, "deaths sit at x=1 on the second row");
		int cryptsX = 150 - 1 - width(lines.get(4).getText());
		check(lines.get(4).getX() == cryptsX && lines.get(4).getY() == 10, "crypts are right aligned on the second row");
		int gapStart = 1 + width(lines.get(2).getText());
		int expected = gapStart + (cryptsX - gapStart - width(lines.get(3).getText())) / 2;
		check(lines.get(3).getX() == expected && lines.get(3).getY() == 10,
			"the marker text is centred between the deaths and crypts texts");
		check(DungeonMapInfoLogic.PANEL_HEIGHT == 19, "the panel is 19 units tall");
		DungeonMapInfoLogic.Options plain = new DungeonMapInfoLogic.Options(false, false, false);
		check(DungeonMapInfoLogic.fitScale(400, base, plain, DungeonMapInfoChecks::width) == 1f,
			"a wide panel keeps full size text");
		float narrow = DungeonMapInfoLogic.fitScale(120, base, plain, DungeonMapInfoChecks::width);
		check(narrow < 1f && narrow >= DungeonMapInfoLogic.MIN_FIT_SCALE, "a narrow panel shrinks the text");
		int scaledWidth = (int) (120 / narrow);
		List<DungeonMapInfoLogic.Line> fitted = DungeonMapInfoLogic.layout(scaledWidth, base, plain, DungeonMapInfoChecks::width);
		check(fitted.get(0).getX() + width(fitted.get(0).getText()) < fitted.get(1).getX(),
			"shrunk secrets and score no longer overlap");
		check(fitted.get(2).getX() + width(fitted.get(2).getText()) <= fitted.get(3).getX()
			&& fitted.get(3).getX() + width(fitted.get(3).getText()) <= fitted.get(4).getX(),
			"shrunk second row texts no longer overlap");
		check(DungeonMapInfoLogic.sampleInfo().getScore() != null, "the preview sample has a score");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("DungeonMapInfoChecks: " + message);
	}
}
