package geiler.addons.client.chat;

import geiler.addons.client.dungeon.DungeonClass;
import geiler.addons.client.party.PartyMember;
import geiler.addons.client.party.PartySnapshot;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.impl.PartyCommandsModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Focused offline checks for incoming chat reply aliases, validation, and delayed queue behavior. */
public final class ChatReplyCommandChecks {
	private ChatReplyCommandChecks() { }
	public static void main(String[] args) { run(); }

	public static void run() {
		actionTemplateRulesKeepJavaEntrypoints();
		replyConfigRulesKeepJavaEntrypoints();
		replyCommandRulesKeepJavaEntrypoints();
		replyCommandRulesPreserveNullAndWhitespaceSemantics();
		replyEntryKeepsJavaValueAbi();
		replyEntryNormalizationAndValidationStayKotlin();
		backendKeepsJavaEntrypoints();
		parsesSupportedAliasesAndChannels();
		parsesLowRiskOdinRepliesAndCustomEntries();
		parsesOdinPartyActionAliases();
		incomingChannelDefaultsAndOverrides();
		productionIncomingParserUsesSavedChannelRules();
		parsesCustomChannelReplyRoutes();
		coordinatesUseOdinBlockFormat();
		legacyFlatReplyGetsStableRootDefaults();
		legacyReplyJsonRoundTripPreservesInferredModesAndPayloads();
		legacyLocalFileMigratesAndRoundTripsThroughStoreIo();
		redactorPreservesUnicodeAcrossBufferBoundaries();
		redactorDepthAndTokenLimitsRemainBounded();
		failedNonAtomicReplacementRestoresOriginal();
		retiredTranslationFieldsAreRemovedFromSavedFiles();
		oversizedStoreRedactsFieldsWithoutOverwritingReplies();
		malformedStoreRedactsOnlyWhenTheValueBoundaryIsKnown();
		cleanupLimitPreservesOriginalAndBlocksWrites();
		senderFilterTogglesMigrateAndPersist();
		folderEnableSwitchesCascadeToNestedReplies();
		boundsCustomReplyConfigurationAndSenderFilters();
		validatesTransferAgainstFreshLeaderRoster();
		validatesPartyActionsAgainstFreshLeaderRoster();
		incomingPartyActionsTrustThePartyPrefix();
		queueIsBoundedAndHonorsDueTicks();
		queueKeepsJavaEntrypointsAndStableOrdering();
	}

	private static void actionTemplateRulesKeepJavaEntrypoints() {
		try {
			var normalize = ChatActionTemplateRules.class.getMethod("normalize", String.class);
			var isValid = ChatActionTemplateRules.class.getMethod("isValid", String.class);
			var expand = ChatActionTemplateRules.class.getMethod("expand", String.class, String.class);
			check(Modifier.isPublic(normalize.getModifiers()) && Modifier.isStatic(normalize.getModifiers())
				&& normalize.getReturnType() == String.class,
				"reply editor keeps the public static template normalizer");
			check(Modifier.isPublic(isValid.getModifiers()) && Modifier.isStatic(isValid.getModifiers())
				&& isValid.getReturnType() == boolean.class,
				"reply editor keeps the public static template validator");
			check(Modifier.isPublic(expand.getModifiers()) && Modifier.isStatic(expand.getModifiers())
				&& expand.getReturnType() == Optional.class,
				"reply execution keeps the public static template expander");
			var maxLength = ChatActionTemplateRules.class.getField("MAX_TEMPLATE_LENGTH");
			check(Modifier.isPublic(maxLength.getModifiers()) && Modifier.isStatic(maxLength.getModifiers())
				&& Modifier.isFinal(maxLength.getModifiers()) && maxLength.getInt(null) == 128,
				"reply editor keeps the public static template length limit");
			check(ChatActionTemplateRules.isValid("/\u00a0 {args}"),
				"action template validation retains Java String.isBlank semantics");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("chat action template Java entrypoint changed", exception);
		}
	}

	private static void replyConfigRulesKeepJavaEntrypoints() {
		Object[][] entryPoints = {
			{"normalizeCommand", new Class<?>[] {String.class}, String.class},
			{"isReservedCommand", new Class<?>[] {String.class}, boolean.class},
			{"isPartyTransferName", new Class<?>[] {String.class}, boolean.class},
			{"validResponse", new Class<?>[] {String.class}, boolean.class},
			{"canAddEntry", new Class<?>[] {List.class, ChatReplyEntry.class}, boolean.class},
			{"validReplacement", new Class<?>[] {List.class}, boolean.class},
			{"enabledInFolderTree", new Class<?>[] {geiler.addons.client.collections.FolderTree.class,
				java.util.Map.class, String.class}, boolean.class},
			{"normalizeUsernames", new Class<?>[] {List.class}, List.class},
			{"validUsernameList", new Class<?>[] {List.class}, boolean.class},
			{"resolveLegacyFilterEnabled", new Class<?>[] {Boolean.class, List.class}, boolean.class},
			{"senderAllowed", new Class<?>[] {String.class, boolean.class, List.class, boolean.class, List.class},
				boolean.class},
			{"remoteActionAllowed", new Class<?>[] {String.class, boolean.class, List.class, boolean.class, List.class},
				boolean.class},
			{"incomingChannelAllowed", new Class<?>[] {ChatChannel.class, boolean.class, boolean.class,
				boolean.class, boolean.class, boolean.class}, boolean.class},
		};
		try {
			for (Object[] entryPoint : entryPoints) {
				var method = ChatReplyConfigRules.class.getMethod((String) entryPoint[0], (Class<?>[]) entryPoint[1]);
				check(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
					&& method.getReturnType() == entryPoint[2],
					"reply configuration keeps public static Java entrypoint " + entryPoint[0]);
			}
			String[] names = {"MAX_ENTRIES", "MAX_COMMAND_LENGTH", "MAX_RESPONSE_LENGTH", "MAX_FILTER_NAMES"};
			int[] values = {16, 24, 160, 32};
			for (int index = 0; index < names.length; index++) {
				var field = ChatReplyConfigRules.class.getField(names[index]);
				check(Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers())
					&& Modifier.isFinal(field.getModifiers()) && field.getInt(null) == values[index],
					"reply configuration keeps its public static limit " + names[index]);
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("chat reply configuration Java entrypoint changed", exception);
		}
	}

	private static void replyCommandRulesKeepJavaEntrypoints() {
		Object[][] entryPoints = {
			{"parse", new Class<?>[] {String.class, String.class}, Optional.class},
			{"parse", new Class<?>[] {String.class, String.class, List.class}, Optional.class},
			{"parse", new Class<?>[] {String.class, String.class, List.class, List.class}, Optional.class},
			{"parseLocalAction", new Class<?>[] {String.class, String.class, List.class}, Optional.class},
			{"parseLocalPartyTransfer", new Class<?>[] {String.class, String.class}, Optional.class},
			{"isLocalHelpCommand", new Class<?>[] {String.class, String.class}, boolean.class},
			{"isLocalPartyTransferTrigger", new Class<?>[] {String.class, String.class}, boolean.class},
			{"incomingPrivateSender", new Class<?>[] {String.class}, Optional.class},
			{"outgoingWhisperTarget", new Class<?>[] {String.class}, Optional.class},
			{"privateReplyRecipientMatches", new Class<?>[] {ChatReplyCommandRules.Request.class, String.class}, boolean.class},
			{"remotePartyTransferAllowed", new Class<?>[] {ChatReplyCommandRules.Request.class,
				boolean.class, List.class, boolean.class, List.class}, boolean.class},
			{"remotePartyActionAllowed", new Class<?>[] {ChatReplyCommandRules.Request.class,
				boolean.class, List.class, boolean.class, List.class}, boolean.class},
			{"isPartyAction", new Class<?>[] {ChatReplyCommandRules.Command.class}, boolean.class},
			{"isRemotePartyAction", new Class<?>[] {ChatReplyCommandRules.Command.class}, boolean.class},
			{"requiresPartyLeader", new Class<?>[] {ChatReplyCommandRules.Command.class}, boolean.class},
			{"partyActionCommand", new Class<?>[] {ChatReplyCommandRules.Request.class,
				PartySnapshot.class, boolean.class, String.class}, Optional.class},
			{"normalizePrefix", new Class<?>[] {String.class}, String.class},
			{"partyTransferCommand", new Class<?>[] {ChatReplyCommandRules.Request.class,
				PartySnapshot.class, boolean.class, String.class}, Optional.class},
			{"helpText", new Class<?>[] {String.class, ChatChannel.class}, String.class},
			{"helpText", new Class<?>[] {String.class, ChatChannel.class, List.class}, String.class},
			{"helpLines", new Class<?>[] {String.class, ChatChannel.class, List.class}, List.class},
			{"senderAllowed", new Class<?>[] {String.class, boolean.class, List.class, boolean.class, List.class}, boolean.class},
			{"isBuiltInName", new Class<?>[] {String.class}, boolean.class},
			{"coordinateText", new Class<?>[] {int.class, int.class, int.class}, String.class},
			{"lobbyLocationText", new Class<?>[] {String.class}, String.class},
			{"replyCommand", new Class<?>[] {ChatChannel.class, String.class}, String.class},
		};
		try {
			for (Object[] entryPoint : entryPoints) {
				var method = ChatReplyCommandRules.class.getMethod((String) entryPoint[0],
					(Class<?>[]) entryPoint[1]);
				check(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
					&& method.getReturnType() == entryPoint[2],
					"reply command rules keep public static Java entrypoint " + entryPoint[0]);
			}
			check(Arrays.stream(ChatReplyCommandRules.Command.values()).map(Enum::name).toList().equals(List.of(
				"HELP", "COORDS", "ODIN_LINK", "COIN_FLIP", "EIGHT_BALL", "DICE", "PING", "TPS", "FPS",
				"TIME", "LOBBY", "HOLDING", "PARTY_TRANSFER", "PARTY_WARP", "PARTY_ALLINVITE", "PARTY_BOOP",
				"PARTY_KICK", "PARTY_PROMOTE", "PARTY_DEMOTE", "PARTY_KICK_OFFLINE", "PARTY_QUEUE_INSTANCE",
				"PRIVATE_INVITE", "PARTY_DOWNTIME", "PARTY_UNDOWNTIME", "PARTY_REINVITE", "CUSTOM_REPLY")),
				"reply command enum names and declaration order remain stable");
			check(!ChatReplyCommandRules.LocalPartyTransfer.class.isRecord()
				&& !ChatReplyCommandRules.LocalActionRequest.class.isRecord()
				&& !ChatReplyCommandRules.Request.class.isRecord(),
				"Kotlin reply request values keep their Java constructors and accessors without record metadata");
			ChatReplyCommandRules.LocalPartyTransfer.class.getConstructor(String.class);
			check(ChatReplyCommandRules.LocalPartyTransfer.class.getMethod("target").getReturnType() == String.class,
				"local transfer value keeps its String target accessor");
			ChatReplyCommandRules.LocalActionRequest.class.getConstructor(ChatReplyEntry.class, String.class);
			check(ChatReplyCommandRules.LocalActionRequest.class.getMethod("entry").getReturnType() == ChatReplyEntry.class
				&& ChatReplyCommandRules.LocalActionRequest.class.getMethod("arguments").getReturnType() == String.class,
				"local action value keeps both component accessor types");
			ChatReplyCommandRules.Request.class.getConstructor(ChatChannel.class, String.class,
				ChatReplyCommandRules.Command.class, String.class, String.class, ChatChannel.class);
			Class<?>[] accessorTypes = {ChatChannel.class, String.class, ChatReplyCommandRules.Command.class,
				String.class, String.class, ChatChannel.class};
			String[] accessors = {"channel", "sender", "command", "argument", "response", "replyChannel"};
			for (int index = 0; index < accessors.length; index++)
				check(ChatReplyCommandRules.Request.class.getMethod(accessors[index]).getReturnType() == accessorTypes[index],
					"incoming request keeps component accessor " + accessors[index]);
			for (Class<?> valueType : List.of(ChatReplyCommandRules.LocalPartyTransfer.class,
				ChatReplyCommandRules.LocalActionRequest.class, ChatReplyCommandRules.Request.class)) {
				check(Modifier.isFinal(valueType.getModifiers()), "reply request values remain final");
				for (var field : valueType.getDeclaredFields()) {
					if (!Modifier.isStatic(field.getModifiers()))
						check(Modifier.isFinal(field.getModifiers()), "reply request state remains immutable: " + field.getName());
				}
			}
			var localTransfer = new ChatReplyCommandRules.LocalPartyTransfer("Bob");
			check(localTransfer.equals(new ChatReplyCommandRules.LocalPartyTransfer("Bob"))
				&& localTransfer.hashCode() == new ChatReplyCommandRules.LocalPartyTransfer("Bob").hashCode()
				&& !localTransfer.equals(new ChatReplyCommandRules.LocalPartyTransfer("Alice")),
				"local transfer value keeps component-based equality and hashing");
			equals("LocalPartyTransfer[target=Bob]", localTransfer.toString(),
				"local transfer value keeps its prior Java record-style string form");
			ChatReplyEntry actionEntry = new ChatReplyEntry("hello", "Hi");
			ChatReplyEntry equalActionEntry = new ChatReplyEntry(actionEntry.id(), actionEntry.command(),
				actionEntry.mode(), actionEntry.response(), actionEntry.actionTemplate(),
				actionEntry.folderId(), actionEntry.enabled());
			var localAction = new ChatReplyCommandRules.LocalActionRequest(actionEntry, "there");
			var equalLocalAction = new ChatReplyCommandRules.LocalActionRequest(equalActionEntry, "there");
			check(localAction.entry() == actionEntry && localAction.arguments().equals("there")
				&& localAction.equals(equalLocalAction)
				&& localAction.hashCode() == equalLocalAction.hashCode(),
				"local action value keeps its accessors, equality, and hashing");
			equals("there", localAction.arguments(), "local action value keeps its Java accessor");
			equals("LocalActionRequest[entry=null, arguments=null]",
				new ChatReplyCommandRules.LocalActionRequest(null, null).toString(),
				"local action value keeps its prior Java record-style string with null components");
			var valueRequest = new ChatReplyCommandRules.Request(ChatChannel.PARTY, "Alice",
				ChatReplyCommandRules.Command.HELP, "Bob", "Hi", ChatChannel.GUILD);
			var equalRequest = new ChatReplyCommandRules.Request(ChatChannel.PARTY, "Alice",
				ChatReplyCommandRules.Command.HELP, "Bob", "Hi", ChatChannel.GUILD);
			check(valueRequest.equals(equalRequest) && valueRequest.hashCode() == equalRequest.hashCode()
				&& !valueRequest.equals(new ChatReplyCommandRules.Request(ChatChannel.PARTY, "Alice",
					ChatReplyCommandRules.Command.HELP, "Bob", "Hi", ChatChannel.PARTY)),
				"incoming request value keeps component-based equality and hashing across every field");
			equals("Request[channel=ChatChannel[id=party, name=Party, commandPrefix=/pc, custom=false], " +
				"sender=Alice, command=HELP, argument=Bob, response=Hi, " +
				"replyChannel=ChatChannel[id=guild, name=Guild, commandPrefix=/gc, custom=false]]",
				valueRequest.toString(), "incoming request value keeps its prior Java record-style string form");
			var legacyConstructor = ChatReplyCommandRules.Request.class.getConstructor(ChatChannel.class,
				String.class, ChatReplyCommandRules.Command.class, String.class, String.class);
			var request = (ChatReplyCommandRules.Request) legacyConstructor.newInstance(ChatChannel.PARTY,
				"Alice", ChatReplyCommandRules.Command.HELP, null, null);
			check(request.replyChannel() == ChatChannel.PARTY,
				"the five-argument request constructor still defaults its reply channel");
			check(new ChatReplyCommandRules.Request(ChatChannel.PARTY, "Alice",
				ChatReplyCommandRules.Command.HELP, null, null, null).replyChannel() == ChatChannel.PARTY,
				"the canonical request constructor still defaults a null reply channel to its source channel");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("chat reply command rules Java entrypoint changed", exception);
		}
	}

