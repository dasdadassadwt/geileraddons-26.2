package geiler.addons.client.enchanting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every discovered Superpairs pair gets its own colour so two known pairs are never confused. */
public final class SuperpairsPairColorsChecks {
	private SuperpairsPairColorsChecks() { }

	public static void run() {
		Map<String, List<Integer>> pairs = new LinkedHashMap<>();
		pairs.put("pair-0", List.of(10, 14));
		pairs.put("pair-1", List.of(11));
		pairs.put("pair-2", List.of(12, 20));
		pairs.put("pair-3", List.of(13, 21));
		List<SuperpairsBoard.Card> cards = List.of(
			new SuperpairsBoard.Card(10, "pair-0", SuperpairsBoard.CardState.MATCH),
			new SuperpairsBoard.Card(14, "pair-0", SuperpairsBoard.CardState.MATCH),
			new SuperpairsBoard.Card(11, "pair-1", SuperpairsBoard.CardState.KNOWN),
			new SuperpairsBoard.Card(12, "pair-2", SuperpairsBoard.CardState.MATCH),
			new SuperpairsBoard.Card(20, "pair-2", SuperpairsBoard.CardState.MATCH),
			new SuperpairsBoard.Card(13, "pair-3", SuperpairsBoard.CardState.RESOLVED),
			new SuperpairsBoard.Card(21, "pair-3", SuperpairsBoard.CardState.RESOLVED));
		Map<Integer, Integer> index = SuperpairsPairColors.indexBySlot(new SuperpairsBoard.View(cards, -1, -1, pairs));
		check(index.get(10).equals(index.get(14)), "both cards of a pair share one index");
		check(index.get(12).equals(index.get(20)), "the second pair shares its own index");
		check(!index.get(10).equals(index.get(12)), "two open pairs get different indexes");
		check(!index.containsKey(11), "a card whose partner is unknown is not part of a pair yet");
		check(!index.containsKey(13) && !index.containsKey(21), "collected pairs are not coloured");
		check(SuperpairsPairColors.color(0) != SuperpairsPairColors.color(1), "neighbouring indexes differ in colour");
		check(SuperpairsPairColors.color(0) == SuperpairsPairColors.color(SuperpairsPairColors.PALETTE_SIZE),
			"the palette wraps around");
		check(SuperpairsPairColors.border(2) != SuperpairsPairColors.color(2), "the border is a darker shade");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
