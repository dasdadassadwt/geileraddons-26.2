package geiler.addons.client.dungeon.helpers;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

/** Dependency-light checks for Three Weirdos clue coverage and conservative solving. */
public final class ThreeWeirdosLogicChecks {
	private ThreeWeirdosLogicChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		try {
			var solve = ThreeWeirdosLogic.class.getMethod("solve", List.class, List.class);
			var supportsStatement = ThreeWeirdosLogic.class.getMethod("supportsStatement", String.class);
			require(Modifier.isPublic(solve.getModifiers()) && Modifier.isStatic(solve.getModifiers())
				&& solve.getReturnType() == OptionalInt.class,
				"solve retains its Java-callable static ABI");
			require(Modifier.isPublic(supportsStatement.getModifiers())
				&& Modifier.isStatic(supportsStatement.getModifiers())
				&& supportsStatement.getReturnType() == boolean.class,
				"supportsStatement retains its Java-callable static ABI");
			require(solve.getGenericParameterTypes()[0].getTypeName().equals("java.util.List<java.lang.String>")
				&& solve.getGenericParameterTypes()[1].getTypeName().equals("java.util.List<java.lang.String>"),
				"solve keeps invariant Java List<String> generic signatures");
			require(Modifier.isFinal(ThreeWeirdosLogic.class.getModifiers())
				&& Modifier.isPrivate(ThreeWeirdosLogic.class.getDeclaredConstructor().getModifiers()),
				"solver remains a final utility class with a private constructor");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("the Java-callable solver methods remain present", exception);
		}
		require(!ThreeWeirdosLogic.supportsStatement(null), "a null clue is not recognized");
		require(ThreeWeirdosLogic.supportsStatement("§aThe reward isn’t in Bob’s chest!"),
			"color codes, punctuation, and curly apostrophes retain clue normalization");

		List<String> pinnedClues = List.of(
			"The reward is not in my chest!",
			"At least one of them is lying, and the reward is not in Bob's chest.",
			"My chest doesn't have the reward. We are all telling the truth.",
			"My chest has the reward and I'm telling the truth!",
			"The reward isn't in any of our chests.",
			"Both of them are telling the truth. Also, Bob has the reward in their chest.",
			"One of us is telling the truth!",
			"They are both telling the truth. The reward isn't in Bob's chest.",
			"We are all telling the truth!",
			"Bob is telling the truth and the reward is in his chest.",
			"My chest doesn't have the reward. At least one of the others is telling the truth!",
			"One of the others is lying.",
			"They are both telling the truth, the reward is in Bob's chest.",
			"They are both lying, the reward is in my chest!",
			"The reward is in my chest.",
			"The reward is not in my chest. They are both lying.",
			"Bob is telling the truth.",
			"My chest has the reward.");
		for (String clue : pinnedClues) {
			require(ThreeWeirdosLogic.supportsStatement(clue), "recognized pinned clue: " + clue);
		}
		require(!ThreeWeirdosLogic.supportsStatement("My chest has the reward, and Bob is lying."),
			"an unrecognized compound clue is rejected instead of partially parsed");
		require(ThreeWeirdosLogic.solve(null, List.of("x", "y", "z")).isEmpty(), "a null speaker roster is unknown");
		require(ThreeWeirdosLogic.solve(List.of("Alice", "Bob", "Carol"), null).isEmpty(),
			"a null clue list is unknown");
		require(ThreeWeirdosLogic.solve(List.of("Alice", "Bob"), List.of("x", "y")).isEmpty(),
			"rosters and clue lists must both contain three entries");
		require(ThreeWeirdosLogic.solve(List.of("Alice", "alice", "Carol"), List.of(
			"The reward is in my chest.", "The reward is in my chest.", "The reward is in my chest.")).isEmpty(),
			"speaker names that normalize to duplicates are rejected");
		require(ThreeWeirdosLogic.solve(Arrays.asList("Alice", null, "Carol"), List.of(
			"The reward is in my chest.", "The reward is in my chest.", "The reward is in my chest.")).isEmpty(),
			"a null speaker name is rejected");
		require(ThreeWeirdosLogic.solve(List.of("Alice", "Bob", "Carol"), Arrays.asList(
			"The reward is in my chest.", null, "The reward is in my chest.")).isEmpty(),
			"a null clue stays unknown");
		require(ThreeWeirdosLogic.solve(List.of("Alice", "Bob", "Carol"), List.of(
			"The reward is in my chest.", "The reward is in my chest.", "The reward is in my chest.")).isEmpty(),
			"multiple consistent reward chests stay ambiguous");

		var answer = ThreeWeirdosLogic.solve(List.of("Alice", "Bob", "Carol"), List.of(
			"Bob is telling the truth and the reward is in his chest.",
			"Carol is telling the truth.",
			"The reward is not in my chest. They are both lying."));
		require(answer.isPresent() && answer.getAsInt() == 2,
			"the supported compound clauses resolve only the uniquely consistent chest");
		require(ThreeWeirdosLogic.solve(List.of("Alice", "Bob", "Carol"), List.of(
			"Bob is telling the truth and the reward is in his chest.",
			"UnknownPlayer is telling the truth.",
			"The reward is not in my chest. They are both lying.")).isEmpty(),
			"a name outside the confirmed three-speaker roster leaves the solution unknown");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