	private static void replyCommandRulesPreserveNullAndWhitespaceSemantics() {
		check(ChatReplyCommandRules.parse(null, "!").isEmpty()
			&& ChatReplyCommandRules.parse("Party > Alice: !help", null).isEmpty()
			&& ChatReplyCommandRules.parse("Party > Alice: !help", "!", null, null).isPresent(),
			"reply parsing preserves null input and optional-list behavior");
		check(ChatReplyCommandRules.parseLocalAction(null, "!", null).isEmpty()
			&& ChatReplyCommandRules.parseLocalPartyTransfer(null, "!").isEmpty()
			&& !ChatReplyCommandRules.isLocalHelpCommand(null, "!")
			&& !ChatReplyCommandRules.isLocalPartyTransferTrigger(null, "!")
			&& ChatReplyCommandRules.incomingPrivateSender(null).isEmpty()
			&& ChatReplyCommandRules.outgoingWhisperTarget(null).isEmpty()
			&& ChatReplyCommandRules.normalizePrefix(null) == null,
			"local parsers and sender tracking preserve their Java null fallbacks");
		check(ChatReplyCommandRules.helpText(null, ChatChannel.PARTY) == null
			&& ChatReplyCommandRules.helpLines("!", null, null) == null
			&& ChatReplyCommandRules.partyTransferCommand(null, null, false, null).isEmpty()
			&& !ChatReplyCommandRules.remotePartyTransferAllowed(null, false, null, false, null)
			&& !ChatReplyCommandRules.privateReplyRecipientMatches(null, "Alice")
			&& ChatReplyCommandRules.replyCommand(null, "hello") == null
			&& ChatReplyCommandRules.replyCommand(ChatChannel.PARTY, null) == null,
			"routing helpers preserve their Java null fallbacks");
		var nonPrivateNullRequest = new ChatReplyCommandRules.Request(null, null, null, null, null);
		check(ChatReplyCommandRules.privateReplyRecipientMatches(nonPrivateNullRequest, null),
			"non-private null request fields retain the source Java short-circuit result");
		var privateNullSender = new ChatReplyCommandRules.Request(ChatChannel.PRIVATE, null,
			ChatReplyCommandRules.Command.HELP, null, null);
		check(!ChatReplyCommandRules.privateReplyRecipientMatches(privateNullSender, null),
			"a null observed private counterpart short-circuits before sender comparison");
		try {
			ChatReplyCommandRules.privateReplyRecipientMatches(privateNullSender, "Alice");
			throw new AssertionError("private recipient comparison should preserve the Java null-sender failure");
		} catch (NullPointerException expected) {
			// The frozen Java implementation dereferences request.sender() in this branch.
		}
		check(ChatReplyCommandRules.normalizePrefix("\u2003!") == null
			&& "\u00a0!".equals(ChatReplyCommandRules.normalizePrefix("\u00a0!")),
			"prefix normalization preserves Java trim and Character.isWhitespace distinctions");
		check(ChatReplyCommandRules.isLocalHelpCommand("\u2003!help", "!")
			&& !ChatReplyCommandRules.isLocalHelpCommand("\u00a0!help", "!")
			&& ChatReplyCommandRules.parse("Party > Alice: \u00a0!help", "!").isPresent(),
			"local leading stripping and incoming chat stripping keep their distinct Unicode whitespace rules");
		equals("pc \u00a0", ChatReplyCommandRules.replyCommand(ChatChannel.PARTY, "\u00a0"),
			"reply routing preserves Java String.isBlank treatment of non-breaking space");
	}

	private static void replyEntryKeepsJavaValueAbi() {
		try {
			check(!ChatReplyEntry.class.isRecord() && Modifier.isFinal(ChatReplyEntry.class.getModifiers()),
				"saved reply entry remains an immutable final Kotlin value type");
			ChatReplyEntry.class.getConstructor(String.class, String.class);
			ChatReplyEntry.class.getConstructor(String.class, String.class, String.class);
			ChatReplyEntry.class.getConstructor(String.class, String.class, String.class, String.class, boolean.class);
			ChatReplyEntry.class.getConstructor(String.class, String.class, String.class, String.class, boolean.class,
				String.class);
			ChatReplyEntry.class.getConstructor(String.class, String.class, ChatReplyEntry.Mode.class, String.class,
				String.class, String.class, boolean.class);
			check(ChatReplyEntry.Mode.values().length == 2
				&& ChatReplyEntry.Mode.values()[0] == ChatReplyEntry.Mode.TEXT_REPLY
				&& ChatReplyEntry.Mode.values()[1] == ChatReplyEntry.Mode.LOCAL_ACTION,
				"saved reply modes keep their names and declaration order");
			check(ChatReplyEntry.class.getMethod("isValid").getReturnType() == boolean.class,
				"saved reply entry keeps its public validation method");
			Class<?>[] accessorTypes = {String.class, String.class, ChatReplyEntry.Mode.class, String.class,
				String.class, String.class, boolean.class};
			String[] accessors = {"id", "command", "mode", "response", "actionTemplate", "folderId", "enabled"};
			for (int index = 0; index < accessors.length; index++)
				check(ChatReplyEntry.class.getMethod(accessors[index]).getReturnType() == accessorTypes[index],
					"saved reply entry keeps accessor " + accessors[index]);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("chat reply entry Java value ABI changed", exception);
		}
	}

	private static void replyEntryNormalizationAndValidationStayKotlin() {
		ChatReplyEntry text = new ChatReplyEntry("  row-id  ", "  HELLO  ", "  Hi there!  ", "  folder-a  ", true);
		equals("row-id", text.id(), "reply entry trims its stable id with Java trim semantics");
		equals("hello", text.command(), "reply entry trims and root-lowercases its command");
		equals(ChatReplyEntry.Mode.TEXT_REPLY, text.mode(), "legacy saved text rows remain text replies");
		equals("Hi there!", text.response(), "text reply payload is trimmed");
		equals("folder-a", text.folderId(), "reply entry trims its folder id");
		check(text.isValid(), "normalized plain-text reply remains valid");
		ChatReplyEntry sameText = new ChatReplyEntry("row-id", "hello", ChatReplyEntry.Mode.TEXT_REPLY,
			"Hi there!", "", "folder-a", true);
		check(text.equals(sameText) && text.hashCode() == sameText.hashCode()
			&& !text.equals(new ChatReplyEntry("other-row", "hello", ChatReplyEntry.Mode.TEXT_REPLY,
				"Hi there!", "", "folder-a", true)),
			"saved reply entries retain component-based equality and matching hashes");
		equals("ChatReplyEntry[id=row-id, command=hello, mode=TEXT_REPLY, response=Hi there!, actionTemplate=, "
			+ "folderId=folder-a, enabled=true]", text.toString(),
			"saved reply entries retain the prior record-shaped diagnostic string");
		ChatReplyEntry nullFields = new ChatReplyEntry(null, null, null, null, null, null, false);
		try {
			java.util.UUID.fromString(nullFields.id());
		} catch (IllegalArgumentException invalidId) {
			throw new AssertionError("null saved reply IDs still receive a generated UUID", invalidId);
		}
		equals("", nullFields.command(), "null commands normalize to empty strings");
		equals(ChatReplyEntry.Mode.TEXT_REPLY, nullFields.mode(), "null modes default to plain text replies");
		equals("", nullFields.response(), "null responses normalize to empty strings");
		equals("", nullFields.actionTemplate(), "null action templates normalize to empty strings");
		equals(null, nullFields.folderId(), "null folder IDs remain null");
		check(!nullFields.enabled(), "saved reply enabled state remains independent of normalization");

		ChatReplyEntry draft = new ChatReplyEntry("draft", "note", ChatReplyEntry.Mode.TEXT_REPLY,
			"  saved text  ", "  dormant template  ", " folder-b ", true);
		equals("saved text", draft.response(), "active text payload remains trimmed");
		equals("  dormant template  ", draft.actionTemplate(),
			"inactive action payload remains an unchanged dormant draft");
		equals("folder-b", draft.folderId(), "canonical constructor still normalizes folder ids");
		check(draft.isValid(), "dormant action text does not block a valid text reply");

		ChatReplyEntry localAction = new ChatReplyEntry("pt", "", "  /party transfer {args}  ");
		equals(ChatReplyEntry.Mode.LOCAL_ACTION, localAction.mode(),
			"legacy action constructor still infers local-action mode");
		equals("/party transfer {args}", localAction.actionTemplate(), "active action template is normalized");
		check(localAction.isValid(), "normalized local action remains valid");

		ChatReplyEntry unicodeWhitespace = new ChatReplyEntry("unicode", "\u2003hello\u2003",
			ChatReplyEntry.Mode.TEXT_REPLY, "reply", "", null, true);
		equals("\u2003hello\u2003", unicodeWhitespace.command(),
			"command normalization preserves Java trim behavior for non-ASCII whitespace");
		check(!unicodeWhitespace.isValid(), "non-ASCII whitespace does not become a valid command through normalization");

		String nonBreakingSpace = "\u00a0";
		ChatReplyEntry nonBreakingSpaceFields = new ChatReplyEntry(nonBreakingSpace, "hello",
			ChatReplyEntry.Mode.TEXT_REPLY, "reply", "", nonBreakingSpace, true);
		equals(nonBreakingSpace, nonBreakingSpaceFields.id(), "NBSP IDs retain Java String.isBlank behavior");
		equals(nonBreakingSpace, nonBreakingSpaceFields.folderId(),
			"NBSP folder IDs retain Java String.isBlank behavior");
		check(nonBreakingSpaceFields.isValid(), "NBSP IDs remain valid under Java String.isBlank semantics");
		ChatReplyEntry nonBreakingSpaceAction = new ChatReplyEntry("nbsp-action", "hello", nonBreakingSpace);
		equals(ChatReplyEntry.Mode.LOCAL_ACTION, nonBreakingSpaceAction.mode(),
			"a non-blank Java action template still selects Local Action mode");
	}

