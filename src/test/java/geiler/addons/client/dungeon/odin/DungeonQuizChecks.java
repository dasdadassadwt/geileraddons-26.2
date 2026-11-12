package geiler.addons.client.dungeon.odin;

import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import java.util.Optional;

/** Focused offline checks for exact Quiz facts and fail-closed prompt observation. */
public final class DungeonQuizChecks {
	private DungeonQuizChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkTrackerJavaInterop();
		checkChatMarkerStyling();
		Map<String, String> curatedStatuses = Map.of(
			"What is the status of Bonzo?", "New Necromancer",
			"What is the status of Scarf?", "Apprentice Necromancer",
			"What is the status of The Professor?", "Professor",
			"What is the status of Thorn?", "Shaman Necromancer",
			"What is the status of Livid?", "Master Necromancer",
			"What is the status of Sadan?", "Necromancer Lord",
			"What is the status of The Watcher?", "Stalker",
			"What is the status of Maxor, Storm, Goldor, and Necron?", "The Wither Lords");
		curatedStatuses.forEach((question, answer) -> require(answer.equals(
			DungeonQuizRules.resolveBossStatus(question)),
			"recognized boss-status question resolves to its curated fact: " + question));
		require(DungeonQuizRules.resolveBossStatus("What is the status of an unknown boss?") == null,
			"unknown boss status remains unanswered");

		Map<String, String> oneFact = Map.of("What is the status of Scarf?", "Apprentice Necromancer");
		require("Apprentice Necromancer".equals(DungeonQuizRules.resolveExactAnswer(
			"§eWHAT IS THE STATUS OF SCARF?", oneFact)),
			"fact matching normalizes formatting and letter case");
		Map<String, String> ambiguous = new LinkedHashMap<>();
		ambiguous.put("What is the status of Scarf?", "Apprentice Necromancer");
		ambiguous.put("WHAT IS THE STATUS OF SCARF!", "Unknown");
		require(DungeonQuizRules.resolveExactAnswer("What is the status of Scarf?", ambiguous) == null,
			"conflicting keys that normalize identically fail closed");
		ambiguous.put("WHAT IS THE STATUS OF SCARF!", "Apprentice Necromancer");
		require("Apprentice Necromancer".equals(DungeonQuizRules.resolveExactAnswer(
			"What is the status of Scarf?", ambiguous)),
			"duplicate normalized keys with the same answer remain unambiguous");

		String known = "What is the status of Scarf?";
		require(known.equals(DungeonQuizRules.observedQuestion(
			"[STATUE] Oruo the Omniscient: " + known, true, true, false)),
			"Oruo question flow resolves a supported question key");
		require(known.equals(DungeonQuizRules.observedQuestion(known, true, true, false)),
			"known Quiz answer keys match without local conversation gating");
		require(known.equals(DungeonQuizRules.observedQuestion(known, true, true, true)),
			"active Oruo phase accepts a bare exact supported fact question");
		require(DungeonQuizRules.observedQuestion(known, false, true, true) == null
			&& DungeonQuizRules.observedQuestion(known, true, false, true) == null,
			"out-of-dungeon and bare non-puzzle messages cannot supply a prompt");
		require(known.equals(DungeonQuizRules.observedQuestion(
			"[STATUE] Oruo the Omniscient: " + known, true, false, false)),
			"an explicit Oruo question can be captured even when room detection is outside Quiz");
		require(DungeonQuizRules.observedQuestion("What should we do?", true, true, true) == null,
			"unverified bare chat questions are not captured as Quiz prompts");
		require(!DungeonQuizRules.isOruoCorrectAnswer(
			"[STATUE] Oruo the Omniscient: The clue was answered correctly by someone."),
			"unrelated Oruo prose ending in correctly does not advance Quiz state");

