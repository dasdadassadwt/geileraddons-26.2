package geiler.addons.client.chat;

/** The shared Minecraft username rule used by chat, party, lobby and dungeon parsers. */
public final class ChatStringSemanticsChecks {
	private ChatStringSemanticsChecks() { }

	public static void run() {
		check(ChatStringSemantics.INSTANCE.isMinecraftUsername("Notch_01"), "a normal username is accepted");
		check(ChatStringSemantics.INSTANCE.isMinecraftUsername("a"), "a one character username is accepted");
		check(ChatStringSemantics.INSTANCE.isMinecraftUsername("abcdefghijklmnop"), "a 16 character username is accepted");
		check(!ChatStringSemantics.INSTANCE.isMinecraftUsername("abcdefghijklmnopq"), "a 17 character username is rejected");
		check(!ChatStringSemantics.INSTANCE.isMinecraftUsername(""), "an empty username is rejected");
		check(!ChatStringSemantics.INSTANCE.isMinecraftUsername("bad name"), "a username with a space is rejected");
		check(!ChatStringSemantics.INSTANCE.isMinecraftUsername("name!"), "a username with punctuation is rejected");
		check(!ChatStringSemantics.INSTANCE.isMinecraftUsername(null), "a null username is rejected");
		check(ChatStringSemantics.INSTANCE.getMINECRAFT_USERNAME().matcher("ok_name").matches(),
			"the shared pattern matches a whole username");
		check(!ChatStringSemantics.INSTANCE.getMINECRAFT_USERNAME().matcher("x ok_name").matches(),
			"the shared pattern is a full match, not a search");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