	private static void backendKeepsJavaEntrypoints() {
		Object[][] entryPoints = {
			{"onIncoming", new Class<?>[] {String.class, boolean.class, long.class}, void.class},
			{"parseIncomingRequest", new Class<?>[] {String.class, String.class, List.class, List.class}, Optional.class},
			{"tick", new Class<?>[] {net.minecraft.client.Minecraft.class, long.class}, void.class},
			{"clear", new Class<?>[] {}, void.class},
			{"onOutgoingCommand", new Class<?>[] {String.class}, void.class},
			{"handleLocalPartyTransfer", new Class<?>[] {String.class}, boolean.class},
			{"handleLocalCustomAction", new Class<?>[] {String.class}, boolean.class},
		};
		try {
			for (Object[] entryPoint : entryPoints) {
				var method = ChatCommandReplyBackend.class.getMethod((String) entryPoint[0],
					(Class<?>[]) entryPoint[1]);
				check(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
					&& method.getReturnType() == entryPoint[2],
					"chat command backend keeps public static Java entrypoint " + entryPoint[0]);
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("chat command backend Java entrypoint changed", exception);
		}
	}

	private static void parsesSupportedAliasesAndChannels() {
		var help = ChatReplyCommandRules.parse("Party > [MVP+] Alice: !help", "!").orElseThrow();
		equals(ChatChannel.PARTY, help.channel(), "party request keeps its channel");
		equals("Alice", help.sender(), "party request extracts sender after rank prefix");
		equals(ChatReplyCommandRules.Command.HELP, help.command(), "help alias is parsed");
		equals(ChatReplyCommandRules.Command.HELP,
			ChatReplyCommandRules.parse("Guild > Bob: !H", "!").orElseThrow().command(), "short help alias is case insensitive");
		equals(ChatReplyCommandRules.Command.COORDS,
			ChatReplyCommandRules.parse("Co-op > Carol: !co", "!").orElseThrow().command(), "coordinate alias parses");
		equals(ChatChannel.PRIVATE,
			ChatReplyCommandRules.parse("From [VIP] Dave: !coords", "!").orElseThrow().channel(),
			"incoming private messages keep their channel");
		equals("$help", ChatReplyCommandRules.normalizePrefix(" $ ") + "help", "prefix settings are trimmed");
		equals(ChatReplyCommandRules.Command.HELP,
			ChatReplyCommandRules.parse("Party > Alice: $help", "$ ").orElseThrow().command(),
			"custom prefix is used by the parser");
		check(ChatReplyCommandRules.isLocalHelpCommand("  $help  ", "$"),
			"local help uses the configured prefix and accepts surrounding spaces");
		check(ChatReplyCommandRules.parse("Party > Alice: \u2003!help", "!").isPresent()
			&& ChatReplyCommandRules.parse("Party > Alice: \u00a0!help", "!").isPresent(),
			"incoming chat normalizes em space and NBSP before command parsing");
		check(ChatReplyCommandRules.isLocalHelpCommand("\u2003!help", "!")
			&& !ChatReplyCommandRules.isLocalHelpCommand("\u00a0!help", "!"),
			"local command parsing retains Java stripLeading's Character.isWhitespace behavior");
		check(ChatReplyCommandRules.isLocalHelpCommand("$H", "$"),
			"local help accepts the short alias case-insensitively");
		check(!ChatReplyCommandRules.isLocalHelpCommand("!help", "$"),
			"local help no longer treats the default prefix as active after it changes");
		check(!ChatReplyCommandRules.isLocalHelpCommand("$help now", "$"),
			"local help rejects extra tokens");
		check(ChatReplyCommandRules.parse("Officer > Alice: !help", "!").isEmpty(),
			"officer messages are not routed through the Guild reply channel");
		check(ChatReplyCommandRules.parse("To Alice: !help", "!").isEmpty(), "outgoing private echoes are ignored");
		equals(Optional.of("Dave"), ChatReplyCommandRules.incomingPrivateSender("From [VIP] Dave: hello"),
			"incoming private sender can be tracked for a delayed /r reply");
		equals(Optional.of("Bob"), ChatReplyCommandRules.outgoingWhisperTarget("/msg Bob hello"),
			"explicit outgoing whispers update the tracked /r counterpart");
		check(ChatReplyCommandRules.outgoingWhisperTarget("r hello").isEmpty(),
			"/r reply text does not replace the tracked counterpart");
		var privateRequest = ChatReplyCommandRules.parse("From Dave: !help", "!").orElseThrow();
		check(ChatReplyCommandRules.privateReplyRecipientMatches(privateRequest, "Dave"),
			"delayed private reply remains eligible while the tracked sender is unchanged");
		check(ChatReplyCommandRules.privateReplyRecipientMatches(privateRequest, "dAVE"),
			"delayed private reply recipient comparison remains case-insensitive");
		check(!ChatReplyCommandRules.privateReplyRecipientMatches(privateRequest, "Bob")
			&& !ChatReplyCommandRules.privateReplyRecipientMatches(privateRequest, null),
			"delayed private reply is dropped when its target changed or is unknown");
		check(ChatReplyCommandRules.parse("Party > Alice: !help now", "!").isEmpty(), "help rejects extra tokens");
		var lobby = ChatReplyCommandRules.parse("Guild > Alice: !lobby", "!").orElseThrow();
		check(lobby.command() == ChatReplyCommandRules.Command.LOBBY && lobby.argument() == null,
			"!lobby is a no-argument location query in an enabled reply channel");
		check(ChatReplyCommandRules.parse("Party > Alice: !lobby 1", "!").isEmpty(),
			"!lobby rejects extra arguments");
		check(ChatReplyCommandRules.parse("Guild > Alice: !pt Bob", "!").isEmpty(), "party transfer is party-only");
		check(ChatReplyCommandRules.parse("Party > Alice: !pt Bob; /op me", "!").isEmpty(),
			"transfer target rejects command-like injected text");
		var implicitTransfer = ChatReplyCommandRules.parse("Party > Alice: !pt", "!").orElseThrow();
		equals("Alice", implicitTransfer.argument(), "targetless !pt defaults to the requesting party member");
		var implicitPtme = ChatReplyCommandRules.parse("Party > Alice: !ptme", "!").orElseThrow();
		equals("Alice", implicitPtme.argument(), "targetless !ptme defaults to the requesting party member");
		var implicitTransferAlias = ChatReplyCommandRules.parse("Party > Alice: !transfer", "!").orElseThrow();
		equals("Alice", implicitTransferAlias.argument(), "targetless !transfer defaults to the requesting party member");
		equals(new ChatReplyCommandRules.LocalPartyTransfer("Bob"),
			ChatReplyCommandRules.parseLocalPartyTransfer("!pt Bob", "!").orElseThrow(),
			"self-typed transfer parses locally before chat submission");
		equals(new ChatReplyCommandRules.LocalPartyTransfer("Alice"),
			ChatReplyCommandRules.parseLocalPartyTransfer(" $transfer Alice ", "$ ").orElseThrow(),
			"self-typed transfer honors the configured prefix and alias");
		check(ChatReplyCommandRules.isLocalPartyTransferTrigger("!pt invalid;command", "!"),
			"malformed local transfer attempts are recognized for local handling");
		check(ChatReplyCommandRules.parseLocalPartyTransfer("!pt invalid;command", "!").isEmpty(),
			"local transfer rejects command-like target injection");
		check(ChatReplyCommandRules.parse("Party > Alice: !anything", "!").isEmpty(), "unknown aliases are ignored");
		check(ChatReplyCommandRules.normalizePrefix("/") == null, "slash is not accepted as a chat trigger prefix");
		check(ChatReplyCommandRules.normalizePrefix("   ") == null, "blank prefixes disable inbound command parsing");

		String partyHelp = ChatReplyCommandRules.helpText("!", ChatChannel.PARTY);
		check(partyHelp.contains("!help/!h") && partyHelp.contains("!coords/!co") && partyHelp.contains("!lobby")
			&& partyHelp.contains("!pt [username] (Party leader)")
			&& partyHelp.contains("!ptme [username] (Party leader)")
			&& partyHelp.contains("!transfer [username] (Party leader)"),
			"help lists all transfer aliases and describes their leader and allowlist gates");
		check(ChatReplyCommandRules.helpText("!", ChatChannel.GUILD).contains("!pt [username] (Party leader)"),
			"the shared help listing advertises Party-only commands consistently in every channel");
		equals("pc hello", ChatReplyCommandRules.replyCommand(ChatChannel.PARTY, "hello"), "party reply route");
		equals("gc hello", ChatReplyCommandRules.replyCommand(ChatChannel.GUILD, "hello"), "guild reply route");
		equals("cc hello", ChatReplyCommandRules.replyCommand(ChatChannel.COOP, "hello"), "co-op reply route");
		equals("r hello", ChatReplyCommandRules.replyCommand(ChatChannel.PRIVATE, "hello"), "private reply route");
		equals(null, ChatReplyCommandRules.replyCommand(ChatChannel.VANILLA_SYSTEM, "hello"),
			"system messages cannot receive command replies");
		equals(null, ChatReplyCommandRules.replyCommand(ChatChannel.PARTY, "x".repeat(221)),
			"reply routing rejects text beyond its safe channel message bound");
		equals(null, ChatReplyCommandRules.replyCommand(ChatChannel.PARTY, "unsafe\u2028line"),
			"reply routing rejects hidden line separators");
	}

	private static void parsesLowRiskOdinRepliesAndCustomEntries() {
		for (String command : List.of("odin", "od", "cf", "8ball", "dice", "ping", "tps", "fps", "time", "lobby", "location", "holding")) {
			check(ChatReplyCommandRules.parse("Guild > Alice: !" + command, "!").isPresent(),
				"supported low-risk Odin chat reply parses: " + command);
		}
		for (String excluded : List.of("racism", "invite", "inv")) {
			check(ChatReplyCommandRules.parse("Party > Alice: !" + excluded, "!").isEmpty(),
				"unported or unrelated Odin command stays excluded: " + excluded);
			if (excluded.equals("invite") || excluded.equals("inv")) continue; // private-only presets now exist
			check(ChatReplyCommandRules.parse("From Alice: !" + excluded, "!").isEmpty(),
				"excluded Odin command is not enabled in private replies either: " + excluded);
		}
		for (String alias : List.of("pt", "ptme", "transfer")) {
			check(ChatReplyCommandRules.parse("Party > Alice: !" + alias + " Bob", "!").isPresent(),
				"explicit transfer alias is accepted: " + alias);
		}
		check(ChatReplyCommandRules.isBuiltInName("ptme"), "custom replies cannot shadow the ptme alias");
		for (String command : List.of("warp", "w", "allinvite", "allinv", "boop", "kick", "k", "promote",
			"demote", "kickoffline", "ko", "f1", "f2", "f3", "f4", "f5", "f6", "f7",
			"m1", "m2", "m3", "m4", "m5", "m6", "m7", "t1", "t2", "t3", "t4", "t5")) {
			check(ChatReplyCommandRules.isBuiltInName(command), "custom replies cannot shadow Odin party command: " + command);
		}
		ChatReplyEntry custom = new ChatReplyEntry("hello", "Hi there!");
		var request = ChatReplyCommandRules.parse("Co-op > Carol: !HELLO", "!", List.of(custom)).orElseThrow();
		equals(ChatReplyCommandRules.Command.CUSTOM_REPLY, request.command(), "custom command matches case-insensitively");
		equals("Hi there!", request.response(), "custom command carries only its saved plain-text reply");
		check(ChatReplyCommandRules.parse("Party > Alice: !hello extra", "!", List.of(custom)).isEmpty(),
			"custom plain-text commands reject extra tokens");
		check(ChatReplyCommandRules.parse("Party > Alice: !hello", "!",
			List.of(new ChatReplyEntry("hello", "!hello"))).isEmpty(),
			"custom reply starting with the active prefix cannot loop");
		ChatReplyEntry localAction = new ChatReplyEntry("pt", "", "/party transfer {args}");
		equals(ChatReplyEntry.Mode.LOCAL_ACTION, localAction.mode(), "compatibility constructor selects local-action mode");
		check(localAction.isValid(), "a reserved party alias is usable only as an explicit local action");
		var parsedLocalAction = ChatReplyCommandRules.parseLocalAction("!pt Bob", "!", List.of(localAction)).orElseThrow();
		equals("Bob", parsedLocalAction.arguments(), "local action parses only the user's command arguments");
		equals("party transfer Bob", ChatActionTemplateRules.expand(localAction.actionTemplate(),
			parsedLocalAction.arguments()).orElseThrow(), "the local action expands to one slashless server command");
		check(ChatReplyCommandRules.parse("Party > Alice: !pt Bob", "!", List.of(localAction)).orElseThrow().command()
			== ChatReplyCommandRules.Command.PARTY_TRANSFER,
			"received chat keeps built-in behavior and never dispatches a custom local action template");
		check(ChatReplyCommandRules.parse("Party > Alice: !hello", "!",
			List.of(new ChatReplyEntry("hello", "", "/op {args}"))).isEmpty(),
			"received chat cannot invoke a locally configured action template");
		check(ChatActionTemplateRules.expand("/party transfer {args}", "Bob; /op Self").isEmpty()
			&& ChatActionTemplateRules.expand("/party transfer {args}", "Bob\n/op Self").isEmpty()
			&& ChatActionTemplateRules.expand("/party transfer {args}", "Bob\nSelf").isEmpty()
			&& ChatActionTemplateRules.expand("/party transfer {args}", "/op Self").isEmpty(),
			"action arguments reject command separators, line breaks, and nested command prefixes");
		var malformedLocalAction = ChatReplyCommandRules.parseLocalAction("!pt\nBob", "!", List.of(localAction)).orElseThrow();
		check(ChatActionTemplateRules.expand(localAction.actionTemplate(), malformedLocalAction.arguments()).isEmpty(),
			"a newline in the locally typed action input is rejected before expansion");
		check(!new ChatReplyEntry("local", "", "/party transfer {args}\n/op Self").isValid(),
			"action templates reject injected line breaks");
		legacyReplyMigrationPreservesDormantPayloads();
		check(!ChatActionTemplateRules.isValid("party transfer {args}")
			&& !ChatActionTemplateRules.isValid("/party transfer")
			&& !ChatActionTemplateRules.isValid("/party transfer {args} {args}"),
			"action templates require one slash-prefixed command and exactly one {args} placeholder");
		check(!ChatReplyConfigRules.validReplacement(List.of(custom,
			new ChatReplyEntry(custom.id(), "other", "duplicate id", null, true))),
			"flat reply replacements retain unique stable ids");
		check(ChatReplyConfigRules.validReplacement(List.of(custom,
			new ChatReplyEntry("other-id", "other", "reply", null, true))),
			"editable reply replacements allow distinct commands and ids");
		String help = ChatReplyCommandRules.helpText("!", ChatChannel.PARTY, List.of(custom));
		check(help.contains("!hello") && help.contains("!tps"), "dynamic help includes active custom entries and TPS");
		check(ChatReplyCommandRules.helpText("!", ChatChannel.ALL, List.of(custom))
			.contains("!pt [username] (Party leader)")
			&& ChatReplyCommandRules.helpText("!", ChatChannel.ALL, List.of(custom)).contains("!hello"),
			"local All help includes enabled custom commands and the Party-only built-in action");
		check(!ChatReplyCommandRules.helpText("!", ChatChannel.PARTY,
			List.of(new ChatReplyEntry("disabled", "hidden", "reply", null, false))).contains("!hidden"),
			"disabled custom entries are omitted from help");
		check(ChatReplyCommandRules.parse("Party > Alice: !disabled", "!",
			List.of(new ChatReplyEntry("disabled", "disabled", "reply", null, false))).isEmpty(),
			"disabled custom entries do not answer incoming commands");
		var fullHelp = ChatReplyCommandRules.helpLines("!!!!", ChatChannel.PARTY, java.util.stream.IntStream.range(0, 16)
			.mapToObj(index -> new ChatReplyEntry("custom" + index, "reply")).toList());
		check(fullHelp.stream().allMatch(line -> line.length() <= 170)
			&& fullHelp.stream().anyMatch(line -> line.contains("!!!!custom15")),
			"help splits safely and retains every enabled custom command at the maximum entry count");
		check(ChatReplyCommandRules.isBuiltInName("co") && ChatReplyCommandRules.isBuiltInName("pt"),
			"custom names cannot shadow built-in aliases");
	}

	private static void parsesOdinPartyActionAliases() {
		for (String command : List.of("warp", "w"))
			equals(ChatReplyCommandRules.Command.PARTY_WARP,
				ChatReplyCommandRules.parse("Party > Alice: !" + command, "!").orElseThrow().command(),
				"Odin party warp alias parses");
		for (String command : List.of("allinvite", "allinv"))
			equals(ChatReplyCommandRules.Command.PARTY_ALLINVITE,
				ChatReplyCommandRules.parse("Party > Alice: !" + command, "!").orElseThrow().command(),
				"Odin allinvite alias parses");
		equals("Alice", ChatReplyCommandRules.parse("Party > Alice: !boop", "!").orElseThrow().argument(),
			"boop defaults to the requesting party member");
		equals("Bob", ChatReplyCommandRules.parse("Party > Alice: !boop Bob", "!").orElseThrow().argument(),
			"boop accepts one validated target");
		for (String command : List.of("kick", "k", "promote", "demote")) {
			var parsed = ChatReplyCommandRules.parse("Party > Alice: !" + command, "!").orElseThrow();
			equals("Alice", parsed.argument(), "targetless party action defaults to its requester: " + command);
		}
		equals(ChatReplyCommandRules.Command.PARTY_KICK_OFFLINE,
			ChatReplyCommandRules.parse("Party > Alice: !ko", "!").orElseThrow().command(),
			"offline kick alias parses");
		for (String command : List.of("f1", "f7", "m1", "m7", "t1", "t5")) {
			var parsed = ChatReplyCommandRules.parse("Party > Alice: !" + command, "!").orElseThrow();
			equals(ChatReplyCommandRules.Command.PARTY_QUEUE_INSTANCE, parsed.command(),
				"Odin queue command parses as a fixed party action: " + command);
			equals(command, parsed.argument(), "queue action stores only its allowlisted code");
		}
		for (String command : List.of("warp", "allinvite", "boop", "kick", "promote", "demote", "kickoffline", "f7")) {
			check(ChatReplyCommandRules.parse("Guild > Alice: !" + command, "!").isEmpty(),
				"party actions are rejected outside party chat: " + command);
		}
		check(ChatReplyCommandRules.parse("Party > Alice: !warp now", "!").isEmpty()
			&& ChatReplyCommandRules.parse("Party > Alice: !f7 now", "!").isEmpty()
			&& ChatReplyCommandRules.parse("Party > Alice: !kick Bob; /op me", "!").isEmpty(),
			"fixed party commands reject extra or command-injection arguments");
		check(ChatReplyCommandRules.parse("Party > Alice: !boop a-name-too-long-123456", "!").isEmpty(),
			"party action target syntax remains bounded to a username");
	}

	private static void legacyReplyMigrationPreservesDormantPayloads() {
		ChatChannelsLocalStore.ReplyData legacyBoth = new ChatChannelsLocalStore.ReplyData();
		legacyBoth.command = "legacy_both";
		legacyBoth.response = "kept as a text reply";
		legacyBoth.actionTemplate = "/party transfer {args}";
		ChatReplyEntry migratedBoth = legacyBoth.toEntry();
		equals(ChatReplyEntry.Mode.TEXT_REPLY, migratedBoth.mode(),
			"legacy rows containing both payloads select non-executing Text Reply mode");
		equals("kept as a text reply", migratedBoth.response(), "legacy reply text survives migration");
		equals("/party transfer {args}", migratedBoth.actionTemplate(),
			"legacy local-action payload survives as a dormant template");
		check(migratedBoth.isValid(), "valid reply remains usable when it has a dormant action template");
		check(ChatReplyCommandRules.parseLocalAction("!legacy_both Bob", "!", List.of(migratedBoth)).isEmpty(),
			"a dormant legacy template cannot execute while Text Reply mode is selected");
		var incoming = ChatReplyCommandRules.parse("Party > Alice: !legacy_both", "!", List.of(migratedBoth))
			.orElseThrow();
		equals("kept as a text reply", incoming.response(), "incoming parser uses only the selected text payload");

		ChatChannelsLocalStore.ReplyData savedBoth = ChatChannelsLocalStore.ReplyData.from(migratedBoth);
		ChatReplyEntry reloadedBoth = savedBoth.toEntry();
		equals(ChatReplyEntry.Mode.TEXT_REPLY, reloadedBoth.mode(), "save/reload retains selected legacy mode");
		equals("kept as a text reply", reloadedBoth.response(), "save/reload retains active reply text");
		equals("/party transfer {args}", reloadedBoth.actionTemplate(),
			"save/reload retains the dormant local-action template");

		ChatChannelsLocalStore.ReplyData legacyActionOnly = new ChatChannelsLocalStore.ReplyData();
		legacyActionOnly.command = "legacy_action";
		legacyActionOnly.actionTemplate = "/party transfer {args}";
		ChatReplyEntry migratedActionOnly = legacyActionOnly.toEntry();
		equals(ChatReplyEntry.Mode.LOCAL_ACTION, migratedActionOnly.mode(),
			"legacy rows with only an action template migrate to Local Action");
		equals("", migratedActionOnly.response(), "action-only migration retains an empty reply payload");
		equals("/party transfer {args}", migratedActionOnly.actionTemplate(),
			"action-only migration retains its configured template");
		ChatReplyEntry reloadedActionOnly = ChatChannelsLocalStore.ReplyData.from(migratedActionOnly).toEntry();
		equals(ChatReplyEntry.Mode.LOCAL_ACTION, reloadedActionOnly.mode(), "action-only save/reload keeps Local Action selected");
		equals("/party transfer {args}", reloadedActionOnly.actionTemplate(),
			"action-only save/reload preserves its template");
		check(ChatReplyCommandRules.parse("Party > Alice: !legacy_action Bob", "!", List.of(migratedActionOnly)).isEmpty(),
			"a migrated action template is never entered into incoming auto-replies");
	}

	private static void productionIncomingParserUsesSavedChannelRules() {
		ChatChannelRule customRule = new ChatChannelRule("custom-reply-test", "Custom", "Community >", "/say",
			List.of(), List.of());
		var request = ChatCommandReplyBackend.parseIncomingRequest("Community > Alice: !hello", "!",
			List.of(new ChatReplyEntry("hello", "Hi there!")), List.of(customRule)).orElseThrow();
		check(request.channel().custom(), "the production incoming parser applies saved custom channel rules");
		equals(customRule.tab(), request.replyChannel(), "incoming custom rules keep their configured reply route");
		equals("say Hi there!", ChatReplyCommandRules.replyCommand(request.replyChannel(), request.response()),
			"the production incoming parser preserves the custom outgoing command prefix");
		check(!PartyCommandsModule.INSTANCE.allowsIncomingReply(request.channel()),
			"custom incoming requests remain disabled by default after production parsing");
	}

	private static void parsesCustomChannelReplyRoutes() {
		ChatReplyEntry customReply = new ChatReplyEntry("hello", "Hi there!");
		ChatChannelRule textRule = new ChatChannelRule("custom-text", "Custom Text", "Crownfall >", "/say",
			List.of(), List.of());
		var textRequest = ChatReplyCommandRules.parse("Crownfall > [MVP+] Dave: !hello", "!",
			List.of(customReply), List.of(textRule)).orElseThrow();
		equals("Dave", textRequest.sender(), "custom text routes extract the player name after rank prefixes");
		equals(textRule.tab(), textRequest.channel(), "custom text route selects its configured tab");
		equals("say Hi there!", ChatReplyCommandRules.replyCommand(textRequest.replyChannel(), textRequest.response()),
			"custom text route replies use that rule's outgoing command prefix");

		ChatChannelRule regexRule = new ChatChannelRule("custom-regex", "Custom Regex", "^Dungeon >.*$", "/guildmsg",
			List.of("Bob"), List.of(), ChatChannelRule.MatchMode.REGEX);
		var regexRequest = ChatReplyCommandRules.parse("Dungeon > Bob: !hello", "!",
			List.of(customReply), List.of(regexRule)).orElseThrow();
		equals(regexRule.tab(), regexRequest.replyChannel(), "custom regex routes select the configured reply tab");
		equals("guildmsg Hi there!", ChatReplyCommandRules.replyCommand(regexRequest.replyChannel(), regexRequest.response()),
			"custom regex route replies use its configured prefix");
		check(ChatReplyCommandRules.parse("Dungeon > Eve: !hello", "!", List.of(customReply), List.of(regexRule)).isEmpty(),
			"custom route whitelist is respected by command replies");

		ChatChannelRule partyOverride = new ChatChannelRule("party-custom", "Party Custom", "Party >", "/customparty",
			List.of(), List.of());
		var partyRequest = ChatReplyCommandRules.parse("Party > Alice: !help", "!", List.of(),
			List.of(partyOverride)).orElseThrow();
		equals(ChatChannel.PARTY, partyRequest.channel(), "party remains the source channel for roster and action checks");
		equals(partyOverride.tab(), partyRequest.replyChannel(), "a matching custom rule can choose the reply route");
		equals("customparty help", ChatReplyCommandRules.replyCommand(partyRequest.replyChannel(), "help"),
			"custom rule prefixes override the route only when the rule matches");
		var ordinaryParty = ChatReplyCommandRules.parse("Party > Alice: !help", "!").orElseThrow();
		equals("pc help", ChatReplyCommandRules.replyCommand(ordinaryParty.replyChannel(), "help"),
			"built-in party replies retain the pc prefix when no custom route matches");

		ChatChannelRule nbspPrefixRule = new ChatChannelRule("nbsp-prefix", "NBSP Prefix", "Dungeon >", "\u00a0",
			List.of(), List.of());
		var nbspPrefixRequest = ChatReplyCommandRules.parse("Dungeon > Alice: !help", "!", List.of(),
			List.of(nbspPrefixRule)).orElseThrow();
		equals(nbspPrefixRule.tab(), nbspPrefixRequest.replyChannel(),
			"custom route matching retains Java String.isBlank semantics for its command prefix");
		equals("\u00a0 help", ChatReplyCommandRules.replyCommand(nbspPrefixRequest.replyChannel(), "help"),
			"reply command routing retains Java String.isBlank semantics for a channel prefix");
		equals("\u00a0 \u00a0", ChatReplyCommandRules.replyCommand(
			new ChatChannel("nbsp", "NBSP", "\u00a0", true), "\u00a0"),
			"reply command routing retains Java String.isBlank semantics for message and prefix");

		ChatChannelRule privateOverride = new ChatChannelRule("private-custom", "Private Custom", "From Dave", "/other",
			List.of(), List.of());
		var privateRequest = ChatReplyCommandRules.parse("From Dave: !help", "!", List.of(),
			List.of(privateOverride)).orElseThrow();
		equals(ChatChannel.PRIVATE, privateRequest.channel(), "private source remains available for recipient checks");
		equals(privateOverride.tab(), ChatChannelClassifier.classify("From Dave: !help", List.of(privateOverride)),
			"custom rules still classify matching private lines into their configured tab");
		equals(ChatChannel.PRIVATE, privateRequest.replyChannel(),
			"a matching custom rule cannot override the private reply route");
		equals("r help", ChatReplyCommandRules.replyCommand(privateRequest.replyChannel(), "help"),
			"private replies always use /r when a custom rule matches");
		var ordinaryPrivate = ChatReplyCommandRules.parse("From Dave: !help", "!").orElseThrow();
		equals("r help", ChatReplyCommandRules.replyCommand(ordinaryPrivate.replyChannel(), "help"),
			"private replies retain /r when no custom rule matches");
	}

	private static void incomingChannelDefaultsAndOverrides() {
		PartyCommandsModule module = PartyCommandsModule.INSTANCE;
		check(module.chatRepliesEnabled().rawValue(), "Chat Reply Commands master switch remains enabled by default");
		check(module.replyPartyEnabled().rawValue(), "Party requests are enabled by default");
		check(!module.replyGuildEnabled().rawValue() && !module.replyCoopEnabled().rawValue()
			&& !module.replyPrivateEnabled().rawValue() && !module.replyCustomEnabled().rawValue(),
			"Guild, Co-op, Private, and custom-channel requests are disabled by default");

		var party = ChatReplyCommandRules.parse("Party > Alice: !help", "!").orElseThrow();
		check(module.allowsIncomingReply(party.channel()), "any valid Party sender is accepted by default");
		check(ChatReplyCommandRules.senderAllowed(party.sender(), false, List.of(), false, List.of()),
			"the default Party rule does not require a sender allowlist");
		for (String line : List.of("Guild > Alice: !help", "Co-op > Alice: !help", "From Alice: !help")) {
			var request = ChatReplyCommandRules.parse(line, "!").orElseThrow();
			check(!module.allowsIncomingReply(request.channel()),
				"other recognized channels are rejected by default: " + request.channel().id());
		}

		ChatChannelRule customRule = new ChatChannelRule("custom-reply-test", "Custom", "Community >", "/say",
			List.of(), List.of());
		var custom = ChatReplyCommandRules.parse("Community > Alice: !help", "!", List.of(), List.of(customRule))
			.orElseThrow();
		check(custom.channel().custom(), "user-created channel rules classify requests as custom channels");
		check(!module.allowsIncomingReply(custom.channel()), "custom-channel replies are disabled by default");

		module.replyCustomEnabled().setValue(true);
		check(module.allowsIncomingReply(custom.channel()), "custom-channel replies can be enabled explicitly");
		check(module.allowsIncomingReply(party.channel()), "enabling custom channels does not disable Party requests");
		module.replyCustomEnabled().setValue(false);

		module.replyGuildEnabled().setValue(true);
		var guild = ChatReplyCommandRules.parse("Guild > Alice: !help", "!").orElseThrow();
		check(module.allowsIncomingReply(guild.channel()), "Guild requests can be enabled explicitly");
		check(module.allowsIncomingReply(party.channel()), "enabling another channel does not disable Party requests");
		module.replyGuildEnabled().setValue(false);

		ChatChannelRule partyOverride = new ChatChannelRule("custom-party-route", "Party Custom", "Party >", "/customparty",
			List.of(), List.of());
		var classifiedParty = ChatReplyCommandRules.parse("Party > Alice: !help", "!", List.of(), List.of(partyOverride))
			.orElseThrow();
		check(classifiedParty.replyChannel().custom() && module.allowsIncomingReply(classifiedParty.channel()),
			"custom outgoing classification preserves Party as the incoming source channel");

		// Mirror ModConfig's current key format: module.configName() + "." + setting.name().
		var savedToggles = new java.util.LinkedHashMap<String, Boolean>();
		for (BooleanSetting setting : module.booleanSettings())
			savedToggles.put(module.configName() + "." + setting.name(), setting.rawValue());
		check(Boolean.TRUE.equals(savedToggles.get("Party Commands.Reply Commands"))
			&& Boolean.TRUE.equals(savedToggles.get("Party Commands.Party"))
			&& Boolean.FALSE.equals(savedToggles.get("Party Commands.Guild"))
			&& Boolean.FALSE.equals(savedToggles.get("Party Commands.Co-op"))
			&& Boolean.FALSE.equals(savedToggles.get("Party Commands.Private")),
			"incoming defaults use the current Party Commands persisted setting keys");
		check(!ChatReplyConfigRules.senderAllowed("Eve", true, List.of("Alice"), false, List.of())
			&& ChatReplyConfigRules.senderAllowed("Eve", false, List.of("Alice"), false, List.of())
			&& !ChatReplyConfigRules.senderAllowed("Eve", false, List.of(), true, List.of("Eve")),
			"the default channel policy preserves optional allowlist and blocklist behavior");
	}

	private static void coordinatesUseOdinBlockFormat() {
		equals("x: -12, y: 68, z: 304", ChatReplyCommandRules.coordinateText(-12, 68, 304),
			"!coords output uses Odin's exact integer block-coordinate format");
		equals("Current island: Hub · lobby/instance unavailable", ChatReplyCommandRules.lobbyLocationText("Hub"),
			"!lobby reports the detected island and unavailable server-instance field");
		equals("Current island unavailable · lobby/instance unavailable", ChatReplyCommandRules.lobbyLocationText(null),
			"!lobby reports unavailable when the location snapshot has no island or instance");
	}

	private static void legacyFlatReplyGetsStableRootDefaults() {
		ChatChannelsLocalStore.ReplyData legacy = new ChatChannelsLocalStore.ReplyData();
		legacy.command = "Legacy_One";
		legacy.response = "Saved response";
		ChatReplyEntry firstLoad = legacy.toEntry();
		ChatReplyEntry secondLoad = legacy.toEntry();
		equals("legacy_one", firstLoad.command(), "flat migration preserves and normalizes the saved command");
		equals("Saved response", firstLoad.response(), "flat migration preserves the saved response");
		equals(null, firstLoad.folderId(), "flat migration puts existing replies in Root");
		check(firstLoad.enabled(), "flat migration enables an existing reply by default");
		equals(firstLoad.id(), secondLoad.id(), "legacy rows receive deterministic ids across reloads");
		equals("", firstLoad.actionTemplate(), "old saved replies migrate with no local action template");
	}

	private static void legacyReplyJsonRoundTripPreservesInferredModesAndPayloads() {
		String legacyJson = """
			{ "chatReplyConfigVersion": 4, "chatReplies": [
				{ "command": "legacy_action", "actionTemplate": "/party transfer {args}" },
				{ "command": "legacy_both", "response": "kept as a text reply",
				  "actionTemplate": "/party transfer {args}" }
			] }
			""";
		var gson = geiler.addons.client.dungeon.ClientJsonFile.GSON;
		ChatChannelsLocalStore.Data legacy = gson.fromJson(legacyJson, ChatChannelsLocalStore.Data.class);
		equals(4, legacy.chatReplyConfigVersion, "production JSON codec reads the saved schema-v4 version");
		check(legacy.chatReplies.get(0).mode == null && legacy.chatReplies.get(1).mode == null,
			"legacy JSON rows omit the schema-v5 mode field");

		legacy.chatReplies = legacy.chatReplies.stream()
			.map(ChatChannelsLocalStore.ReplyData::toEntry)
			.map(ChatChannelsLocalStore.ReplyData::from)
			.toList();
		ChatChannelsLocalStore.Data reloaded = gson.fromJson(gson.toJson(legacy), ChatChannelsLocalStore.Data.class);
		ChatReplyEntry actionOnly = reloaded.chatReplies.get(0).toEntry();
		equals(ChatReplyEntry.Mode.LOCAL_ACTION, actionOnly.mode(),
			"JSON-loaded action-only legacy row keeps Local Action after save/reload");
		equals("/party transfer {args}", actionOnly.actionTemplate(),
			"JSON save/reload retains the action-only template");

		ChatReplyEntry both = reloaded.chatReplies.get(1).toEntry();
		equals(ChatReplyEntry.Mode.TEXT_REPLY, both.mode(),
			"JSON-loaded row with both payloads selects Text Reply after save/reload");
		equals("kept as a text reply", both.response(), "JSON save/reload retains the selected text payload");
		equals("/party transfer {args}", both.actionTemplate(),
			"JSON save/reload retains the dormant action template");
	}

	private static void legacyLocalFileMigratesAndRoundTripsThroughStoreIo() {
		Path directory = null;
		Path file = null;
		try {
			directory = Files.createTempDirectory("geiler-chat-channels-local-copy-");
			file = directory.resolve("chat-channels-local.json");
			Files.writeString(file, """
				{
				  "chatReplyConfigVersion": 7,
				  "rules": [{
				    "id": "custom-guild",
				    "name": "Guild",
				    "incomingContains": "Guild > ",
				    "commandPrefix": "$",
				    "matchMode": "TEXT",
				    "whitelist": ["alice"],
				    "blacklist": ["mallory"]
				  }],
				  "chatReplies": [
				    { "id": "legacy-action", "command": "legacy_action", "mode": "LOCAL_ACTION",
				      "response": "", "actionTemplate": "/party transfer {args}",
				      "folderId": "alerts", "enabled": true },
				    { "id": "legacy-text", "command": "hello", "mode": "TEXT_REPLY",
				      "response": "kept text reply", "actionTemplate": "/say {args}",
				      "folderId": "alerts", "enabled": false }
				  ],
				  "replyFolders": [
				    { "id": "garden", "name": "Garden", "parentId": null, "enabled": false },
				    { "id": "alerts", "name": "Alerts", "parentId": "garden", "enabled": true }
				  ],
				  "replyAllowlist": ["alice", "bob"],
				  "replyBlocklist": ["eve"],
				  "replyAllowlistEnabled": false,
				  "replyBlocklistEnabled": true,
				  "unfocusedChatHudEnabled": true,
				  "chatHudBoundsMigrated": false,
				  "focusedChatX": 37,
				  "focusedChatY": 84,
				  "chatWindowWidth": 480,
				  "chatWindowHeight": 320
				}
				""");

			ChatChannelsLocalStore.SanitizedLoad loaded = ChatChannelsLocalStore.readSanitized(file);
			check(loaded.persistenceAllowed(), "isolated legacy local store remains writable after migration");
			check(loaded.needsRewrite(), "schema-v7 local store requests the production migration rewrite");
			assertLegacyLocalStoreFields(loaded.data(), "sanitized legacy local file");
			check(ChatChannelsLocalStore.writeSanitized(file, loaded).get(),
				"legacy local file is written through production writeSanitized");

			ChatChannelsLocalStore.SanitizedLoad reloaded = ChatChannelsLocalStore.readSanitized(file);
			check(reloaded.persistenceAllowed() && !reloaded.needsRewrite(),
				"production readSanitized reloads the migrated file as current and writable");
			assertLegacyLocalStoreFields(reloaded.data(), "reloaded local file");
		} catch (Exception failure) {
			throw new AssertionError("legacy local chat store fields survive production file migration", failure);
		} finally {
			try { if (file != null) Files.deleteIfExists(file); } catch (Exception ignored) { }
			try { if (directory != null) Files.deleteIfExists(directory); } catch (Exception ignored) { }
		}
	}

	private static void assertLegacyLocalStoreFields(ChatChannelsLocalStore.Data data, String stage) {
		equals(8, data.chatReplyConfigVersion, stage + " is sanitized to the current reply schema");
		check(data.rules.size() == 1, stage + " retains its custom channel rule");
		ChatChannelsLocalStore.RuleData rule = data.rules.get(0);
		equals("custom-guild", rule.id, stage + " retains the custom channel ID");
		equals("Guild", rule.name, stage + " retains the custom channel name");
		equals("Guild >", rule.incomingContains, stage + " trims trailing whitespace from the incoming match text");
		equals("$", rule.commandPrefix, stage + " retains the custom outgoing prefix");
		equals("TEXT", rule.matchMode, stage + " retains the channel match mode");
		equals(List.of("alice"), rule.whitelist, stage + " retains the custom channel allowlist");
		equals(List.of("mallory"), rule.blacklist, stage + " retains the custom channel blocklist");

		check(data.chatReplies.size() == 2, stage + " retains both configured reply modes");
		ChatReplyEntry action = data.chatReplies.get(0).toEntry();
		equals("legacy-action", action.id(), stage + " retains the local-action reply ID");
		equals(ChatReplyEntry.Mode.LOCAL_ACTION, action.mode(), stage + " retains Local Action mode");
		equals("/party transfer {args}", action.actionTemplate(), stage + " retains its local action template");
		equals("alerts", action.folderId(), stage + " retains the local-action folder assignment");
		check(action.enabled(), stage + " retains the enabled local-action state");
		ChatReplyEntry text = data.chatReplies.get(1).toEntry();
		equals(ChatReplyEntry.Mode.TEXT_REPLY, text.mode(), stage + " retains Text Reply mode");
		equals("kept text reply", text.response(), stage + " retains the text payload");
		equals("/say {args}", text.actionTemplate(), stage + " retains the dormant action payload");
		equals("alerts", text.folderId(), stage + " retains the text-reply folder assignment");
		check(!text.enabled(), stage + " retains the disabled reply state");

		check(data.replyFolders.size() == 2, stage + " retains both nested reply folders");
		ChatChannelsLocalStore.FolderData garden = data.replyFolders.stream()
			.filter(folder -> "garden".equals(folder.id)).findFirst().orElseThrow();
		ChatChannelsLocalStore.FolderData alerts = data.replyFolders.stream()
			.filter(folder -> "alerts".equals(folder.id)).findFirst().orElseThrow();
		equals(null, garden.parentId, stage + " keeps the root reply folder");
		equals(Boolean.FALSE, garden.enabled, stage + " retains the disabled parent-folder state");
		equals("garden", alerts.parentId, stage + " retains the nested reply-folder parent");
		equals(Boolean.TRUE, alerts.enabled, stage + " retains the enabled child-folder state");
		var folderTree = new geiler.addons.client.collections.FolderTree();
		folderTree.restore(data.replyFolders.stream().map(folder ->
			new geiler.addons.client.collections.FolderTree.Folder(folder.id, folder.name, folder.parentId)).toList());
		var folderEnabled = new java.util.HashMap<String, Boolean>();
		for (ChatChannelsLocalStore.FolderData folder : data.replyFolders) {
			folderEnabled.put(folder.id, folder.enabled != false);
		}
		check(!ChatReplyConfigRules.enabledInFolderTree(folderTree, folderEnabled, "alerts"),
			stage + " keeps a disabled parent cascading to nested replies");

		equals(List.of("alice", "bob"), data.replyAllowlist, stage + " retains sender allowlist entries");
		equals(List.of("eve"), data.replyBlocklist, stage + " retains sender blocklist entries");
		equals(Boolean.FALSE, data.replyAllowlistEnabled, stage + " retains the disabled allowlist switch");
		equals(Boolean.TRUE, data.replyBlocklistEnabled, stage + " retains the enabled blocklist switch");
		check(Boolean.TRUE.equals(data.unfocusedChatHudEnabled), stage + " retains the legacy HUD visibility preference");
		check(Boolean.FALSE.equals(data.chatHudBoundsMigrated),
			stage + " retains the pending one-time native-chat migration marker");
		equals(37, data.focusedChatX, stage + " retains the legacy HUD X position");
		equals(84, data.focusedChatY, stage + " retains the legacy HUD Y position");
		equals(480, data.chatWindowWidth, stage + " retains the legacy HUD width");
		equals(320, data.chatWindowHeight, stage + " retains the legacy HUD height");
	}

	private static void retiredTranslationFieldsAreRemovedFromSavedFiles() {
		Path legacyFile = null;
		Path currentFile = null;
		Path trailingRetiredRunFile = null;
		try {
			legacyFile = Files.createTempFile("geiler-chat-legacy-", ".json");
			Files.writeString(legacyFile, """
				{ "chatReplyConfigVersion": 8, "azureKey": "must-not-remain",
				  "translationEnabled": true, "replyAllowlist": ["Alice"],
				  "replyAllowlistEnabled": false,
				  "chatReplies": [{ "command": "hello", "response": "kept reply" }] }
				""");
			ChatChannelsLocalStore.SanitizedLoad legacy = ChatChannelsLocalStore.readSanitized(legacyFile);
			boolean legacyFileExists = Files.exists(legacyFile);
			String persisted = legacyFileExists ? Files.readString(legacyFile) : "<missing>";
			check(legacyFileExists, "sanitizer leaves the legacy config at its original path"
				+ " (warning=" + legacy.warning() + ", persistenceAllowed=" + legacy.persistenceAllowed() + ")");
			check(legacy.persistenceAllowed(), "valid schema-v8 store stays writable after raw field cleanup"
				+ " (warning=" + legacy.warning()
				+ ", needsRewrite=" + legacy.needsRewrite()
				+ ", loadedReplyCount=" + legacy.data().chatReplies.size()
				+ ", fileExists=" + legacyFileExists
				+ ", azureKeyPresent=" + persisted.contains("\"azureKey\"")
				+ ", translationFieldPresent=" + persisted.contains("\"translationEnabled\"") + ")");
			check(!legacy.needsRewrite(), "raw cleanup does not trigger whole-file serialization for a current schema");
			check(!persisted.contains("azureKey") && !persisted.contains("translationEnabled"),
				"retired credentials and translation options are removed from the local file");
			check(persisted.contains("\"replyAllowlist\": [\"Alice\"]")
				&& persisted.contains("\"response\": \"kept reply\""),
				"raw cleanup preserves unrelated JSON bytes for replies and sender settings");
			ChatChannelsLocalStore.Data reloaded = geiler.addons.client.dungeon.ClientJsonFile.GSON
				.fromJson(persisted, ChatChannelsLocalStore.Data.class);
			equals("kept reply", reloaded.chatReplies.get(0).response,
				"rewriting a schema-v8 file preserves its saved replies");
			equals(List.of("Alice"), reloaded.replyAllowlist,
				"rewriting preserves non-translation sender settings");
			equals(Boolean.FALSE, reloaded.replyAllowlistEnabled,
				"rewriting preserves explicit sender filter state");

			currentFile = Files.createTempFile("geiler-chat-current-", ".json");
			Files.writeString(currentFile, """
				{ "chatReplyConfigVersion": 8, "replyAllowlist": ["Bob"],
				  "chatReplies": [{ "command": "status", "response": "still here" }] }
				""");
			ChatChannelsLocalStore.SanitizedLoad current = ChatChannelsLocalStore.readSanitized(currentFile);
			check(!current.needsRewrite(), "schema-v8 files without retired fields need no migration rewrite");

			trailingRetiredRunFile = Files.createTempFile("geiler-chat-retired-run-", ".json");
			Files.writeString(trailingRetiredRunFile,
				"{ \"keep\": 1, \"azureKey\": \"old\", \"translationEnabled\": true }");
			ChatChannelsLocalStore.SanitizedLoad trailingRun =
				ChatChannelsLocalStore.readSanitized(trailingRetiredRunFile);
			String trailingRunRemainder = Files.readString(trailingRetiredRunFile);
			check(trailingRun.persistenceAllowed() && !trailingRunRemainder.contains("azureKey")
				&& !trailingRunRemainder.contains("translationEnabled"),
				"a trailing run of retired properties is removed");
			check(geiler.addons.client.dungeon.ClientJsonFile.GSON
				.fromJson(trailingRunRemainder, com.google.gson.JsonObject.class).has("keep"),
				"removing a trailing retired-property run preserves valid JSON and the preceding property");
		} catch (Exception failure) {
			throw new AssertionError("retired translation fields are sanitized on disk", failure);
		} finally {
			try { if (legacyFile != null) Files.deleteIfExists(legacyFile); }
			catch (Exception ignored) { }
			try { if (currentFile != null) Files.deleteIfExists(currentFile); }
			catch (Exception ignored) { }
			try { if (trailingRetiredRunFile != null) Files.deleteIfExists(trailingRetiredRunFile); }
			catch (Exception ignored) { }
		}
	}

	private static void failedNonAtomicReplacementRestoresOriginal() {
		Path file = null;
		try {
			file = Files.createTempFile("geiler-chat-replace-failure-", ".json");
			String original = "{ \"chatReplyConfigVersion\": 8, \"azureKey\": \"retired\", "
				+ "\"chatReplies\": [{ \"command\": \"hello\", \"response\": \"kept\" }] }";
			Files.writeString(file, original);
			Path target = file;
			boolean[] recoveryCopyContainedOriginal = { false };
			RetiredChatConfigRedactor.Result result = RetiredChatConfigRedactor.redact(file,
				(source, destination, atomic) -> {
					if (atomic) throw new IOException("simulated atomic replacement failure");
					try (var siblings = Files.list(destination.toAbsolutePath().getParent())) {
						recoveryCopyContainedOriginal[0] = siblings
							.filter(candidate -> candidate.getFileName().toString().startsWith(
								target.getFileName().toString() + ".recovery-"))
							.anyMatch(candidate -> {
								try { return Files.readString(candidate).equals(original); }
								catch (IOException ignored) { return false; }
							});
					}
					// Model the Windows provider deleting the existing destination before its move fails.
					Files.deleteIfExists(destination);
					throw new IOException("simulated fallback failure after destination deletion");
				});
			check(recoveryCopyContainedOriginal[0],
				"fallback creates a complete same-directory recovery copy before replacing the original");
			check(result.status() == RetiredChatConfigRedactor.Status.IO_ERROR && !result.cleaned(),
				"failed replacement remains protected instead of being reported as a successful cleanup");
			check(result.reason().contains("the original was restored"),
				"failure diagnostic confirms the original was restored");
			check(Files.isRegularFile(file), "failed replacement restores the original config path");
			equals(original, Files.readString(file), "failed replacement restores every original config byte");
			try (var siblings = Files.list(file.toAbsolutePath().getParent())) {
				check(siblings.noneMatch(candidate -> candidate.getFileName().toString().startsWith(
					target.getFileName().toString() + ".recovery-")),
					"recovery copy is removed after the original has been restored");
			}
		} catch (Exception failure) {
			throw new AssertionError("failed chat-config replacement restores its original", failure);
		} finally {
			try { if (file != null) Files.deleteIfExists(file); } catch (Exception ignored) { }
		}
	}

	private static void redactorPreservesUnicodeAcrossBufferBoundaries() {
		Path file = null;
		try {
			file = Files.createTempFile("geiler-chat-redactor-window-", ".json");
			String retainedUnicode = "🍄".repeat(2_050);
			String original = "{\"keep\":\"" + retainedUnicode
				+ "\",\"azure\\u004bey\":\"retired\",\"nested\":{\"TranslationCache\":true,\"keep\":7},\"after\":\"ok\"}";
			Files.writeString(file, original);
			RetiredChatConfigRedactor.Result result = RetiredChatConfigRedactor.redact(file);
			String persisted = Files.readString(file);
			check(result.status() == RetiredChatConfigRedactor.Status.REDACTED
				&& result.sourceJsonValid() && result.removedMembers() == 2,
				"the Kotlin scanner finds escaped and nested retired keys across its byte window boundary");
			check(!persisted.contains("azure") && !persisted.contains("TranslationCache")
				&& persisted.contains(retainedUnicode) && persisted.contains("\"keep\":7")
				&& persisted.contains("\"after\":\"ok\""),
				"byte-preserving cleanup keeps UTF-8 data and unrelated nested members intact");
			var parsed = geiler.addons.client.dungeon.ClientJsonFile.GSON
				.fromJson(persisted, com.google.gson.JsonObject.class);
			check(parsed.getAsJsonObject("nested").get("keep").getAsInt() == 7,
				"the byte-window cleanup leaves valid JSON after removing adjacent retired properties");
		} catch (Exception failure) {
			throw new AssertionError("retired chat configuration keys are located across byte windows", failure);
		} finally {
			try { if (file != null) Files.deleteIfExists(file); } catch (Exception ignored) { }
		}
	}

	private static void redactorDepthAndTokenLimitsRemainBounded() {
		Path file = null;
		try {
			file = Files.createTempFile("geiler-chat-redactor-depth-", ".json");
			Files.writeString(file, "[".repeat(RetiredChatConfigRedactor.MAX_DEPTH) + "0"
				+ "]".repeat(RetiredChatConfigRedactor.MAX_DEPTH));
			RetiredChatConfigRedactor.ScanResult maximumDepth = scan(file);
			check(maximumDepth.unsafeReason() == null && maximumDepth.sourceJsonValid(),
				"the maximum supported JSON nesting remains valid");

			Files.writeString(file, "[".repeat(RetiredChatConfigRedactor.MAX_DEPTH + 1) + "0"
				+ "]".repeat(RetiredChatConfigRedactor.MAX_DEPTH + 1));
			RetiredChatConfigRedactor.ScanResult excessiveDepth = scan(file);
			check(excessiveDepth.unsafeReason() != null && excessiveDepth.unsafeReason().contains("64-level")
				&& excessiveDepth.removed().isEmpty(),
				"input beyond the nesting limit is rejected before any redaction range is returned");

			StringBuilder tokenHeavy = new StringBuilder("[");
			for (int index = 0; index < 100_000; index++) {
				if (index > 0) tokenHeavy.append(',');
				tokenHeavy.append('0');
			}
			tokenHeavy.append(']');
			Files.writeString(file, tokenHeavy);
			RetiredChatConfigRedactor.ScanResult excessiveTokens = scan(file);
			check(excessiveTokens.unsafeReason() != null
				&& excessiveTokens.unsafeReason().contains("200,000-token")
				&& excessiveTokens.removed().isEmpty(),
				"input beyond the token limit is rejected without producing partial redaction ranges");
		} catch (Exception failure) {
			throw new AssertionError("retired chat configuration scanner limits remain enforced", failure);
		} finally {
			try { if (file != null) Files.deleteIfExists(file); } catch (Exception ignored) { }
		}
	}

	private static RetiredChatConfigRedactor.ScanResult scan(Path file) throws IOException {
		try (FileChannel input = FileChannel.open(file, StandardOpenOption.READ)) {
			return RetiredChatConfigRedactor.scan(input, Files.size(file));
		}
	}

	private static void oversizedStoreRedactsFieldsWithoutOverwritingReplies() {
		Path file = null;
		try {
			file = Files.createTempFile("geiler-chat-oversized-", ".json");
			String response = "reply-data-".repeat(100_000);
			String original = "{\"chatReplyConfigVersion\":8,\"azureKey\":\"retired-secret\","
				+ "\"chatReplies\":[{\"command\":\"large\",\"response\":\"" + response + "\"}]}";
			Files.writeString(file, original);
			check(Files.size(file) > geiler.addons.client.dungeon.ClientJsonFile.MAX_FILE_BYTES,
				"fixture exceeds the strict 1 MiB runtime read cap");
			ChatChannelsLocalStore.SanitizedLoad loaded = ChatChannelsLocalStore.readSanitized(file);
			check(!loaded.persistenceAllowed(), "oversized runtime store is read-only to prevent default-data overwrite");
			check(loaded.warning() != null && loaded.warning().contains("1 MiB"),
				"oversized store surfaces an actionable local warning");
			check(!loaded.warning().contains("retired-secret"), "warnings never include the retired secret value");
			check(!ChatChannelsLocalStore.writeSanitized(file, loaded).get(),
				"explicit sanitized writes are blocked for an unreadable oversized store");
			String persisted = Files.readString(file);
			check(!persisted.contains("azureKey") && !persisted.contains("retired-secret"),
				"bounded cleanup removes the retired property from an oversized store");
			check(persisted.contains("\"chatReplies\":[{\"command\":\"large\",\"response\":\"")
				&& persisted.contains(response), "oversized cleanup preserves unrelated reply bytes");
		} catch (Exception failure) {
			throw new AssertionError("oversized local chat store is cleaned without data loss", failure);
		} finally {
			try { if (file != null) Files.deleteIfExists(file); } catch (Exception ignored) { }
		}
	}

	private static void malformedStoreRedactsOnlyWhenTheValueBoundaryIsKnown() {
		Path safe = null;
		Path unsafe = null;
		Path ambiguousScalar = null;
		Path missingComma = null;
		try {
			safe = Files.createTempFile("geiler-chat-malformed-safe-", ".json");
			String safeMalformed = "{\"azureKey\":\"retired-secret\",\"chatReplies\":[{\"command\":\"keep\","
				+ "\"response\":\"still here\"}]"; // Missing the root closing brace.
			Files.writeString(safe, safeMalformed);
			ChatChannelsLocalStore.SanitizedLoad cleaned = ChatChannelsLocalStore.readSanitized(safe);
			check(!cleaned.persistenceAllowed(), "malformed store cannot be rewritten from empty defaults");
			String safeRemainder = Files.readString(safe);
			check(!safeRemainder.contains("azureKey") && !safeRemainder.contains("retired-secret"),
				"a malformed suffix does not prevent removing a fully bounded retired property");
			check(safeRemainder.contains("\"chatReplies\":[{\"command\":\"keep\",\"response\":\"still here\"}]"),
				"malformed cleanup preserves unrelated reply bytes");
			check(!ChatChannelsLocalStore.writeSanitized(safe, cleaned).get(),
				"malformed data cannot be replaced with a default store");

			unsafe = Files.createTempFile("geiler-chat-malformed-unsafe-", ".json");
			String unsafeMalformed = "{\"azureKey\":\"unterminated-retired-secret";
			Files.writeString(unsafe, unsafeMalformed);
			ChatChannelsLocalStore.SanitizedLoad preserved = ChatChannelsLocalStore.readSanitized(unsafe);
			check(!preserved.persistenceAllowed(), "unsafe value boundary makes store read-only");
			check(Files.readString(unsafe).equals(unsafeMalformed), "ambiguous malformed input stays byte-for-byte unchanged");
			check(preserved.warning() != null && !preserved.warning().contains("unterminated-retired-secret"),
				"unsafe cleanup failure is visible without logging the secret");

			ambiguousScalar = Files.createTempFile("geiler-chat-malformed-scalar-", ".json");
			String invalidScalar = "{\"azureKey\":retired-secret,\"chatReplies\":[]}";
			Files.writeString(ambiguousScalar, invalidScalar);
			ChatChannelsLocalStore.SanitizedLoad ambiguous = ChatChannelsLocalStore.readSanitized(ambiguousScalar);
			check(!ambiguous.persistenceAllowed(), "invalid retired scalar boundary makes store read-only");
			check(Files.readString(ambiguousScalar).equals(invalidScalar),
				"invalid scalar input remains byte-for-byte unchanged when its value boundary is ambiguous");

			missingComma = Files.createTempFile("geiler-chat-malformed-comma-", ".json");
			String invalidSeparator = "{\"chatReplies\":[] \"azureKey\":\"retired-secret\"}";
			Files.writeString(missingComma, invalidSeparator);
			ChatChannelsLocalStore.SanitizedLoad scrubbedMalformed = ChatChannelsLocalStore.readSanitized(missingComma);
			String scrubbedMalformedBytes = Files.readString(missingComma);
			check(!scrubbedMalformed.persistenceAllowed(), "scrubbing does not make malformed original JSON writable");
			check(!scrubbedMalformedBytes.contains("azureKey") && !scrubbedMalformedBytes.contains("retired-secret"),
				"a bounded retired field can be removed from malformed input");
			check(scrubbedMalformedBytes.contains("\"chatReplies\":[]")
				&& !ChatChannelsLocalStore.writeSanitized(missingComma, scrubbedMalformed).get(),
				"malformed cleanup preserves the unrelated reply property and blocks replacement writes");
			check(scrubbedMalformed.warning() != null && !scrubbedMalformed.warning().contains("retired-secret"),
				"malformed-source warnings do not include retired secret values");
		} catch (Exception failure) {
			throw new AssertionError("malformed local chat store cleanup is fail-closed", failure);
		} finally {
			try { if (safe != null) Files.deleteIfExists(safe); } catch (Exception ignored) { }
			try { if (unsafe != null) Files.deleteIfExists(unsafe); } catch (Exception ignored) { }
			try { if (ambiguousScalar != null) Files.deleteIfExists(ambiguousScalar); } catch (Exception ignored) { }
			try { if (missingComma != null) Files.deleteIfExists(missingComma); } catch (Exception ignored) { }
		}
	}

	private static void cleanupLimitPreservesOriginalAndBlocksWrites() {
		Path file = null;
		try {
			file = Files.createTempFile("geiler-chat-cleanup-limit-", ".json");
			String prefix = "{\"azureKey\":\"retired-secret\"}";
			Files.writeString(file, prefix);
			try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
				channel.position(RetiredChatConfigRedactor.MAX_INPUT_BYTES);
				channel.write(ByteBuffer.wrap(new byte[] { '\n' }));
			}
			ChatChannelsLocalStore.SanitizedLoad loaded = ChatChannelsLocalStore.readSanitized(file);
			check(!loaded.persistenceAllowed(), "over-cap file enters protected read-only mode");
			check(loaded.warning() != null && loaded.warning().contains("16 MiB"),
				"cleanup cap failure explains the required manual action");
			check(Files.size(file) == RetiredChatConfigRedactor.MAX_INPUT_BYTES + 1,
				"over-cap file is not truncated or replaced");
			check(Files.readString(file).startsWith(prefix), "over-cap source remains intact for manual recovery");
		} catch (Exception failure) {
			throw new AssertionError("over-cap chat store cleanup preserves the original", failure);
		} finally {
			try { if (file != null) Files.deleteIfExists(file); } catch (Exception ignored) { }
		}
	}

