package geiler.addons.client.chat;

/** The inline "Add Reply" form validates a draft with the same rules the old reply screen used. */
public final class ChatReplyDraftChecks {
	private ChatReplyDraftChecks() { }

	public static void run() {
		check(ChatReplyConfigRules.draftError("!", "hello", false, "Hi there") == null, "a plain reply is valid");
		check(ChatReplyConfigRules.draftError("!", "", false, "Hi") != null, "an empty command is rejected");
		check(ChatReplyConfigRules.draftError("!", "hello", false, "") != null, "an empty reply is rejected");
		check(ChatReplyConfigRules.draftError("!", "hello", false, "!hello") != null,
			"a reply starting with the prefix would loop");
		check(ChatReplyConfigRules.draftError("!", "hello", true, "/party kick {args}") == null,
			"a local action with one {args} is valid");
		check(ChatReplyConfigRules.draftError("!", "hello", true, "/party kick") != null,
			"a local action needs the {args} placeholder");
		check(ChatReplyConfigRules.draftError("!", "help", false, "x") != null, "built-in names are reserved");
		check(ChatReplyConfigRules.draftError("!", "pt", true, "/party transfer {args}") == null,
			"party transfer may be customised as a local action");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
