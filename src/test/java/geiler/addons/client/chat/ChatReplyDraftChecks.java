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

		// Inline edits of an existing reply apply live; an invalid half-typed edit leaves the saved reply alone.
		ChatReplyEntry saved = new ChatReplyEntry("id-1", "larp", ChatReplyEntry.Mode.TEXT_REPLY,
			"{sender} larps", "", "folder-1", true);
		ChatReplyEntry reply = ChatReplyConfigRules.editedEntry(saved, "!", "larp", false, " new text ", false);
		check(reply != null && reply.id().equals("id-1") && reply.response().equals("new text")
			&& "folder-1".equals(reply.folderId()) && !reply.enabled(),
			"an edited reply keeps its id and folder and takes the new text and switch");
		check(ChatReplyConfigRules.editedEntry(saved, "!", "larp", false, "!larp", true) == null,
			"an edit starting with the prefix is not applied");
		check(ChatReplyConfigRules.editedEntry(saved, "!", "", false, "x", true) == null
			&& ChatReplyConfigRules.editedEntry(saved, "!", "help", false, "x", true) == null,
			"a blank or built-in command name is not applied");
		check(ChatReplyConfigRules.editedEntry(saved, "!", "larp", true, "/party kick", true) == null,
			"a local action edit without {args} is not applied");
		ChatReplyEntry action = ChatReplyConfigRules.editedEntry(saved, "!", "kk", true, "/party kick {args}", true);
		check(action != null && action.mode() == ChatReplyEntry.Mode.LOCAL_ACTION && action.command().equals("kk"),
			"switching a reply to a local action with a valid template applies");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