	private static void folderEnableSwitchesCascadeToNestedReplies() {
		geiler.addons.client.collections.FolderTree folders = new geiler.addons.client.collections.FolderTree();
		var parent = folders.create("Garden", null);
		var child = folders.create("Alerts", parent.id());
		check(ChatReplyConfigRules.enabledInFolderTree(folders, java.util.Map.of(), child.id()),
			"missing folder switch values default to enabled");
		check(!ChatReplyConfigRules.enabledInFolderTree(folders, java.util.Map.of(parent.id(), false), child.id()),
			"parent folder switch disables nested commands");
		check(!ChatReplyConfigRules.enabledInFolderTree(folders, java.util.Map.of(child.id(), false), child.id()),
			"command folder switch disables commands in that folder");
	}

	private static void senderFilterTogglesMigrateAndPersist() {
		var gson = geiler.addons.client.dungeon.ClientJsonFile.GSON;
		ChatChannelsLocalStore.Data legacy = gson.fromJson("""
			{ "chatReplyConfigVersion": 5, "replyAllowlist": ["Alice"], "replyBlocklist": ["Eve"] }
			""", ChatChannelsLocalStore.Data.class);
		check(legacy.replyAllowlistEnabled == null && legacy.replyBlocklistEnabled == null,
			"schema-v5 sender lists deserialize without the new enable fields");
		check(ChatReplyConfigRules.resolveLegacyFilterEnabled(legacy.replyAllowlistEnabled, legacy.replyAllowlist),
			"a legacy nonempty allowlist migrates enabled to preserve prior behavior");
		check(ChatReplyConfigRules.resolveLegacyFilterEnabled(legacy.replyBlocklistEnabled, legacy.replyBlocklist),
			"a legacy nonempty blocklist migrates enabled to preserve prior behavior");
		check(!ChatReplyConfigRules.resolveLegacyFilterEnabled(null, List.of()),
			"a legacy empty list migrates disabled");
		check(!ChatReplyConfigRules.resolveLegacyFilterEnabled(false, List.of("Alice")),
			"an explicit disabled toggle overrides configured list entries");
		check(ChatReplyConfigRules.resolveLegacyFilterEnabled(true, List.of()),
			"an explicit enabled toggle survives even with an empty list");

		ChatChannelsLocalStore.Data saved = new ChatChannelsLocalStore.Data();
		saved.chatReplyConfigVersion = 6;
		saved.replyAllowlist = List.of("alice");
		saved.replyAllowlistEnabled = false;
		saved.replyBlocklist = List.of("eve");
		saved.replyBlocklistEnabled = true;
		ChatChannelsLocalStore.Data reloaded = gson.fromJson(gson.toJson(saved), ChatChannelsLocalStore.Data.class);
		equals(Boolean.FALSE, reloaded.replyAllowlistEnabled, "saved disabled allowlist state round-trips");
		equals(Boolean.TRUE, reloaded.replyBlocklistEnabled, "saved enabled blocklist state round-trips");
	}