		DungeonQuizTracker tracker = new DungeonQuizTracker();
		tracker.onChat("[STATUE] Oruo the Omniscient: I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.", true, true);
		require(tracker.conversationActive() && tracker.question() == null,
			"exact Oruo stage line opens Quiz capture without inventing a question");
		tracker.onChat("What is the status of Scarf?", true, true);
		require(known.equals(tracker.question()), "active Quiz captures the matching observed prompt");
		require(tracker.acceptedAnswers().contains("Apprentice Necromancer"),
			"accepted answer options are retained for live chat confirmation");
		tracker.onChat("ⓑ Apprentice Necromancer", true, true);
		require(tracker.confirmedOption() != null && tracker.confirmedOption().marker().equals("ⓑ")
			&& tracker.confirmedOption().answer().equals("Apprentice Necromancer"),
			"matching Odin-style option chat identifies the accepted choice for display guidance");
		tracker.onChat("[STATUE] Oruo the Omniscient: Player answered Question #1 correctly!", true, true);
		require(tracker.conversationActive() && known.equals(tracker.question())
			&& tracker.acceptedAnswers().contains("Apprentice Necromancer") && tracker.confirmedOption() == null,
			"correct answer progression retains the source answer map and clears only the shown option");
		tracker.onChat("[STATUE] Oruo the Omniscient: Player answered the final question correctly!", true, true);
		require(!tracker.conversationActive() && tracker.question() == null,
			"final correct answer closes the tracked Quiz session");
		tracker.onChat("[STATUE] Oruo the Omniscient: What is the status of Bonzo?", true, true);
		tracker.onChat("ⓐ New Necromancer", true, false);
		require("What is the status of Bonzo?".equals(tracker.question())
			&& tracker.confirmedOption() != null && tracker.confirmedOption().marker().equals("ⓐ"),
			"an explicit prompt and confirmed option remain available while outside the detected Quiz room");
		tracker.onChat("ignored", true, false);
		require("What is the status of Bonzo?".equals(tracker.question()) && tracker.conversationActive(),
			"unrelated chat outside Quiz does not clear retained answer state");
		tracker.onChat("[STATUE] Oruo the Omniscient: Player answered the final question correctly!", true, false);
		require(tracker.question() == null && !tracker.conversationActive(),
			"the exact Oruo final-answer line clears retained Quiz state from outside the room");

		long epochStart = 1_560_276_000L;
		DungeonQuizTracker nullLine = new DungeonQuizTracker();
		nullLine.onChat("[STATUE] Oruo the Omniscient: I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.", true, true, epochStart);
		nullLine.onChat(known, true, true, epochStart);
		nullLine.onChat(null, true, false);
		require(nullLine.conversationActive() && known.equals(nullLine.question())
			&& !nullLine.acceptedAnswers().isEmpty(),
			"a missing chat line does not erase retained Quiz state");
		nullLine.onChat("ignored", false, false);
		require(!nullLine.conversationActive() && nullLine.question() == null,
			"leaving the dungeon clears retained Quiz state");
		require(DungeonQuizRules.acceptedAnswers("What SkyBlock year is it?", epochStart)
			.equals(List.of("Year 1")), "SkyBlock-year answer begins at the verified epoch");
		require(DungeonQuizRules.acceptedAnswers("what skyblock year is it", epochStart + 446_400L)
			.equals(List.of("Year 2")), "SkyBlock-year answer advances after one year interval");
		require(DungeonQuizRules.parseConfirmedOption("ⓐ Year 2", List.of("Year 1")) == null,
			"a choice with a non-accepted answer is not highlighted");
		var longest = DungeonQuizRules.parseConfirmedOption("ⓒ Longer answer", List.of("answer", "Longer answer"));
		require(longest != null && longest.marker().equals("ⓒ") && longest.answer().equals("Longer answer"),
			"overlapping accepted strings choose the longest exact suffix");
		require(DungeonQuizRules.parseConfirmedOption("ⓓ Apprentice Necromancer",
			List.of("Apprentice Necromancer")) == null, "unsupported option markers are ignored");

		tracker.onChat("[STATUE] Oruo the Omniscient: I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.", true, true, epochStart);
		tracker.onChat("[STATUE] Oruo the Omniscient: What SkyBlock year is it?", true, true, epochStart + 446_400L);
		require(tracker.acceptedAnswers().equals(List.of("Year 2")),
			"the live year prompt receives its clock-derived answer rather than static catalog data");

