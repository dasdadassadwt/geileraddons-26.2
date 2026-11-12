package geiler.addons.client.chat;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.impl.ChatChannelsModule;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/** Focused pure checks for chat classification, custom-route precedence, filters, and send routing. */
public final class ChatChannelsChecks {
	private ChatChannelsChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		moduleIdentityAndJavaCompatibility();
		chatHelperJavaCompatibility();
		nullSanitizedDataAndCollectionsKeepJavaBehavior();
		chatModelValuesKeepJavaAbiAndNormalization();
		retiredRedactorRecordsKeepValueAndListContracts();
		ChatChannelMixinRegistrationChecks.run();
		ChatReplyCommandChecks.run();
		ChatTpsTrackerChecks.run();
		ChatWindowLayoutChecks.run();
		builtInTabsAndClassification();
		tabPrefixesAndInputRouting();
		customRulePrecedenceAndFilters();
		customRegexRulesAreBoundedAndFlexible();
		outgoingRoutesPreserveVanillaInput();
		activeCaptureAndHelpRouting();
		messageComponentInteractionStylesRemainAvailable();
		wrappedLinesCacheByChannelWidthAndRevision();
		animatedWrappedLinesRefreshOnlyKnownMatches();
	}

	private static void nullSanitizedDataAndCollectionsKeepJavaBehavior() {
		java.nio.file.Path file = null;
		java.nio.file.Path nullDataFile = null;
		try {
			var nullableRecord = new ChatChannelsLocalStore.SanitizedLoad(null, false, true, null);
			check(nullableRecord.data() == null,
				"Java callers can construct and read a sanitized record with null data");
			nullDataFile = java.nio.file.Files.createTempFile("geiler-chat-null-data-", ".json");
			check(ChatChannelsLocalStore.writeSanitized(nullDataFile, nullableRecord).get(),
				"writeSanitized preserves the Java behavior for a writable null-data record");
			equals("null", java.nio.file.Files.readString(nullDataFile).trim(),
				"writeSanitized serializes the null data component as JSON null");

			file = java.nio.file.Files.createTempFile("geiler-chat-null-collections-", ".json");
			java.nio.file.Files.writeString(file, """
				{ "chatReplyConfigVersion": 8, "rules": null, "chatReplies": null,
				  "replyFolders": null, "replyAllowlist": null, "replyBlocklist": null }
				""");
			ChatChannelsLocalStore.SanitizedLoad loaded = ChatChannelsLocalStore.readSanitized(file);
			check(loaded.persistenceAllowed() && !loaded.needsRewrite(),
				"current local settings with null collections remain readable and writable");
			check(loaded.data().rules != null && loaded.data().rules.isEmpty()
				&& loaded.data().chatReplies != null && loaded.data().chatReplies.isEmpty()
				&& loaded.data().replyFolders != null && loaded.data().replyFolders.isEmpty()
				&& loaded.data().replyAllowlist != null && loaded.data().replyAllowlist.isEmpty()
				&& loaded.data().replyBlocklist != null && loaded.data().replyBlocklist.isEmpty(),
				"null persisted collections sanitize to their original empty defaults");
		} catch (Exception failure) {
			throw new AssertionError("null local chat data and collections retain Java behavior", failure);
		} finally {
			try { if (file != null) java.nio.file.Files.deleteIfExists(file); }
			catch (Exception ignored) { }
			try { if (nullDataFile != null) java.nio.file.Files.deleteIfExists(nullDataFile); }
			catch (Exception ignored) { }
		}
	}

	private static void chatHelperJavaCompatibility() {
		try {
			assertPublicStatic(ChatChannelFilterRules.class, "isVisible", boolean.class,
				String.class, String.class);
			assertPublicStatic(ChatChannelTabs.class, "selected", ChatChannelTabs.Tab.class);
			assertPublicStatic(ChatChannelTabs.class, "tabs", List.class);
			assertPublicStatic(ChatChannelTabs.class, "select", boolean.class, ChatChannelTabs.Tab.class);
			assertPublicStatic(ChatChannelTabs.class, "routeInput", String.class,
				ChatChannelTabs.Tab.class, String.class);
			assertPublicStatic(ChatChannelTabs.class, "reset", void.class);
			ChatChannelTabs.Tab.class.getMethod("channelId");
			ChatChannelTabs.Tab.class.getMethod("label");
			ChatChannelTabs.Tab.class.getMethod("messagePrefix");
			assertPublicStatic(ChatChannelClassifier.class, "classify", ChatChannel.class, String.class);
			assertPublicStatic(ChatChannelClassifier.class, "classify", ChatChannel.class,
				String.class, List.class);
			assertPublicStatic(ChatChannelClassifier.class, "sender", String.class, String.class);
			assertPublicStatic(ChatChannelRegex.class, "validationError", java.util.Optional.class,
				String.class);
			assertPublicStatic(ChatChannelRegex.class, "matches", boolean.class,
				String.class, String.class);
			var maxRegexPattern = ChatChannelRegex.class.getField("MAX_PATTERN_CHARACTERS");
			var maxRegexInput = ChatChannelRegex.class.getField("MAX_INPUT_CHARACTERS");
			check(Modifier.isPublic(maxRegexPattern.getModifiers()) && Modifier.isStatic(maxRegexPattern.getModifiers())
				&& Modifier.isFinal(maxRegexPattern.getModifiers()) && maxRegexPattern.getInt(null) == 96,
				"regex pattern limit remains a public Java constant");
			check(Modifier.isPublic(maxRegexInput.getModifiers()) && Modifier.isStatic(maxRegexInput.getModifiers())
				&& Modifier.isFinal(maxRegexInput.getModifiers()) && maxRegexInput.getInt(null) == 2048,
				"regex input limit remains a public Java constant");
			assertUnmodifiable(ChatChannelTabs.tabs(), "tab catalog remains immutable to Java callers");
			assertUnmodifiable(ChatChannelHelpRouting.destinationIds("party"),
				"local help destinations remain immutable to Java callers");
			assertPublicStatic(ChatOutgoingRouter.class, "routedInput", String.class,
				ChatChannel.class, String.class);
			assertPublicStatic(ChatChannelRuntimeGate.class, "shouldCapture", boolean.class, boolean.class);
			assertPublicStatic(ChatChannelRuntimeGate.class, "shouldSuppressVanilla", boolean.class,
				boolean.class, boolean.class);
			assertPublicStatic(ChatChannelRuntimeGate.class, "shouldSuppressVanilla", boolean.class,
				boolean.class, boolean.class, boolean.class, boolean.class);
			assertPublicStatic(ChatChannelHelpRouting.class, "destinationIds", List.class, String.class);
			assertPublicStatic(ChatChannelHelpRouting.class, "localOriginId", String.class,
				boolean.class, String.class);
			assertPublicStatic(ChatChannelHelpRouting.class, "shouldEchoToVanilla", boolean.class, boolean.class);
			assertPublicStatic(ChatNativeCopyRules.class, "lineIndexFromBottom", int.class,
				int.class, int.class, int.class);
			assertPublicStatic(ChatNativeSizeRules.class, "effectiveWidth", int.class,
				int.class, int.class, int.class);
			assertPublicStatic(ChatNativeSizeRules.class, "availableWidth", int.class,
				int.class, double.class);
			assertPublicStatic(ChatNativeSizeRules.class, "effectiveHeight", int.class,
				int.class, int.class, int.class, double.class);
			assertPublicStatic(ChatNativeSizeRules.class, "migrateLegacyWidthExtra", int.class,
				int.class, int.class);
			assertPublicStatic(ChatNativeSizeRules.class, "migrateLegacyHeightExtra", int.class,
				int.class, int.class, double.class);
			assertPublicStatic(ChatNativeSizeRules.class, "scaledHeight", int.class,
				int.class, double.class);
			assertPublicStatic(ChatNativeSizeRules.class, "clampExtra", int.class, int.class);
			var extraPixels = ChatNativeSizeRules.class.getField("MAX_EXTRA_PIXELS");
			check(Modifier.isPublic(extraPixels.getModifiers()) && Modifier.isStatic(extraPixels.getModifiers())
				&& Modifier.isFinal(extraPixels.getModifiers()), "chat size limit remains a public static constant");

			assertPublicStaticSynchronized(ChatChannelsBackend.class, "onIncoming", void.class,
				net.minecraft.network.chat.Component.class, net.minecraft.network.chat.MessageSignature.class,
				net.minecraft.client.multiplayer.chat.GuiMessageSource.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "onDelete", void.class,
				net.minecraft.network.chat.MessageSignature.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "messages", List.class, String.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "messageRevision", long.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "channels", List.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "channel", ChatChannel.class, String.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "selectedChannel", ChatChannel.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "selectChannel", void.class, String.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "reclassifyMessages", void.class);
			assertPublicStatic(ChatChannelsBackend.class, "isActive", boolean.class);
			assertPublicStatic(ChatChannelsBackend.class, "isWindowOpen", boolean.class);
			assertPublicStatic(ChatChannelsBackend.class, "shouldSuppressVanillaOverlay", boolean.class,
				boolean.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "refreshVisibleMessageFilter", void.class,
				net.minecraft.client.gui.components.ChatComponent.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "nativeFilterRevision", long.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "shouldShowVanillaMessage", boolean.class,
				net.minecraft.client.multiplayer.chat.GuiMessage.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "beginNativeMessageInsertion", void.class,
				net.minecraft.client.gui.components.ChatComponent.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "finishNativeMessageInsertion", void.class,
				net.minecraft.client.gui.components.ChatComponent.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "channelForVanillaMessage", ChatChannel.class,
				net.minecraft.client.multiplayer.chat.GuiMessage.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "routeLocalMessage", void.class,
				net.minecraft.network.chat.Component.class, String.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "dynamicHelp", String.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "showHelp", void.class);
			assertPublicStaticSynchronized(ChatChannelsBackend.class, "clearForDisconnect", void.class);
			assertPublicInstance(ChatChannelsBackend.ChatMessage.class, "id", long.class);
			assertPublicInstance(ChatChannelsBackend.ChatMessage.class, "original",
				net.minecraft.network.chat.Component.class);
			assertPublicInstance(ChatChannelsBackend.ChatMessage.class, "display",
				net.minecraft.network.chat.Component.class);
			assertPublicInstance(ChatChannelsBackend.ChatMessage.class, "rawDisplay",
				net.minecraft.network.chat.Component.class);
			assertPublicInstance(ChatChannelsBackend.ChatMessage.class, "signature",
				net.minecraft.network.chat.MessageSignature.class);
			assertPublicInstance(ChatChannelsBackend.ChatMessage.class, "source",
				net.minecraft.client.multiplayer.chat.GuiMessageSource.class);
			assertPublicInstance(ChatChannelsBackend.ChatMessage.class, "receivedAtMillis", long.class);
			assertPublicInstance(ChatChannelsBackend.ChatMessage.class, "channel", ChatChannel.class);
			assertPublicInstance(ChatChannelsBackend.ChatMessage.class, "displayRevision", long.class);
			var chatMessageConstructor = ChatChannelsBackend.ChatMessage.class.getDeclaredConstructor(
				long.class, net.minecraft.network.chat.Component.class,
				net.minecraft.network.chat.MessageSignature.class,
				net.minecraft.client.multiplayer.chat.GuiMessageSource.class, ChatChannel.class, long.class);
			check(Modifier.isPrivate(chatMessageConstructor.getModifiers()),
				"chat message constructor remains private to Java callers");

			assertPublicStatic(ChatChannelsLocalStore.class, "instance", ChatChannelsLocalStore.class);
			var maxRules = ChatChannelsLocalStore.class.getField("MAX_RULES");
			var maxReplies = ChatChannelsLocalStore.class.getField("MAX_CHAT_REPLIES");
			check(Modifier.isPublic(maxRules.getModifiers()) && Modifier.isStatic(maxRules.getModifiers())
				&& Modifier.isFinal(maxRules.getModifiers()) && maxRules.getInt(null) == 16,
				"channel rule limit remains a public Java constant");
			check(Modifier.isPublic(maxReplies.getModifiers()) && Modifier.isStatic(maxReplies.getModifiers())
				&& Modifier.isFinal(maxReplies.getModifiers()), "chat reply limit remains a public Java constant");
			var sanitizedLoad = ChatChannelsLocalStore.SanitizedLoad.class;
			check(sanitizedLoad.isRecord(), "sanitized load keeps its Java record contract");
			equals(List.of("data", "needsRewrite", "persistenceAllowed", "warning"),
				java.util.Arrays.stream(sanitizedLoad.getRecordComponents())
					.map(java.lang.reflect.RecordComponent::getName).toList(),
				"sanitized load keeps its record component order and names");
			sanitizedLoad.getDeclaredConstructor(ChatChannelsLocalStore.Data.class, boolean.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "rules", List.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "addRule", void.class, ChatChannelRule.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "removeRule", boolean.class, String.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "chatReplies", List.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "enabledChatReplies", List.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "isChatReplyEnabled", boolean.class,
				ChatReplyEntry.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "addChatReply", boolean.class,
				ChatReplyEntry.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "replaceChatReplies", boolean.class, List.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "setChatReplyEnabled", boolean.class,
				String.class, boolean.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "setChatReplyFolder", boolean.class,
				String.class, String.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "removeChatReplyById", boolean.class,
				String.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "removeChatReply", boolean.class, String.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "replyFolders",
				geiler.addons.client.collections.FolderTree.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "replyFolderEnabled", boolean.class, String.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "setReplyFolderEnabled", boolean.class,
				String.class, boolean.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "persistReplyFolders", void.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "replyAllowlist", List.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "replyBlocklist", List.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "replyAllowlistEnabled", boolean.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "replyBlocklistEnabled", boolean.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "setReplyFilters", boolean.class,
				List.class, boolean.class, List.class, boolean.class);
			assertPublicSynchronized(ChatChannelsLocalStore.class, "migrateLegacyHudBounds",
				ChatWindowLayoutRules.Settings.class);

			var cacheConstructor = ChatWrappedLineCache.class.getConstructor();
			check(Modifier.isPublic(cacheConstructor.getModifiers()),
				"wrapped-line cache keeps its public no-argument constructor");
			assertPublicInstance(ChatWrappedLineCache.class, "getOrCompute", List.class,
				String.class, int.class, long.class, java.util.function.Supplier.class,
				java.util.function.ToLongFunction.class, java.util.function.ToLongFunction.class,
				java.util.function.Function.class);
			assertPublicInstance(ChatWrappedLineCache.class, "getOrCompute", List.class,
				String.class, int.class, long.class, long.class, java.util.function.Supplier.class,
				java.util.function.ToLongFunction.class, java.util.function.ToLongFunction.class,
				java.util.function.Predicate.class, java.util.function.Function.class);
			assertPublicInstance(ChatWrappedLineCache.class, "invalidate", void.class);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("chat helper Java entrypoint changed", exception);
		}
	}

	private static void assertPublicStatic(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
		throws ReflectiveOperationException {
		var method = owner.getMethod(name, parameters);
		check(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
			&& method.getReturnType() == result,
			owner.getSimpleName() + "." + name + " keeps its public static Java signature");
	}

	private static void assertPublicStaticSynchronized(Class<?> owner, String name, Class<?> result,
		Class<?>... parameters) throws ReflectiveOperationException {
		var method = owner.getMethod(name, parameters);
		check(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
			&& Modifier.isSynchronized(method.getModifiers()) && method.getReturnType() == result,
			owner.getSimpleName() + "." + name + " keeps its synchronized static Java signature");
	}

	private static void assertPublicSynchronized(Class<?> owner, String name, Class<?> result,
		Class<?>... parameters) throws ReflectiveOperationException {
		var method = owner.getMethod(name, parameters);
		check(Modifier.isPublic(method.getModifiers()) && Modifier.isSynchronized(method.getModifiers())
			&& !Modifier.isStatic(method.getModifiers()) && method.getReturnType() == result,
			owner.getSimpleName() + "." + name + " keeps its synchronized Java signature");
	}

	private static void assertPublicInstance(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
		throws ReflectiveOperationException {
		var method = owner.getMethod(name, parameters);
		check(Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
			&& method.getReturnType() == result,
			owner.getSimpleName() + "." + name + " keeps its public Java signature");
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertUnmodifiable(List<?> values, String message) {
		try {
			((List) values).add("unexpected");
		} catch (UnsupportedOperationException expected) {
			return;
		}
		throw new AssertionError(message);
	}

	private static void moduleIdentityAndJavaCompatibility() {
		ChatChannelsModule module = ChatChannelsModule.INSTANCE;
		equals("Chat Channels", module.name(), "module display name");
		equals("Chat Channels", module.configName(), "persisted module config ID");
		equals("Captures channel history and adds clickable tabs above vanilla chat.",
			module.description(), "module description");
		equals(Category.MISCELLANEOUS, module.category(), "module category");
		check(!module.defaultEnabled(), "module remains disabled by default");

		ChatChannelTabs.reset();
		equals(ChatChannelTabs.Tab.NONE, ChatChannelsModule.selectedTab(), "public static selected-tab entrypoint");
		check(publicJavaEntrypointsRemainStable(module),
			"singleton field and both public static chat entrypoints remain Java-visible");
		check(!ChatChannelsModule.handleLocalChatCommand(null),
			"Java compatibility entrypoint keeps returning false for a null local command");
		ChatChannelTabs.reset();
	}

	private static void chatModelValuesKeepJavaAbiAndNormalization() {
		check(Modifier.isFinal(ChatChannel.class.getModifiers()) && !ChatChannel.class.isRecord()
			&& hasKotlinMetadata(ChatChannel.class)
			&& Modifier.isFinal(ChatChannelRule.class.getModifiers()) && !ChatChannelRule.class.isRecord()
			&& hasKotlinMetadata(ChatChannelRule.class),
			"chat channel models remain Kotlin classes without Java record metadata");
		try {
			var channelConstructor = ChatChannel.class.getConstructor(
				String.class, String.class, String.class, boolean.class);
			equals(List.of(String.class, String.class, String.class, boolean.class),
				java.util.Arrays.asList(channelConstructor.getParameterTypes()),
				"channel canonical constructor descriptor remains stable");
			assertPublicInstance(ChatChannel.class, "id", String.class);
			assertPublicInstance(ChatChannel.class, "name", String.class);
			assertPublicInstance(ChatChannel.class, "commandPrefix", String.class);
			assertPublicInstance(ChatChannel.class, "custom", boolean.class);
			var ruleConstructor = ChatChannelRule.class.getConstructor(String.class, String.class, String.class,
				String.class, List.class, List.class, ChatChannelRule.MatchMode.class);
			equals(List.of(String.class, String.class, String.class, String.class, List.class, List.class,
				ChatChannelRule.MatchMode.class), java.util.Arrays.asList(ruleConstructor.getParameterTypes()),
				"rule canonical constructor descriptor remains stable");
			ChatChannelRule.class.getConstructor(String.class, String.class, String.class, String.class,
				List.class, List.class);
			assertPublicInstance(ChatChannelRule.class, "id", String.class);
			assertPublicInstance(ChatChannelRule.class, "name", String.class);
			assertPublicInstance(ChatChannelRule.class, "incomingContains", String.class);
			assertPublicInstance(ChatChannelRule.class, "commandPrefix", String.class);
			assertPublicInstance(ChatChannelRule.class, "whitelist", List.class);
			assertPublicInstance(ChatChannelRule.class, "blacklist", List.class);
			assertPublicInstance(ChatChannelRule.class, "matchMode", ChatChannelRule.MatchMode.class);
			assertPublicStatic(ChatChannel.class, "builtIns", List.class);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("chat models retain their public Java constructors and accessors", failure);
		}

		ChatChannel channel = new ChatChannel(null, " \u2003 ", " \u2003 ", true);
		equals("", channel.id(), "channel constructor normalizes a null id");
		equals("", channel.name(), "channel constructor falls back to the normalized id for blank names");
		equals(null, channel.commandPrefix(), "channel constructor clears a blank command prefix");
		check(channel.custom(), "channel constructor preserves its custom component");
		ChatChannel sameChannel = new ChatChannel("", "", null, true);
		equals(sameChannel, channel, "channel values retain component-based equality");
		equals(sameChannel.hashCode(), channel.hashCode(), "equal channel values retain matching hashes");
		equals("ChatChannel[id=, name=, commandPrefix=null, custom=true]", channel.toString(),
			"channel values retain the prior record-shaped diagnostic string");

		List<String> sourceWhitelist = new ArrayList<>(java.util.Arrays.asList(" Alice ", null, " ", "Alice", "Bob"));
		List<String> sourceBlacklist = new ArrayList<>(List.of(" Muted "));
		ChatChannelRule rule = new ChatChannelRule("rule-1", " \r Example \n ", " Guild\r > ", " \n /pc \t",
			sourceWhitelist, sourceBlacklist, null);
		sourceWhitelist.add("Changed");
		sourceBlacklist.clear();
		equals("rule-1", rule.id(), "rule constructor preserves a nonblank id");
		equals("Example", rule.name(), "rule constructor trims and bounds the display name");
		equals("Guild  >", rule.incomingContains(), "rule constructor normalizes line breaks in match text");
		equals("/pc", rule.commandPrefix(), "rule constructor normalizes line breaks in command prefixes");
		equals(List.of("Alice", "Bob"), rule.whitelist(), "rule constructor trims and deduplicates whitelist names");
		equals(List.of("Muted"), rule.blacklist(), "rule constructor defensively copies and trims blacklist names");
		equals(ChatChannelRule.MatchMode.TEXT, rule.matchMode(), "rule constructor defaults a null match mode");
		assertUnmodifiable(rule.whitelist(), "normalized rule lists remain immutable to Java callers");
		ChatChannelRule sameRule = new ChatChannelRule("rule-1", "Example", "Guild  >", "/pc",
			List.of("Alice", "Bob"), List.of("Muted"), ChatChannelRule.MatchMode.TEXT);
		equals(sameRule, rule, "rule values retain component-based equality");
		equals(sameRule.hashCode(), rule.hashCode(), "equal rule values retain matching hashes");
		equals("ChatChannelRule[id=rule-1, name=Example, incomingContains=Guild  >, commandPrefix=/pc, "
			+ "whitelist=[Alice, Bob], blacklist=[Muted], matchMode=TEXT]", rule.toString(),
			"rule values retain the prior record-shaped diagnostic string");

		ChatChannelRule blankRule = new ChatChannelRule(" \u2003 ", null, null, null, null, null, null);
		try {
			java.util.UUID.fromString(blankRule.id());
		} catch (IllegalArgumentException invalidId) {
			throw new AssertionError("rule constructor generates a UUID for a blank id", invalidId);
		}
		equals("", blankRule.name(), "null rule names normalize to an empty string");
		equals(List.of(), blankRule.whitelist(), "null whitelist normalizes to an immutable empty list");
		equals(List.of(), blankRule.blacklist(), "null blacklist normalizes to an immutable empty list");
		assertUnmodifiable(blankRule.whitelist(), "empty normalized rule lists remain immutable to Java callers");
	}

	private static void retiredRedactorRecordsKeepValueAndListContracts() {
		check(RetiredChatConfigRedactor.Range.class.isRecord()
			&& RetiredChatConfigRedactor.Result.class.isRecord(),
			"redactor range and result values retain their Java record declarations");
		check(!RetiredChatConfigRedactor.ScanResult.class.isRecord(),
			"redactor scan result remains a Kotlin-owned defensive value type");
		equals(List.of("start", "end"), java.util.Arrays.stream(
			RetiredChatConfigRedactor.Range.class.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getName).toList(),
			"redactor range components retain their names and order");
		try {
			RetiredChatConfigRedactor.ScanResult.class.getConstructor(
				boolean.class, List.class, String.class);
			RetiredChatConfigRedactor.ScanResult.class.getMethod("sourceJsonValid");
			RetiredChatConfigRedactor.ScanResult.class.getMethod("removed");
			RetiredChatConfigRedactor.ScanResult.class.getMethod("unsafeReason");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("redactor scan Java value API changed", exception);
		}
		equals(List.of("status", "removedMembers", "reason", "sourceJsonValid"), java.util.Arrays.stream(
			RetiredChatConfigRedactor.Result.class.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getName).toList(),
			"redactor result components retain their names and order");

		var range = new RetiredChatConfigRedactor.Range(3L, 9L);
		equals(3L, range.start(), "redactor range retains its Java accessor");
		equals(9L, range.end(), "redactor range retains its Java accessor");
		equals(new RetiredChatConfigRedactor.Range(3L, 9L), range,
			"redactor range retains record value equality");
		equals("Range[start=3, end=9]", range.toString(), "redactor range retains record string formatting");

		List<RetiredChatConfigRedactor.Range> source = new ArrayList<>(List.of(range));
		var scan = new RetiredChatConfigRedactor.ScanResult(true, source, null);
		source.clear();
		check(scan.sourceJsonValid() && scan.unsafeReason() == null,
			"redactor scan retains its Java accessors");
		equals(List.of(range), scan.removed(), "redactor scan defensively copies its removed ranges");
		assertUnmodifiable(scan.removed(), "redactor scan ranges remain immutable to Java callers");
		var equalScan = new RetiredChatConfigRedactor.ScanResult(true, List.of(range), null);
		equals(equalScan, scan,
			"redactor scan retains record value equality");
		check(equalScan.hashCode() == scan.hashCode(), "redactor scan retains value hash semantics");
		equals("ScanResult[sourceJsonValid=true, removed=[Range[start=3, end=9]], unsafeReason=null]",
			scan.toString(), "redactor scan retains record string formatting");

		var result = new RetiredChatConfigRedactor.Result(
			RetiredChatConfigRedactor.Status.REDACTED, 1, null, true);
		equals(RetiredChatConfigRedactor.Status.REDACTED, result.status(), "redactor result retains its status accessor");
		equals(1, result.removedMembers(), "redactor result retains its count accessor");
		equals(null, result.reason(), "redactor result retains its nullable reason accessor");
		check(result.sourceJsonValid() && result.cleaned(), "redactor result retains validity and cleaned behavior");
		var equalResult = new RetiredChatConfigRedactor.Result(
			RetiredChatConfigRedactor.Status.REDACTED, 1, null, true);
		equals(equalResult, result,
			"redactor result retains record value equality");
		check(equalResult.hashCode() == result.hashCode(), "redactor result retains value hash semantics");
		equals("Result[status=REDACTED, removedMembers=1, reason=null, sourceJsonValid=true]", result.toString(),
			"redactor result retains record string formatting");
	}

	private static boolean publicJavaEntrypointsRemainStable(ChatChannelsModule module) {
		try {
			var instance = ChatChannelsModule.class.getField("INSTANCE");
			var selectedTab = ChatChannelsModule.class.getMethod("selectedTab");
			var localChatCommand = ChatChannelsModule.class.getMethod("handleLocalChatCommand", String.class);
			return Modifier.isPublic(instance.getModifiers())
				&& Modifier.isStatic(instance.getModifiers())
				&& instance.get(null) == module
				&& Modifier.isPublic(selectedTab.getModifiers())
				&& Modifier.isStatic(selectedTab.getModifiers())
				&& selectedTab.getReturnType() == ChatChannelTabs.Tab.class
				&& Modifier.isPublic(localChatCommand.getModifiers())
				&& Modifier.isStatic(localChatCommand.getModifiers())
				&& localChatCommand.getReturnType() == boolean.class;
		} catch (ReflectiveOperationException exception) {
			return false;
		}
	}

	private static void builtInTabsAndClassification() {
		equals(List.of("all", "party", "guild", "coop", "private", "vanilla"),
			ChatChannel.builtIns().stream().map(ChatChannel::id).toList(), "built-in tabs and no Officer tab");
		equals(ChatChannel.PARTY, ChatChannelClassifier.classify("Party > [MVP+] Alice: hello"), "party route");
		equals(ChatChannel.GUILD, ChatChannelClassifier.classify("Guild > Alice: hello"), "guild route");
		equals(ChatChannel.VANILLA_SYSTEM, ChatChannelClassifier.classify("Officer > Alice: hello"),
			"officer lines use Vanilla/System instead of Guild");
		equals(ChatChannel.COOP, ChatChannelClassifier.classify("Co-op > Alice: hello"), "co-op route");
		equals(ChatChannel.PRIVATE, ChatChannelClassifier.classify("From [VIP] Alice: hello"), "private reply route");
		equals(ChatChannel.VANILLA_SYSTEM, ChatChannelClassifier.classify("A server announcement"), "system fallback");
	}

	private static void tabPrefixesAndInputRouting() {
		equals(List.of(ChatChannelTabs.Tab.NONE, ChatChannelTabs.Tab.ALL, ChatChannelTabs.Tab.PARTY,
			ChatChannelTabs.Tab.GUILD, ChatChannelTabs.Tab.COOP, ChatChannelTabs.Tab.PRIVATE),
			ChatChannelTabs.tabs(), "chat-entry row starts with None and omits the System tab");
		equals("None", ChatChannelTabs.Tab.NONE.label(), "None tab label");
		equals("all", ChatChannelTabs.Tab.NONE.channelId(), "None keeps all messages visible");
		equals(null, ChatChannelTabs.Tab.NONE.messagePrefix(), "None sends ordinary text without a prefix");
		equals("/ac", ChatChannelTabs.Tab.ALL.messagePrefix(), "All message prefix");
		equals("/pc", ChatChannelTabs.Tab.PARTY.messagePrefix(), "Party message prefix");
		equals("all", ChatChannelTabs.Tab.ALL.channelId(), "All channel ID");
		equals("party", ChatChannelTabs.Tab.PARTY.channelId(), "Party channel ID");
		equals("/gc", ChatChannelTabs.Tab.GUILD.messagePrefix(), "Guild message prefix");
		equals("/cc", ChatChannelTabs.Tab.COOP.messagePrefix(), "Co-op message prefix");
		ChatChannelTabs.reset();
		check(!ChatChannelTabs.select(null), "null tab selection is rejected");
		equals(ChatChannelTabs.Tab.NONE, ChatChannelTabs.selected(), "null selection preserves the active tab");
		check(ChatChannelTabs.select(ChatChannelTabs.Tab.PARTY), "first channel selection succeeds");
		check(ChatChannelTabs.select(ChatChannelTabs.Tab.GUILD), "rapid tab selection remains local and succeeds");
		equals("/pc hello", ChatChannelTabs.routeInput(ChatChannelTabs.Tab.PARTY, " hello "),
			"Party input uses /pc");
		equals("/pc hello", ChatChannelTabs.routeInput(ChatChannelTabs.Tab.PARTY, "\u2003hello\u2003"),
			"Party input strips Unicode whitespace with Java String.strip semantics");
		equals("/ac hello", ChatChannelTabs.routeInput(ChatChannelTabs.Tab.ALL, " hello "),
			"All input uses /ac");
		equals(" hello ", ChatChannelTabs.routeInput(ChatChannelTabs.Tab.NONE, " hello "),
			"None preserves ordinary chat without adding or stripping a prefix");
		equals("/gc hello", ChatChannelTabs.routeInput(ChatChannelTabs.Tab.GUILD, "hello"),
			"Guild input uses /gc");
		equals("/cc hello", ChatChannelTabs.routeInput(ChatChannelTabs.Tab.COOP, "hello"),
			"Co-op input uses /cc");
		equals("/r hello", ChatChannelTabs.routeInput(ChatChannelTabs.Tab.PRIVATE, " hello "),
			"Private input uses /r");
		equals("/warp home", ChatChannelTabs.routeInput(ChatChannelTabs.Tab.PRIVATE, "/warp home"),
			"Private leaves slash commands unchanged");
		equals(" /warp home", ChatChannelTabs.routeInput(ChatChannelTabs.Tab.PARTY, " /warp home"),
			"all channel tabs preserve slash command input unchanged");
		equals("\u2003/warp home", ChatChannelTabs.routeInput(ChatChannelTabs.Tab.PARTY, "\u2003/warp home"),
			"Unicode leading whitespace before slash commands remains unchanged");
		equals(null, ChatChannelTabs.routeInput(null, null), "missing tab and text remain null");
		ChatChannelTabs.reset();
	}

	private static void customRulePrecedenceAndFilters() {
		ChatChannelRule broad = new ChatChannelRule("broad", "Broad", "hello", "/pc", List.of(), List.of());
		ChatChannelRule narrow = new ChatChannelRule("narrow", "Narrow", "hello world", "/gc",
			List.of("Alice"), List.of("Muted"));
		String line = "Party > [MVP+] Alice: hello world";
		equals("custom:narrow", ChatChannelClassifier.classify(line, List.of(broad, narrow)).id(),
			"longest matching custom route wins");
		check(!narrow.matches(line, "Muted"), "blacklisted sender is excluded");
		check(!narrow.matches(line, "Bob"), "non-whitelisted sender is excluded");
		check(narrow.matches(line, "Alice"), "whitelisted sender can match");
		// Bob fails the narrow rule's Alice whitelist, so the built-in Party route should receive the line.
		equals(ChatChannel.PARTY, ChatChannelClassifier.classify("Party > Bob: hello world", List.of(narrow)),
			"filtered custom route falls back to built-in route");
		check(ChatChannelFilterRules.isVisible(ChatChannel.ALL.id(), ChatChannel.PARTY.id()),
			"All keeps Party lines in native history");
		check(ChatChannelFilterRules.isVisible(null, ChatChannel.PARTY.id()),
			"missing filter selection keeps every channel visible");
		check(ChatChannelFilterRules.isVisible("  ", ChatChannel.PARTY.id()),
			"blank filter selection keeps every channel visible");
		check(!ChatChannelFilterRules.isVisible("\u00a0", ChatChannel.PARTY.id()),
			"NBSP filter ids retain Java String.isBlank semantics");
		check(ChatChannelFilterRules.isVisible(ChatChannel.VANILLA_SYSTEM.id(),
			ChatChannelClassifier.classify("Officer > Alice: hello").id()),
			"System keeps Officer lines in native history");
		check(!ChatChannelFilterRules.isVisible(ChatChannel.VANILLA_SYSTEM.id(), ChatChannel.PARTY.id()),
			"System excludes Party lines from native history");
	}

	private static void outgoingRoutesPreserveVanillaInput() {
		equals("/pc hello", ChatOutgoingRouter.routedInput(ChatChannel.PARTY, "hello"), "party outgoing prefix");
		equals("/gc hello", ChatOutgoingRouter.routedInput(ChatChannel.GUILD, "hello"), "guild outgoing prefix");
		equals("/cc hello", ChatOutgoingRouter.routedInput(ChatChannel.COOP, "hello"), "co-op outgoing prefix");
		equals("/r hello", ChatOutgoingRouter.routedInput(ChatChannel.PRIVATE, "hello"), "private reply prefix");
		ChatChannel custom = new ChatChannel("custom:test", "Test", "route", true);
		equals("/route hello", ChatOutgoingRouter.routedInput(custom, "hello"), "custom prefix gets slash");
		equals("/pc hello", ChatOutgoingRouter.routedInput(
			new ChatChannel("custom:trimmed", "Trimmed", "  pc  ", true), "hello"),
			"outgoing prefix trims surrounding ASCII whitespace");
		equals("/ac hello", ChatOutgoingRouter.routedInput(ChatChannel.ALL, "hello"), "All outgoing prefix");
		equals(null, ChatOutgoingRouter.routedInput(ChatChannel.PARTY, " /warp home"), "slash command is passed through");
		equals(null, ChatOutgoingRouter.routedInput(ChatChannel.PRIVATE, "  "), "blank input is not routed");
		equals(null, ChatOutgoingRouter.routedInput(ChatChannel.PRIVATE, "\u2003"),
			"Unicode blank input is not routed");
		equals(null, ChatOutgoingRouter.routedInput(ChatChannel.PRIVATE, "\u2003/warp home"),
			"Unicode leading whitespace before a slash command keeps it out of routed chat");
		equals("", ChatReplyConfigRules.normalizeCommand("\u2003help"),
			"reply command normalization retains Java String.trim semantics");
		check(ChatReplyConfigRules.validResponse("\u2003"),
			"reply response validation retains Java String.trim semantics");
	}

	private static void customRegexRulesAreBoundedAndFlexible() {
		check(ChatChannelRegex.validationError(null).isPresent(), "null patterns are rejected safely");
		check(ChatChannelRegex.validationError("\u2003").isPresent(),
			"Unicode whitespace-only patterns retain Java String.isBlank semantics");
		check(ChatChannelRegex.validationError(String.valueOf((char) 0x00A0)).isEmpty(),
			"blank-pattern validation preserves Java String.isBlank whitespace semantics");
		check(ChatChannelRegex.validationError("(cat|dog)[0-9]{2,3}").isEmpty(),
			"groups, alternation, character classes, and bounded quantifiers are accepted");
		check(ChatChannelRegex.matches("(cat|dog)[0-9]{2,3}", "Party > Alice: dog42"),
			"regex rules use find matching over incoming lines");
		check(ChatChannelRegex.matches("^[a-z]+\\s+hello$", "abc hello"),
			"regex anchors and whitespace classes work");
		check(!ChatChannelRegex.matches("^[a-z]+\\s+hello$", "prefix abc hello"),
			"anchors constrain a regex match");
		check(ChatChannelRegex.validationError("(a)\\1").isPresent(), "backreferences are rejected");
		check(ChatChannelRegex.validationError("(?=hello)hello").isPresent(), "lookarounds are rejected");
		check(ChatChannelRegex.validationError("(a{64}){64}").isPresent(),
			"nested bounded repeats cannot exceed the automaton state limit");
		check(!ChatChannelRegex.matches("(a|aa)*b", "a".repeat(256)),
			"ambiguous repeated alternatives remain bounded without a backtracking engine");
		check(ChatChannelRegex.validationError("x".repeat(ChatChannelRegex.MAX_PATTERN_CHARACTERS + 1)).isPresent(),
			"overlong patterns are rejected");
		check(!ChatChannelRegex.matches(".*", "x".repeat(ChatChannelRegex.MAX_INPUT_CHARACTERS + 1)),
			"overlong candidate chat input does not enter the matcher");
		ChatChannelRule regex = new ChatChannelRule("regex", "Regex", "(cat|dog)[0-9]{2,3}", "/pc",
			List.of(), List.of(), ChatChannelRule.MatchMode.REGEX);
		check(regex.matches("Guild > Alice: cat123", "Alice"), "custom rules apply the selected regex mode");
		ChatChannelRule legacy = new ChatChannelRule("legacy", "Legacy", "hello", "/pc", List.of(), List.of());
		equals(ChatChannelRule.MatchMode.TEXT, legacy.matchMode(), "old constructor and saved rules default to text");
	}

	private static void activeCaptureAndHelpRouting() {
		check(ChatChannelRuntimeGate.shouldCapture(true), "enabled chat module captures inbound lines");
		check(!ChatChannelRuntimeGate.shouldCapture(false), "disabled chat module leaves inbound lines to vanilla");
		check(!ChatChannelRuntimeGate.shouldSuppressVanilla(true, true),
			"channel filtering keeps the native Vanilla overlay visible");
		check(!ChatChannelRuntimeGate.shouldSuppressVanilla(false, true),
			"disabled modules leave the native Vanilla overlay visible");
		check(!ChatChannelRuntimeGate.shouldSuppressVanilla(true, false, true, false),
			"channel filtering never replaces the native chat with a separate HUD");
		equals(List.of("all"), ChatChannelHelpRouting.destinationIds("all"), "help in All is stored once");
		equals(List.of("all", "party"), ChatChannelHelpRouting.destinationIds("party"),
			"one local help message belongs to All and the selected native-chat filter");
		equals(List.of("all", "\u00a0"), ChatChannelHelpRouting.destinationIds("\u00a0"),
			"NBSP help destinations retain Java String.isBlank semantics");
		equals("party", ChatChannelHelpRouting.localOriginId(true, "party"),
			"help with channel tabs enabled follows the selected filter");
		equals("\u00a0", ChatChannelHelpRouting.localOriginId(true, "\u00a0"),
			"NBSP help origins retain Java String.isBlank semantics");
		equals("all", ChatChannelHelpRouting.localOriginId(false, "party"),
			"help with channel tabs disabled stays in All despite a stale selection");
		check(ChatChannelHelpRouting.shouldEchoToVanilla(false), "local help uses vanilla chat rendering");
		check(ChatChannelHelpRouting.shouldEchoToVanilla(true), "local help stays in vanilla chat with channel tabs");
	}

	private static void messageComponentInteractionStylesRemainAvailable() {
		Component message = Component.empty()
			.append(Component.literal("run").withStyle(style -> style
				.withClickEvent(new ClickEvent.RunCommand("/party list"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Run the party command")))))
			.append(Component.literal(" suggest").withStyle(style -> style
				.withClickEvent(new ClickEvent.SuggestCommand("/party invite "))))
			.append(Component.literal(" copy").withStyle(style -> style
				.withClickEvent(new ClickEvent.CopyToClipboard("copied value"))));
		List<Component> segments = message.toFlatList();
		check(segments.stream().anyMatch(segment -> segment.getStyle().getClickEvent()
			instanceof ClickEvent.RunCommand run && run.command().equals("/party list")),
			"message components retain run-command click events");
		check(segments.stream().anyMatch(segment -> segment.getStyle().getClickEvent()
			instanceof ClickEvent.SuggestCommand suggest && suggest.command().equals("/party invite ")),
			"message components retain suggest-command click events");
		check(segments.stream().anyMatch(segment -> segment.getStyle().getClickEvent()
			instanceof ClickEvent.CopyToClipboard copy && copy.value().equals("copied value")),
			"message components retain clipboard click events");
		check(segments.stream().anyMatch(segment -> segment.getStyle().getHoverEvent()
			instanceof HoverEvent.ShowText hover && hover.value().getString().equals("Run the party command")),
			"message components retain hover text events");
	}

	private static void wrappedLinesCacheByChannelWidthAndRevision() {
		ChatWrappedLineCache<String> cache = new ChatWrappedLineCache<>();
		ChatWrappedLineCache<String> nullChannelCache = new ChatWrappedLineCache<>();
		equals(List.of("null-key"), nullChannelCache.getOrCompute(null, 320, 1L,
			() -> List.of(1L), value -> value, value -> 0L, value -> List.of("null-key")),
			"wrapped-line cache continues accepting a null channel key");
		List<TestWrappedMessage> history = new ArrayList<>(List.of(
			new TestWrappedMessage(1L, 0L, "one"),
			new TestWrappedMessage(2L, 0L, "two"),
			new TestWrappedMessage(3L, 0L, "three")));
		Map<Long, Integer> wrapCalls = new HashMap<>();
		AtomicInteger historyReads = new AtomicInteger();
		java.util.function.Supplier<List<TestWrappedMessage>> currentHistory = () -> {
			historyReads.incrementAndGet();
			return history;
		};
		java.util.function.Function<TestWrappedMessage, List<String>> wrap = message -> {
			wrapCalls.merge(message.id(), 1, Integer::sum);
			return List.of(message.text() + "-wrapped");
		};
		equals(List.of("one-wrapped", "two-wrapped", "three-wrapped"),
			cache.getOrCompute("all", 320, 1L, currentHistory, TestWrappedMessage::id,
				TestWrappedMessage::displayRevision, wrap), "first layout wraps all visible messages");
		equals(List.of("one-wrapped", "two-wrapped", "three-wrapped"),
			cache.getOrCompute("all", 320, 1L, currentHistory, TestWrappedMessage::id,
				TestWrappedMessage::displayRevision, wrap), "same cache key reuses wrapped lines");
		equals(1, historyReads.get(), "cached draws do not enumerate the message history again");

		history.add(new TestWrappedMessage(4L, 0L, "four"));
		cache.getOrCompute("all", 320, 2L, currentHistory, TestWrappedMessage::id,
			TestWrappedMessage::displayRevision, wrap);
		equals(Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1), wrapCalls,
			"a normal incoming message wraps only its new segment");

		history.set(1, new TestWrappedMessage(2L, 1L, "translated-two"));
		equals(List.of("one-wrapped", "translated-two-wrapped", "three-wrapped", "four-wrapped"),
			cache.getOrCompute("all", 320, 3L, currentHistory, TestWrappedMessage::id,
				TestWrappedMessage::displayRevision, wrap), "translation updates the changed message segment");
		equals(2, wrapCalls.get(2L), "translation rewraps only the message whose display changed");

		history.removeIf(message -> message.id() == 3L);
		equals(List.of("one-wrapped", "translated-two-wrapped", "four-wrapped"),
			cache.getOrCompute("all", 320, 4L, currentHistory, TestWrappedMessage::id,
				TestWrappedMessage::displayRevision, wrap), "deletion drops the removed segment");
		equals(1, wrapCalls.get(1L), "deleting another message preserves unchanged wrapped segments");

		// Simulate a custom-route add/reclassification that brings an older message into a selected tab.
		cache.getOrCompute("party", 320, 4L, () -> List.of(history.get(0), history.get(1)),
			TestWrappedMessage::id, TestWrappedMessage::displayRevision, wrap);
		equals(3, wrapCalls.get(2L), "a tab change wraps its messages for the new layout");
		List<TestWrappedMessage> reclassified = List.of(history.get(1), history.get(2));
		cache.getOrCompute("party", 320, 5L, () -> reclassified,
			TestWrappedMessage::id, TestWrappedMessage::displayRevision, wrap);
		equals(2, wrapCalls.get(4L), "route reclassification wraps only newly routed message segments");
		cache.getOrCompute("party", 320, 6L, () -> List.of(history.get(1)),
			TestWrappedMessage::id, TestWrappedMessage::displayRevision, wrap);
		equals(3, wrapCalls.get(2L), "route deletion preserves the surviving message segment");
		equals(List.of("translated-two-wrapped"), cache.getOrCompute("party", 320, 6L,
			() -> List.of(history.get(1)), TestWrappedMessage::id, TestWrappedMessage::displayRevision, wrap),
			"route deletion removes no-longer-eligible messages without rewrapping survivors");

		cache.getOrCompute("party", 280, 6L, () -> List.of(history.get(1)),
			TestWrappedMessage::id, TestWrappedMessage::displayRevision, wrap);
		equals(4, wrapCalls.get(2L), "viewport width change rebuilds the active layout");
		cache.invalidate();
		cache.getOrCompute("party", 280, 6L, () -> List.of(history.get(1)),
			TestWrappedMessage::id, TestWrappedMessage::displayRevision, wrap);
		equals(5, wrapCalls.get(2L), "explicit invalidation rebuilds the active layout");

		history.clear();
		equals(List.of(), cache.getOrCompute("all", 320, 7L, currentHistory, TestWrappedMessage::id,
			TestWrappedMessage::displayRevision, wrap), "clear/disconnect drops every cached segment");
		history.add(new TestWrappedMessage(5L, 0L, "after-disconnect"));
		equals(List.of("after-disconnect-wrapped"), cache.getOrCompute("all", 320, 8L, currentHistory,
			TestWrappedMessage::id, TestWrappedMessage::displayRevision, wrap),
			"new messages after disconnect start with a clean cache");
	}

	private record TestWrappedMessage(long id, long displayRevision, String text) { }

	private static void animatedWrappedLinesRefreshOnlyKnownMatches() {
		ChatWrappedLineCache<String> cache = new ChatWrappedLineCache<>();
		List<TestAnimatedWrappedMessage> history = List.of(
			new TestAnimatedWrappedMessage(1L, 0L, "Alice", true),
			new TestAnimatedWrappedMessage(2L, 0L, "ordinary", false),
			new TestAnimatedWrappedMessage(3L, 0L, "Bob", true));
		AtomicInteger frame = new AtomicInteger(1);
		AtomicInteger historyReads = new AtomicInteger();
		Map<Long, Integer> wrapCalls = new HashMap<>();
		Map<Long, Integer> matchChecks = new HashMap<>();
		java.util.function.Supplier<List<TestAnimatedWrappedMessage>> currentHistory = () -> {
			historyReads.incrementAndGet();
			return history;
		};
		java.util.function.Function<TestAnimatedWrappedMessage, List<String>> wrap = message -> {
			wrapCalls.merge(message.id(), 1, Integer::sum);
			return List.of(message.text() + (message.animated() ? "-frame-" + frame.get() : "-static"));
		};
		List<String> displayed = cache.getOrCompute("all", 320, 1L, 1L, currentHistory,
			TestAnimatedWrappedMessage::id, TestAnimatedWrappedMessage::displayRevision,
			message -> {
				matchChecks.merge(message.id(), 1, Integer::sum);
				return message.animated();
			}, wrap);
		frame.set(2);
		cache.getOrCompute("all", 320, 1L, 2L, currentHistory,
			TestAnimatedWrappedMessage::id, TestAnimatedWrappedMessage::displayRevision,
			message -> {
				matchChecks.merge(message.id(), 1, Integer::sum);
				return message.animated();
			}, wrap);
		equals(List.of("Alice-frame-2", "ordinary-static", "Bob-frame-2"), displayed,
			"frame change updates already displayed animated segments");
		equals(1, historyReads.get(), "animation frame does not enumerate the message history");
		equals(Map.of(1L, 2, 2L, 1, 3L, 2), wrapCalls,
			"animation rewraps only messages already known to contain animated profile names");
		equals(Map.of(1L, 1, 2L, 1, 3L, 1), matchChecks,
			"animation frame reuses the cached profile-name match set");
	}

	private record TestAnimatedWrappedMessage(long id, long displayRevision, String text, boolean animated) { }

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static boolean hasKotlinMetadata(Class<?> type) {
		return java.util.Arrays.stream(type.getDeclaredAnnotations()).anyMatch(annotation ->
			annotation.annotationType().getName().equals("kotlin.Metadata"));
	}

	private static void equals(Object expected, Object actual, String message) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
		}
	}
}