	private static void boundsCustomReplyConfigurationAndSenderFilters() {
		check(ChatReplyConfigRules.normalizeCommand(null).isEmpty()
			&& !ChatReplyConfigRules.isReservedCommand(null)
			&& !ChatReplyConfigRules.isPartyTransferName(null)
			&& !ChatReplyConfigRules.validResponse(null)
			&& !ChatReplyConfigRules.canAddEntry(null, null)
			&& !ChatReplyConfigRules.validReplacement(null)
			&& ChatReplyConfigRules.enabledInFolderTree(null, null, null)
			&& ChatReplyConfigRules.normalizeUsernames(null).isEmpty()
			&& ChatReplyConfigRules.validUsernameList(null)
			&& !ChatReplyConfigRules.resolveLegacyFilterEnabled(null, null)
			&& !ChatReplyConfigRules.senderAllowed(null, false, null, false, null)
			&& !ChatReplyConfigRules.remoteActionAllowed(null, false, null, false, null)
			&& !ChatReplyConfigRules.incomingChannelAllowed(null, false, false, false, false, false),
			"reply configuration preserves null input and fallback behavior for Java callers");
		equals("hello_2", ChatReplyConfigRules.normalizeCommand(" Hello_2 "), "command names are normalized");
		check(ChatReplyConfigRules.isReservedCommand("LoBbY"), "custom replies cannot shadow the built-in lobby query");
		equals("", ChatReplyConfigRules.normalizeCommand("2hello"), "command names must start with a letter");
		check(ChatReplyConfigRules.validResponse("One short reply"), "single-line response is accepted");
		check(!ChatReplyConfigRules.validResponse("first\nsecond"), "multi-line replies are rejected");
		check(!ChatReplyConfigRules.validResponse("first\u2028second"), "Unicode line separators are rejected");
		check(!ChatReplyConfigRules.validResponse("§cformatted"), "formatting codes are excluded from plain-text replies");
		check(!ChatReplyConfigRules.validResponse("x".repeat(ChatReplyConfigRules.MAX_RESPONSE_LENGTH + 1)),
			"overlong replies are rejected");
		List<ChatReplyEntry> entries = java.util.stream.IntStream.range(0, ChatReplyConfigRules.MAX_ENTRIES)
			.mapToObj(index -> new ChatReplyEntry("custom" + index, "reply")).toList();
		check(!ChatReplyConfigRules.canAddEntry(entries, new ChatReplyEntry("extra", "reply")),
			"reply count is capped at 16 entries");
		check(!ChatReplyConfigRules.canAddEntry(entries, new ChatReplyEntry("custom0", "new reply")),
			"reply names cannot be duplicated");
		equals(32, ChatReplyConfigRules.normalizeUsernames(java.util.stream.IntStream.range(0, 40)
			.mapToObj(i -> "Player" + i).toList()).size(), "sender filters are bounded to 32 names");
		check(!ChatReplyConfigRules.validUsernameList(List.of("bad name")), "invalid filter values are not saved as a broad allowlist");
		check(ChatReplyConfigRules.validUsernameList(List.of("\u2003"))
			&& !ChatReplyConfigRules.validUsernameList(List.of("\u00a0")),
			"sender filters preserve Java String.isBlank whitespace handling");
		check(!ChatReplyConfigRules.validUsernameList(java.util.stream.IntStream.range(0, 33)
			.mapToObj(i -> "Player" + i).toList()), "more than 32 filter names are rejected rather than truncated");
		check(ChatReplyConfigRules.senderAllowed("Alice", false, List.of(), false, List.of()),
			"disabled empty sender filters permit ordinary replies by default");
		check(ChatReplyConfigRules.senderAllowed("Alice", false, List.of("Alice"), false, List.of()),
			"a disabled allowlist does not constrain senders despite saved entries");
		check(ChatReplyConfigRules.senderAllowed("ALICE", true, List.of("alice", "Bob"), false, List.of()),
			"allowlist matching ignores username case");
		check(!ChatReplyConfigRules.senderAllowed("Eve", true, List.of("alice", "Bob"), false, List.of()),
			"enabled allowlist denies a non-listed sender");
		check(!ChatReplyConfigRules.senderAllowed("Alice", false, List.of(), true, List.of("alice")),
			"enabled blocklist denies a listed sender without enabling the allowlist");
		check(ChatReplyConfigRules.senderAllowed("Eve", false, List.of(), false, List.of("alice")),
			"a disabled blocklist does not constrain senders despite saved entries");
		check(!ChatReplyConfigRules.senderAllowed("Alice", true, List.of(), false, List.of()),
			"enabled empty allowlist denies ordinary replies");
		check(!ChatReplyConfigRules.senderAllowed("Alice", true, List.of("Alice"), true, List.of("alice")),
			"blocklist takes precedence over allowlist");
		check(!ChatReplyConfigRules.senderAllowed("bad name", false, List.of(), false, List.of()),
			"malformed sender names are denied");
		check(ChatReplyConfigRules.blocklistWith(List.of("alice"), " Bob ").equals(List.of("alice", "bob"))
			&& ChatReplyConfigRules.blocklistWith(List.of("alice"), "ALICE") == null
			&& ChatReplyConfigRules.blocklistWith(List.of(), "bad name") == null
			&& ChatReplyConfigRules.blocklistWith(List.of(), "") == null,
			"adding a blocked player normalizes the name and rejects duplicates and invalid names");
		check(ChatReplyConfigRules.blocklistWith(java.util.stream.IntStream.range(0, 32)
			.mapToObj(i -> "Player" + i).toList(), "Extra") == null,
			"the blocklist keeps its 32 name bound");
		check(ChatReplyConfigRules.blocklistWithout(List.of("alice", "bob"), "ALICE").equals(List.of("bob"))
			&& ChatReplyConfigRules.blocklistWithout(List.of("alice"), "carol").equals(List.of("alice")),
			"removing a blocked player ignores case and unknown names");
	}