		DungeonQuizTracker timed = new DungeonQuizTracker();
		timed.onChat("[STATUE] Oruo the Omniscient: I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.", true, true, epochStart);
		require(timed.timerTicks() == 220 && timed.timerMaximumTicks() == 220 && timed.timerStage() == 1,
			"the first Oruo stage starts its 220-tick timer through the Java facade");
		timed.onServerGameTime(500L);
		timed.onServerGameTime(505L);
		require(timed.timerTicks() == 215, "Quiz timer decrements once for observed server game-time ticks");
		timed.onServerGameTime(10L);
		timed.onServerGameTime(11L);
		require(timed.timerTicks() == 214, "a server game-time rollback resets the timer baseline before decrementing");
		timed.onChat("[STATUE] Oruo the Omniscient: 2 questions left... Then you will have proven your worth to me!", true, true, epochStart);
		require(timed.timerTicks() == 100 && timed.timerMaximumTicks() == 100 && timed.timerStage() == 2,
			"the second Oruo stage replaces the active timer with its 100-tick stage value");
		timed.onServerGameTime(100L);
		timed.onServerGameTime(1_000L);
		require(timed.timerTicks() == 0 && timed.timerMaximumTicks() == 100,
			"Quiz timer clamps at zero while retaining the stage maximum");
		timed.onChat("ignored", true, false, epochStart);
		require(timed.timerTicks() == 0 && timed.timerMaximumTicks() == 100 && timed.timerStage() == 2,
			"leaving the detected Quiz room retains stage metadata while the timer remains expired");
		timed.onChat("ignored", false, false, epochStart);
		require(timed.timerTicks() == 0 && timed.timerMaximumTicks() == 0 && timed.timerStage() == 0,
			"leaving the dungeon resets all Quiz timer state");
	}

	private static void checkChatMarkerStyling() {
		Component input = Component.literal("ⓑ ")
			.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xAAAAAA)))
			.append(Component.literal("Apprentice Necromancer")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF))));
		Component styled = DungeonQuizChatStyler.apply(input, "ⓑ", "Apprentice Necromancer");
		require(styled != input && "ⓑ Apprentice Necromancer".equals(styled.getString())
			&& hasColor(styled, 0x55FF55) && hasColor(styled, 0xFFFFFF),
			"the accepted option marker is green while the answer text and its original style remain intact");
		Component unrelated = Component.literal("ⓑ unrelated");
		require(DungeonQuizChatStyler.apply(input, "ⓐ", "Apprentice Necromancer") == input
			&& DungeonQuizChatStyler.apply(input, "ⓑ", "New Necromancer") == input
			&& DungeonQuizChatStyler.apply(unrelated, "ⓑ", "Apprentice Necromancer") == unrelated,
			"wrong markers and unrelated chat remain unchanged");
	}

	private static boolean hasColor(Component component, int expectedRgb) {
		boolean[] found = {false};
		component.visit((style, text) -> {
			if (style.getColor() != null && style.getColor().getValue() == expectedRgb) found[0] = true;
			return Optional.empty();
		}, Style.EMPTY);
		return found[0];
	}

	private static void checkTrackerJavaInterop() {
		Class<DungeonQuizTracker> tracker = DungeonQuizTracker.class;
		try {
			require(Modifier.isPublic(tracker.getConstructor().getModifiers()),
				"the Java-compatible Quiz tracker retains its public no-argument constructor");
			checkInstanceMethod(tracker, "onChat", void.class, String.class, boolean.class, boolean.class);
			checkInstanceMethod(tracker, "onChat", void.class,
				String.class, boolean.class, boolean.class, long.class);
			checkInstanceMethod(tracker, "onServerGameTime", void.class, long.class);
			checkInstanceMethod(tracker, "question", String.class);
			checkInstanceMethod(tracker, "conversationActive", boolean.class);
			checkInstanceMethod(tracker, "acceptedAnswers", List.class);
			checkInstanceMethod(tracker, "confirmedOption", DungeonQuizRules.ConfirmedOption.class);
			checkInstanceMethod(tracker, "timerTicks", int.class);
			checkInstanceMethod(tracker, "timerMaximumTicks", int.class);
			checkInstanceMethod(tracker, "timerStage", int.class);
			checkInstanceMethod(tracker, "reset", void.class);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Quiz tracker Java API changed", exception);
		}
	}

	private static void checkInstanceMethod(Class<?> owner, String name, Class<?> returnType,
		Class<?>... parameterTypes) throws ReflectiveOperationException {
		var method = owner.getMethod(name, parameterTypes);
		require(method.getReturnType() == returnType && Modifier.isPublic(method.getModifiers())
			&& !Modifier.isStatic(method.getModifiers()),
			"the Quiz tracker retains its public instance descriptor: " + name);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
