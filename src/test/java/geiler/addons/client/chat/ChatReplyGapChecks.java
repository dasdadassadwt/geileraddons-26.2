package geiler.addons.client.chat;

import java.util.Optional;

/** Gaps found by comparing against Odin's party commands: sender forms, downtime, reinvite, help and reserved names. */
public final class ChatReplyGapChecks {
	private ChatReplyGapChecks() { }

	public static void run() {
		senderLinesWithBadgesAndGuildRanksParse();
		downtimeAndReinviteParse();
		downtimeStateIsBoundedAndHoldsRequeue();
		helpListsNewCommandsAndNoStaleAllowlist();
		newNamesAreReserved();
	}

	private static Optional<ChatReplyCommandRules.Request> parse(String line) {
		return ChatReplyCommandRules.parse(line, "!");
	}

	private static void senderLinesWithBadgesAndGuildRanksParse() {
		var badge = parse("Party > [MVP+] Steve ⚒: !coords").orElse(null);
		check(badge != null && "Steve".equals(badge.sender()) && badge.channel() == ChatChannel.PARTY,
			"a party sender followed by the crossed-hammers badge parses");
		var ethiopic = parse("Party > Steve ቃ: !coords").orElse(null);
		check(ethiopic != null && "Steve".equals(ethiopic.sender()), "the second badge parses too");
		var guildRank = parse("Guild > [VIP] Steve [Officer]: !coords").orElse(null);
		check(guildRank != null && "Steve".equals(guildRank.sender()) && guildRank.channel() == ChatChannel.GUILD,
			"a guild sender followed by a guild rank tag parses");
		var coop = parse("Co-op > Steve ⚒: !coords").orElse(null);
		check(coop != null && coop.channel() == ChatChannel.COOP, "a co-op sender with a badge parses");
		check(parse("Party > Steve is great: !coords").isEmpty(), "free text between the name and the colon is still rejected");
		check(parse("Party > Steve [a] [b]: !coords").isEmpty(), "only one trailing tag is accepted");
	}

	private static void downtimeAndReinviteParse() {
		var dt = parse("Party > Alice: !dt Wither King DPS").orElse(null);
		check(dt != null && dt.command() == ChatReplyCommandRules.Command.PARTY_DOWNTIME
			&& "Wither King DPS".equals(dt.argument()), "dt keeps the reason with its case");
		var downtime = parse("Party > Alice: !downtime").orElse(null);
		check(downtime != null && downtime.command() == ChatReplyCommandRules.Command.PARTY_DOWNTIME
			&& downtime.argument() == null, "downtime without a reason has no argument");
		var undt = parse("Party > Alice: !undt").orElse(null);
		check(undt != null && undt.command() == ChatReplyCommandRules.Command.PARTY_UNDOWNTIME, "undt parses");
		check(parse("Party > Alice: !undowntime").isPresent(), "undowntime is an alias");
		check(parse("Party > Alice: !undt now").isEmpty(), "undt takes no argument");
		check(parse("Guild > Alice: !dt").isEmpty(), "downtime is a party command");
		var reinvite = parse("Party > Alice: !reinv").orElse(null);
		check(reinvite != null && reinvite.command() == ChatReplyCommandRules.Command.PARTY_REINVITE
			&& parse("Party > Alice: !reinvite").isPresent(), "reinvite and reinv parse");
		check(parse("Private > x: !reinv").isEmpty() && parse("From Alice: !reinv").isEmpty(), "reinvite is party only");
		var longReason = parse("Party > Alice: !dt " + "x".repeat(200)).orElse(null);
		check(longReason != null && longReason.argument().length() <= 60, "a long reason is cut to 60 characters");
	}

	private static void downtimeStateIsBoundedAndHoldsRequeue() {
		PartyDowntime.clear();
		check(!PartyDowntime.holdsRequeue(), "no reminders, no hold");
		check(PartyDowntime.add("Alice", null) && "No reason given".equals(PartyDowntime.reasonFor("alice")),
			"a missing reason is filled in and names match ignoring case");
		check(!PartyDowntime.add("ALICE", "again"), "a second reminder from the same player is refused");
		check(PartyDowntime.holdsRequeue(), "a reminder holds the requeue");
		check(PartyDowntime.add("Bob", "Mana") && PartyDowntime.summary().contains("Alice") && PartyDowntime.summary().contains("Mana"),
			"the summary lists every reminder");
		check(PartyDowntime.remove("alice") && !PartyDowntime.remove("alice"), "undt removes once");
		check(PartyDowntime.holdsRequeue(), "the hold stays while another reminder exists");
		PartyDowntime.remove("Bob");
		check(!PartyDowntime.holdsRequeue(), "the hold ends with the last reminder");
		for (int i = 0; i < 40; i++) PartyDowntime.add("P" + i, "r");
		check(PartyDowntime.size() == 16, "reminders are capped at 16");
		PartyDowntime.clear();
		check(PartyDowntime.size() == 0 && !PartyDowntime.holdsRequeue(), "clear empties it");
	}

	private static void helpListsNewCommandsAndNoStaleAllowlist() {
		String help = ChatReplyCommandRules.helpText("!", ChatChannel.PARTY);
		check(help.contains("!dt [reason]") && help.contains("!undt") && help.contains("!reinvite") && help.contains("!inv"),
			"help lists downtime, reinvite and invite");
		check(!help.contains("allowlist"), "the removed allowlist is no longer mentioned");
		check(!help.contains("Odin queue"), "the queue line does not name the Odin mod");
	}

	private static void newNamesAreReserved() {
		for (String name : new String[] {"dt", "downtime", "undt", "undowntime", "reinv", "reinvite"}) {
			check(ChatReplyConfigRules.isReservedCommand(name), name + " cannot be used as a custom command name");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