	private static void validatesTransferAgainstFreshLeaderRoster() {
		PartySnapshot party = new PartySnapshot(List.of(
			new PartyMember("Self", null, DungeonClass.ARCHER),
			new PartyMember("Alice", null, DungeonClass.MAGE),
			new PartyMember("Bob", null, DungeonClass.TANK)), "Self", true, 12L);
		var request = ChatReplyCommandRules.parse("Party > Alice: !pt bOb", "!").orElseThrow();
		equals(ChatReplyCommandRules.Command.PARTY_TRANSFER,
			ChatReplyCommandRules.parse("Party > Alice: !transfer Bob", "!").orElseThrow().command(),
			"transfer alias maps to the same bounded party action");
		var requesterTarget = ChatReplyCommandRules.parse("Party > Alice: !pt", "!").orElseThrow();
		equals(Optional.of("party transfer Alice"), ChatReplyCommandRules.partyTransferCommand(requesterTarget,
			party, true, "Self"), "targetless !pt resolves to the requesting member after roster checks");
		equals(Optional.of("party transfer Bob"), ChatReplyCommandRules.partyTransferCommand(request, party,
			true, "Self"), "transfer resolves an exact username to the canonical current member name");
		check(ChatReplyCommandRules.partyTransferCommand(request, party, false, "Self").isEmpty(),
			"stale/incomplete party list blocks transfer");
		check(ChatReplyCommandRules.partyTransferCommand(request, party, true, "Alice").isEmpty(),
			"non-leader cannot initiate a transfer");
		var unknown = ChatReplyCommandRules.parse("Party > Alice: !pt NotListed", "!").orElseThrow();
		check(ChatReplyCommandRules.partyTransferCommand(unknown, party, true, "Self").isEmpty(),
			"transfer target must be an exact current party member");
		var outsider = ChatReplyCommandRules.parse("Party > Mallory: !pt Bob", "!").orElseThrow();
		check(ChatReplyCommandRules.partyTransferCommand(outsider, party, true, "Self").isEmpty(),
			"requester must be a current party member");
		var selfTransfer = ChatReplyCommandRules.parse("Party > Alice: !pt Self", "!").orElseThrow();
		check(ChatReplyCommandRules.partyTransferCommand(selfTransfer, party, true, "Self").isEmpty(),
			"local player cannot be the requested transfer target");
		// !pt is an open preset (still off by default via its module toggle): any party member may use it.
		check(ChatReplyCommandRules.remotePartyTransferAllowed(request, false, List.of(), false, List.of())
			&& ChatReplyCommandRules.remotePartyTransferAllowed(request, true, List.of(), false, List.of()),
			"an enabled !pt preset does not need an allowlist entry");
		check(!ChatReplyCommandRules.remotePartyTransferAllowed(request, false, List.of(), true, List.of("Alice")),
			"a blocklist still vetoes a remote transfer");
		check(ChatReplyCommandRules.remotePartyTransferAllowed(requesterTarget, true, List.of("Alice"), false, List.of()),
			"targetless requester transfer is accepted for an allowed sender");
		check(!ChatReplyCommandRules.remotePartyTransferAllowed(request, true, List.of("Alice"), true, List.of("alice")),
			"blocklist overrides an explicit allowlist entry for remote transfer");
	}

