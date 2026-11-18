package geiler.addons.client.chat;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Random;

/** Tags in Text Reply responses: substitution, bounds and what stays literal. */
public final class ChatReplyTagChecks {
	private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 18, 9, 5, 7, 0, ZoneOffset.UTC);

	private ChatReplyTagChecks() { }

	public static void run() {
		namesAndTimeSubstitute();
		rngStaysInsideInclusiveRange();
		pickChoosesAnOption();
		unknownAndMalformedTagsStayLiteral();
		validationRejectsBadRngAndPick();
		plainTemplatesAreUnchanged();
		expandedRepliesAreRevalidated();
	}

	private static String expand(String template) {
		return ChatReplyTags.expand(template, "Steve", "Alex", NOW, new Random(1));
	}

	private static void namesAndTimeSubstitute() {
		check(expand("hi {sender}, I am {me}").equals("hi Steve, I am Alex"), "sender and me substitute");
		check(expand("{sender}{sender}").equals("SteveSteve"), "a tag can repeat");
		check(expand("at {time}").equals("at 2026-11-18 09:05:07 Z"), "time uses the built-in time format");
	}

	private static void rngStaysInsideInclusiveRange() {
		boolean sawLow = false;
		boolean sawHigh = false;
		Random random = new Random(7);
		for (int i = 0; i < 400; i++) {
			int value = Integer.parseInt(ChatReplyTags.expand("{rng:1-3}", "a", "b", NOW, random));
			check(value >= 1 && value <= 3, "rng stays in range, got " + value);
			sawLow |= value == 1;
			sawHigh |= value == 3;
		}
		check(sawLow && sawHigh, "rng reaches both ends");
		check(expand("{rng:5-5}").equals("5"), "equal bounds give that value");
		check(expand("{rng:0-1000000}") != null, "the largest allowed range expands");
	}

	private static void pickChoosesAnOption() {
		for (int i = 0; i < 20; i++) {
			String value = ChatReplyTags.expand("{pick:red|green|blue}", "a", "b", NOW, new Random(i));
			check(value.equals("red") || value.equals("green") || value.equals("blue"), "pick returns an option: " + value);
		}
		check(expand("{pick: yes }").equals("yes"), "options are trimmed");
	}

	private static void unknownAndMalformedTagsStayLiteral() {
		check(expand("{args} {nope}").equals("{args} {nope}"), "unknown tags are not touched");
		check(expand("{rng:5-1}").equals("{rng:5-1}"), "a reversed range is left alone");
		check(expand("{rng:1-2000000}").equals("{rng:1-2000000}"), "an over-large range is left alone");
		check(expand("{pick:}").equals("{pick:}"), "an empty pick is left alone");
	}

	private static void validationRejectsBadRngAndPick() {
		check(ChatReplyTags.validate("{rng:1-10}"), "good rng is valid");
		check(ChatReplyTags.validate("{pick:a|b}"), "good pick is valid");
		check(ChatReplyTags.validate("{args} and {nope}"), "unknown tags are allowed as literal text");
		check(!ChatReplyTags.validate("{rng:5-1}"), "reversed range is invalid");
		check(!ChatReplyTags.validate("{rng:1-2000000}"), "over-large range is invalid");
		check(!ChatReplyTags.validate("{rng:x}"), "non-numeric rng is invalid");
		check(!ChatReplyTags.validate("{pick:}"), "empty pick is invalid");
		check(!ChatReplyTags.validate("{pick:a||b}"), "an empty option is invalid");
		check(!ChatReplyTags.validate("{pick:" + "a|".repeat(17) + "a}"), "more than 16 options is invalid");
	}

	private static void plainTemplatesAreUnchanged() {
		check(expand("just text").equals("just text"), "no tags, no change");
		check(ChatReplyConfigRules.validResponse("{sender} rolled {rng:1-10}"), "a tagged response is a valid reply");
		check(!ChatReplyConfigRules.validResponse("{rng:9-1}"), "an invalid tag makes the reply invalid");
	}

	private static void expandedRepliesAreRevalidated() {
		String longTemplate = "{sender}".repeat(20);
		check(longTemplate.length() == 160 && ChatReplyConfigRules.validResponse(longTemplate), "template fits the limit");
		String grown = ChatReplyTags.expand(longTemplate, "SixteenCharName_", "Alex", NOW, new Random(1));
		check(!ChatReplyConfigRules.validResponse(grown), "expansion that grows past 160 is rejected");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
