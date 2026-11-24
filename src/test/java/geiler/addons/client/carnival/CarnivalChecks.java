package geiler.addons.client.carnival;

import geiler.addons.client.module.impl.CarnivalModule;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import kotlin.random.RandomKt;

/** Offline checks for the SkyHanni Carnival port: patterns, Fruit Digging state and solver, goals, store and shops. */
public final class CarnivalChecks {
	private CarnivalChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		checkFruitTextures();
		checkPatterns();
		checkGameFlow();
		checkSolver();
		checkGoals();
		checkStoreAndShops();
		checkModuleHelpers();
	}

	private static void checkFruitTextures() {
		String json = "{\"timestamp\":1,\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
			+ CarnivalFruit.DURIAN.getTextureHash() + "\"}}}";
		String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		require(CarnivalFruit.fromTexture(base64) == CarnivalFruit.DURIAN, "a head texture resolves to its fruit by skin hash");
		require(CarnivalFruit.fromTexture("not base64!") == null && CarnivalFruit.fromTexture("") == null,
			"broken or empty textures are not fruit");
		require(CarnivalFruit.fromName("dragonfruit") == CarnivalFruit.DRAGON_FRUIT, "fruit names match case-insensitively");
		require(CarnivalFruit.APPLE.pointValue(2, CarnivalFruit.NO_FRUIT) == 300
			&& CarnivalFruit.APPLE.pointValue(2, CarnivalFruit.POMEGRANATE) == 450
			&& CarnivalFruit.MANGO.pointValue(0, CarnivalFruit.DURIAN) == 150,
			"apples grow with each dug apple and pomegranate/durian scale the next dig");
	}

	private static void checkPatterns() {
		CarnivalPatterns p = CarnivalPatterns.INSTANCE;
		require(p.getTREASURE().matches("TREASURE! There is an Apple nearby."), "treasure clue matches");
		require(p.getMINES().matches("MINES! There are 2 bombs hidden nearby.") && p.getMINES().matches("MINES! There is 1 bomb hidden nearby."),
			"mines clue matches singular and plural");
		require(p.getNO_FRUITS_NEARBY().matches("ANCHOR! There are no fruits nearby!"), "no-fruit clue matches");
		require(p.getREVEAL().matches("Pomegranate (+300)") && p.getREVEAL().matches("Bomb"), "reveal names match with and without points");
		require(p.getFRUIT_END().matches("Fruit Digging") && !p.getFRUIT_END().matches("Fruit Digging goals"), "the trimmed summary title ends a game");
		require(p.getSHOOTOUT_START().matches("[NPC] Carnival Cowboy: Good luck, pal!"), "shootout start matches");
		require(p.getTICKETS_CLAIMED().matches("You claimed Carnival Ticket x25!"), "ticket claim matches");
		require(CarnivalPatterns.roman("IV") == 4 && CarnivalPatterns.roman("X") == 10 && CarnivalPatterns.roman("Q") == 0,
			"upgrade tiers read roman numerals");
		require("carnival_pirateman".equals(CarnivalPatterns.npcId("Carnival Pirateman")) && CarnivalPatterns.npcId("Bob") == null,
			"game masters map to their dialogue npc ids");
	}

	private static FruitDiggingGame newGame() {
		return new FruitDiggingGame(new FruitDiggingSolver(7, RandomKt.Random(42)));
	}

	private static void checkGameFlow() {
		FruitDiggingGame game = newGame();
		require(!game.onChat("MINES! There is 1 bomb hidden nearby."), "clues before a game starts are ignored");
		require(game.onChat("[NPC] Carnival Pirateman: Good luck, matey!") && game.getPlaying(), "the pirate's line starts a game");
		require(game.updateRecommendation() && game.getRecommendation() != null, "an opening dig is recommended before the first dig");
		game.onBlockChange(2, 3, FruitDiggingGame.Block.SAND, FruitDiggingGame.Block.SANDSTONE);
		require(game.getDigsUsed() == 1 && !game.cell(2, 3).getDiggable(), "sand turning to sandstone is one dig");
		require(game.onChat("MINES! There are 2 bombs hidden nearby.") && Integer.valueOf(2).equals(game.cell(2, 3).getAdjacentMines()),
			"the mines clue lands on the last dug cell");
		game.onReveal(2, 3, "Mango (+300)");
		require(game.remaining(CarnivalFruit.MANGO) == 9 && game.cell(2, 3).found() == CarnivalFruit.MANGO,
			"a revealed fruit leaves the remaining bag once");
		game.onReveal(2, 3, "Mango (+300)");
		require(game.remaining(CarnivalFruit.MANGO) == 9, "the same reveal is not counted twice");
		require(game.lastDugFruit() == CarnivalFruit.MANGO, "the last dug fruit drives the next multiplier");
		require(game.updateRecommendation(), "a board change recomputes the recommendation");
		FruitDiggingSolver.Recommendation rec = game.getRecommendation();
		require(rec != null && !(rec.getTargetRow() == 2 && rec.getTargetCol() == 3) && rec.getPBomb() >= 0 && rec.getPBomb() <= 1,
			"the recommendation is a still-diggable cell with a probability");
		require(!game.updateRecommendation(), "an unchanged board is not solved again");
		game.onChat("Fruit Digging");
		require(!game.getPlaying() && game.getDigsUsed() == 0, "the summary title resets the board");
		require("A1".equals(FruitDiggingGame.label(0, 0)) && "G7".equals(FruitDiggingGame.label(6, 6)), "cells are labelled like SkyHanni");
		require(FruitDiggingGame.colOf(-111.5) == 0 && FruitDiggingGame.rowOf(25.9) == 6 && FruitDiggingGame.colOf(-105.01) == 6
			&& FruitDiggingGame.colOf(-112.01) == -1, "world positions map onto board cells");
	}

	private static void checkSolver() {
		// Every neighbour of A1 holds a bomb according to the clue; the solver must not recommend one of them.
		FruitDiggingSolver solver = new FruitDiggingSolver(7, RandomKt.Random(7));
		FruitDiggingSolver.CellInput[][] grid = new FruitDiggingSolver.CellInput[7][7];
		for (int r = 0; r < 7; r++) for (int c = 0; c < 7; c++) {
			grid[r][c] = new FruitDiggingSolver.CellInput(null, true, false, false, null, null, null);
		}
		grid[0][0] = new FruitDiggingSolver.CellInput(CarnivalFruit.APPLE, false, false, false, 3, null, null);
		FruitDiggingSolver.Recommendation rec = solver.recommend(grid, 1, 1.0, false);
		require(rec != null, "a board with clues still gets a recommendation");
		boolean neighbour = rec.getTargetRow() <= 1 && rec.getTargetCol() <= 1;
		require(!neighbour, "cells that must all be bombs are never the recommended dig: " + rec);
		require(rec.getSamples() < 500, "the bomb clue constrains the sampled boards");
		require(rec.getSamples() > 0, "the clue leaves boards to sample");
	}

	private static void checkGoals() {
		require(CarnivalGoal.fromChat("Shoot 60 Zombies in a single game of Zombie Shootout.") == CarnivalGoal.SHOOT_ZOMBIE,
			"a completion line names its goal");
		require(CarnivalGoal.fromChat("- Dig up a Dragonfruit.") == CarnivalGoal.DRAGONFRUIT, "the list prefix is ignored");
		require(CarnivalGoal.fromLore(CarnivalGoal.Game.CATCH_A_FISH, "Play 3 games of Catch a Fish.") == CarnivalGoal.CATCH_A_FISH_PLAY,
			"lore resolves within its game");
		require(CarnivalGoal.fromLore(CarnivalGoal.Game.FRUIT_DIGGING, "Reach 3,000 score in a single game") == CarnivalGoal.FRUIT_DIGGING_SCORE
			&& CarnivalGoal.fromLore(CarnivalGoal.Game.ZOMBIE_SHOOTOUT, "Reach 3,000 score in a single game") == CarnivalGoal.ZOMBIE_SHOOTOUT_SCORE,
			"shared wording is told apart by the item's game");
	}

	private static void checkStoreAndShops() {
		require(CarnivalStore.skyblockYear(1_560_275_700_000L) == 1 && CarnivalStore.skyblockYear(1_560_275_700_000L + 446_400_000L) == 2,
			"SkyBlock years last 124 hours from the SkyBlock epoch");
		require(CarnivalStore.perkInElection("{\"mayor\":{\"name\":\"Foxy\",\"perks\":[{\"name\":\"Chivalrous Carnival\"}]}}"),
			"Foxy's perk activates the reminder");
		require(CarnivalStore.perkInElection("{\"mayor\":{\"perks\":[],\"minister\":{\"perk\":{\"name\":\"Chivalrous Carnival\"}}}}"),
			"the minister's perk counts too");
		require(!CarnivalStore.perkInElection("{\"mayor\":{\"perks\":[{\"name\":\"Pathfinder\"}]}}") && !CarnivalStore.perkInElection("nope"),
			"other mayors and broken payloads do not");
		List<TokenShopProgress.Shop> shops = TokenShopProgress.parse(
			"{\"carnivalTokenShops\":{\"Fishing_Festival\":{\"a\":{\"name\":\"Fishing Prodigy\",\"costs\":[50,100,150]}}}}");
		require(shops.size() == 1 && "Fishing Festival".equals(shops.get(0).getName()), "shop keys become names");
		TokenShopProgress.Progress progress = TokenShopProgress.progress(shops.get(0), Map.of("fishing prodigy", 1));
		require(progress.getRemainingSum() == 250 && progress.getUpgrades().get(0).getCurrentLevel() == 1,
			"bought tiers are subtracted from the remaining cost");
		require(TokenShopProgress.progress(shops.get(0), Map.of("Fishing Prodigy", 9)).getRemainingSum() == 0,
			"levels beyond the table cap at fully bought");
	}

	private static void checkModuleHelpers() {
		require(CarnivalModule.sidebarInCarnival("Hub\n ⏣ Carnival\nPurse: 5") && !CarnivalModule.sidebarInCarnival("Carnival Tickets\n ⏣ Village"),
			"only the location row decides the Carnival area");
		require(CarnivalModule.timerColor(6_000L, 6_000L) == 0xFF55FF55 && CarnivalModule.timerColor(3_000L, 6_000L) == 0xFFFFFF55
			&& CarnivalModule.timerColor(500L, 6_000L) == 0xFFFF5555, "timers go green, yellow, red by thirds");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