	private static void validatesPartyActionsAgainstFreshLeaderRoster() {
		PartySnapshot party = new PartySnapshot(List.of(
			new PartyMember("Self", null, DungeonClass.ARCHER),
			new PartyMember("Alice", null, DungeonClass.MAGE),
			new PartyMember("Alina", null, DungeonClass.TANK),
			new PartyMember("Bob", null, DungeonClass.BERSERK)), "Self", true, 21L);
		var warp = ChatReplyCommandRules.parse("Party > Alice: !warp", "!").orElseThrow();
		check(ChatReplyCommandRules.isPartyAction(warp.command())
			&& ChatReplyCommandRules.isRemotePartyAction(warp.command())
			&& ChatReplyCommandRules.requiresPartyLeader(warp.command()),
			"party administration is marked as a remote, leader-only action");
		check(ChatReplyCommandRules.remotePartyActionAllowed(warp, false, List.of(), false, List.of())
			&& ChatReplyCommandRules.remotePartyActionAllowed(warp, true, List.of(), false, List.of()),
			"harmless party presets (warp) are open to any party member without an allowlist entry");
		check(!ChatReplyCommandRules.remotePartyActionAllowed(warp, false, List.of(), true, List.of("alice")),
			"the blocklist still vetoes an open party preset");
		var kickProbe = ChatReplyCommandRules.parse("Party > Alice: !kick Bo", "!").orElseThrow();
		check(ChatReplyCommandRules.remotePartyActionAllowed(kickProbe, false, List.of(), false, List.of())
			&& ChatReplyCommandRules.remotePartyActionAllowed(kickProbe, true, List.of(), false, List.of()),
			"every party action (including kick/promote/demote) is open to any sender; the allowlist is legacy and ignored");
		check(!ChatReplyCommandRules.remotePartyActionAllowed(kickProbe, false, List.of(), true, List.of("alice")),
			"the blocklist still vetoes kick/promote/demote requests");
		var location = ChatReplyCommandRules.parse("Party > Alice: !location", "!").orElseThrow();
		check(location.command() == ChatReplyCommandRules.Command.LOBBY, "!location is an alias of !lobby");
		var invite = ChatReplyCommandRules.parse("From Alice: !inv", "!").orElseThrow();
		check(invite.command() == ChatReplyCommandRules.Command.PRIVATE_INVITE
			&& ChatReplyCommandRules.privateInviteCommand(invite).equals(Optional.of("party invite Alice")),
			"!inv in a private message invites the sender with the fixed party invite command");
		check(ChatReplyCommandRules.parse("Party > Alice: !inv", "!").isEmpty(),
			"!inv is private-only");
		check(ChatReplyCommandRules.partyActionCommand(warp, party, true, "Self").equals(Optional.of("party warp")),
			"a fresh current leader can execute the fixed party warp command");
		check(ChatReplyCommandRules.partyActionCommand(warp, party, true, "Alice").isEmpty()
			&& ChatReplyCommandRules.partyActionCommand(warp, party, false, "Self").isEmpty(),
			"non-leaders and stale rosters cannot execute leader actions");
		var kick = ChatReplyCommandRules.parse("Party > Alice: !kick Bo", "!").orElseThrow();
		check(ChatReplyCommandRules.partyActionCommand(kick, party, true, "Self").equals(Optional.of("party kick Bob")),
			"a unique current-roster prefix resolves to its canonical party member");
		check(ChatReplyCommandRules.partyActionCommand(
			ChatReplyCommandRules.parse("Party > Alice: !kick Al", "!").orElseThrow(), party, true, "Self").isEmpty(),
			"an ambiguous target prefix is rejected");
		check(ChatReplyCommandRules.partyActionCommand(
			ChatReplyCommandRules.parse("Party > Alice: !kick Self", "!").orElseThrow(), party, true, "Self").isEmpty(),
			"party actions cannot target the local leader");
		check(ChatReplyCommandRules.partyActionCommand(
			ChatReplyCommandRules.parse("Party > Mallory: !warp", "!").orElseThrow(), party, true, "Self").isEmpty(),
			"only a current party member can request a party action");
		var boop = ChatReplyCommandRules.parse("Party > Alice: !boop OutsideName", "!").orElseThrow();
		check(!ChatReplyCommandRules.requiresPartyLeader(boop.command())
			&& ChatReplyCommandRules.partyActionCommand(boop, party, true, "Alice").equals(Optional.of("boop OutsideName")),
			"Odin-compatible boop remains available to an allowlisted party member without leader privileges");
		var kickOffline = ChatReplyCommandRules.parse("Party > Alice: !kickoffline", "!").orElseThrow();
		check(ChatReplyCommandRules.partyActionCommand(kickOffline, party, true, "Self")
			.equals(Optional.of("party kickoffline")), "offline kick is resolved as a fixed leader command");
		var queue = ChatReplyCommandRules.parse("Party > Alice: !m7", "!").orElseThrow();
		check(ChatReplyCommandRules.partyActionCommand(queue, party, true, "Self").equals(Optional.of("joininstance MASTER_CATACOMBS_FLOOR_SEVEN")),
			"dungeon queue actions send the same joininstance command as the built-in aliases after leader validation");
		check(ChatReplyCommandRules.partyActionCommand(
			new ChatReplyCommandRules.Request(ChatChannel.PARTY, "Alice",
				ChatReplyCommandRules.Command.PARTY_QUEUE_INSTANCE, "bogus", null), party, true, "Self").isEmpty(),
			"queue action execution validates its command code independently of chat parsing");
	}

