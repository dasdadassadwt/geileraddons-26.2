package geiler.addons.client.chat;

/** Custom reply previews spell out the trigger and the result for both reply modes. */
public final class ChatReplyPreviewChecks {
	private ChatReplyPreviewChecks() { }

	public static void run() {
		check(ChatReplyPreview.describe("!", "hello", false, "Hi there").equals("!hello  →  you reply  \"Hi there\""),
			"text reply preview shows the trigger and the reply");
		check(ChatReplyPreview.describe("!", "tp", true, "/warp {args}").equals("!tp example  →  you run  /warp example"),
			"local action preview substitutes {args} with an example");
		check(ChatReplyPreview.HELP.contains("{args}") && ChatReplyPreview.HELP.contains("Text Reply"),
			"the help text explains both modes and the placeholder");
		check(ChatReplyPreview.HELP.contains("{rng:1-10}") && ChatReplyPreview.GUIDE_LINES.size() > 5,
			"the guide is step by step and lists the tags");
		check(ChatReplyPreview.describe("!", "hi", false, "{sender} says hi").equals("!hi  →  you reply  \"Steve says hi\""),
			"text reply preview fills tags with a sample sender");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