	/** The incoming party path trusts the `Party >` prefix: no fresh roster or membership is required, but leader-only actions need the local player to be the known leader. */
	private static void incomingPartyActionsTrustThePartyPrefix() {
		PartySnapshot none = new PartySnapshot(List.of(), null, false, 0L);
		PartySnapshot selfLeads = new PartySnapshot(List.of(new PartyMember("Self", null, DungeonClass.ARCHER)), "Self", true, 5L);
		var warp = ChatReplyCommandRules.parse("Party > Alice: !warp", "!").orElseThrow();
		check(ChatReplyCommandRules.incomingPartyActionCommand(warp, selfLeads, "Self").equals(Optional.of("party warp")),
			"warp runs without a fresh roster when the local player is the known leader");
		check(ChatReplyCommandRules.incomingPartyActionCommand(warp, none, "Self").isEmpty()
			&& ChatReplyCommandRules.incomingPartyActionCommand(warp, null, "Self").isEmpty(),
			"leader-only actions do nothing while the leader is unknown (Odin does the same)");
		var allinv = ChatReplyCommandRules.parse("Party > Alice: !allinv", "!").orElseThrow();
		check(ChatReplyCommandRules.incomingPartyActionCommand(allinv, selfLeads, "Self")
			.equals(Optional.of("party settings allinvite")), "allinvite runs for the known leader");
		var queue = ChatReplyCommandRules.parse("Party > Alice: !f7", "!").orElseThrow();
		check(ChatReplyCommandRules.incomingPartyActionCommand(queue, selfLeads, "Self")
			.equals(Optional.of("joininstance CATACOMBS_FLOOR_SEVEN")), "dungeon queue sends joininstance for the known leader");
		check(ChatReplyCommandRules.incomingPartyActionCommand(
			ChatReplyCommandRules.parse("Party > Alice: !t5", "!").orElseThrow(), selfLeads, "Self")
			.equals(Optional.of("joininstance KUUDRA_INFERNAL")), "Kuudra tiers use the Kuudra joininstance name");
		var kick = ChatReplyCommandRules.parse("Party > Alice: !kick Bob", "!").orElseThrow();
		check(ChatReplyCommandRules.incomingPartyActionCommand(kick, selfLeads, "Self").equals(Optional.of("party kick Bob")),
			"kick uses the typed username when the roster does not know the target");
		var kickSender = ChatReplyCommandRules.parse("Party > Alice: !kick", "!").orElseThrow();
		check(ChatReplyCommandRules.incomingPartyActionCommand(kickSender, selfLeads, "Self").equals(Optional.of("party kick Alice")),
			"a targetless kick uses the sender");
		var promoteSelf = ChatReplyCommandRules.parse("Party > Alice: !promote Self", "!").orElseThrow();
		check(ChatReplyCommandRules.incomingPartyActionCommand(promoteSelf, selfLeads, "Self").isEmpty(),
			"the local player is never a target");
		var transfer = ChatReplyCommandRules.parse("Party > Alice: !pt", "!").orElseThrow();
		check(ChatReplyCommandRules.incomingPartyActionCommand(transfer, selfLeads, "Self").equals(Optional.of("party transfer Alice")),
			"a targetless transfer hands the party to the sender");
		PartySnapshot otherLeader = new PartySnapshot(List.of(
			new PartyMember("Self", null, DungeonClass.ARCHER),
			new PartyMember("Alice", null, DungeonClass.MAGE)), "Alice", true, 5L);
		check(ChatReplyCommandRules.incomingPartyActionCommand(warp, otherLeader, "Self").isEmpty(),
			"leader-only actions are skipped when another player is known to lead the party");
		var boop = ChatReplyCommandRules.parse("Party > Alice: !boop Bob", "!").orElseThrow();
		check(ChatReplyCommandRules.incomingPartyActionCommand(boop, otherLeader, "Self").equals(Optional.of("boop Bob")),
			"boop needs no leader");
		PartySnapshot selfLeader = new PartySnapshot(List.of(
			new PartyMember("Self", null, DungeonClass.ARCHER),
			new PartyMember("Alice", null, DungeonClass.MAGE),
			new PartyMember("Bob", null, DungeonClass.BERSERK),
			new PartyMember("Alina", null, DungeonClass.TANK)), "Self", true, 6L);
		check(ChatReplyCommandRules.incomingPartyActionCommand(
			ChatReplyCommandRules.parse("Party > Alice: !kick Bo", "!").orElseThrow(), selfLeader, "Self")
			.equals(Optional.of("party kick Bob")), "a roster prefix still resolves to the canonical member name");
		check(ChatReplyCommandRules.incomingPartyActionCommand(
			ChatReplyCommandRules.parse("Party > Mallory: !warp", "!").orElseThrow(), selfLeader, "Self")
			.equals(Optional.of("party warp")), "a sender missing from an old roster is still served");
		var whisper = ChatReplyCommandRules.parse("From Alice: !warp", "!");
		check(whisper.isEmpty() || ChatReplyCommandRules.incomingPartyActionCommand(whisper.get(), none, "Self").isEmpty(),
			"only the party channel produces party actions");
	}

	private static void queueIsBoundedAndHonorsDueTicks() {
		ChatReplyCommandQueue<String> queue = new ChatReplyCommandQueue<>(2);
		check(queue.offer("later", 8), "queue accepts a pending response");
		check(queue.offer("earlier", 5), "queue accepts a second pending response");
		check(!queue.offer("overflow", 9), "queue refuses work beyond its fixed capacity");
		equals(null, queue.pollReady(4), "response stays pending before its delay expires");
		equals("earlier", queue.pollReady(5), "earlier due response leaves first");
		equals("later", queue.pollReady(8), "later response waits for its own due tick");
		queue.offer("discarded", 12);
		queue.clear();
		equals(0, queue.size(), "disconnect/disable clear drops delayed responses");
	}

	private static void queueKeepsJavaEntrypointsAndStableOrdering() {
		try {
			var constructor = ChatReplyCommandQueue.class.getConstructor(int.class);
			var offer = ChatReplyCommandQueue.class.getMethod("offer", Object.class, long.class);
			var pollReady = ChatReplyCommandQueue.class.getMethod("pollReady", long.class);
			var size = ChatReplyCommandQueue.class.getMethod("size");
			var clear = ChatReplyCommandQueue.class.getMethod("clear");
			check(Modifier.isPublic(constructor.getModifiers())
				&& Modifier.isPublic(offer.getModifiers()) && offer.getReturnType() == boolean.class
				&& Modifier.isPublic(pollReady.getModifiers()) && pollReady.getReturnType() == Object.class
				&& Modifier.isPublic(size.getModifiers()) && size.getReturnType() == int.class
				&& Modifier.isPublic(clear.getModifiers()) && clear.getReturnType() == void.class,
				"the Kotlin due-tick queue keeps its Java generic constructor and method ABI");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("chat reply queue Java entrypoint changed", exception);
		}

		ChatReplyCommandQueue<String> queue = new ChatReplyCommandQueue<>(3);
		check(queue.offer("first", 5) && queue.offer("second", 5) && queue.offer("earlier", 4),
			"queue accepts equal-deadline entries and an earlier entry");
		equals("earlier", queue.pollReady(4), "earlier due tick still leaves before tied entries");
		equals("first", queue.pollReady(5), "equal due ticks retain insertion order");
		equals("second", queue.pollReady(5), "the next equal-due entry remains second");
		check(!queue.offer(null, 5) && !queue.offer("negative", -1),
			"queue rejects null values and negative due ticks");
		try {
			new ChatReplyCommandQueue<>(0);
			throw new AssertionError("zero-capacity queue should fail");
		} catch (IllegalArgumentException expected) {
			equals("capacity must be positive", expected.getMessage(), "capacity failure stays compatible");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void equals(Object expected, Object actual, String message) {
		if (expected == null ? actual != null : !expected.equals(actual))
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
}
