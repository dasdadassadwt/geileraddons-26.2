package geiler.addons.client.chat;

import geiler.addons.client.module.impl.ChatModule;

import net.minecraft.ReportedException;
import net.minecraft.network.chat.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Offline checks for the direct-message chat helper and its Chat settings row. */
public final class ChatHelperChecks {
	private ChatHelperChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		hypixelLines();
		triggerRules();
		senderRules();
		vanillaComponents();
		settingsDefaults();
		moduleIndependence();
		shutdownContract();
		System.out.println("Chat helper checks passed");
	}

	private static void hypixelLines() {
		ChatHelperRules.Incoming dm = ChatHelperRules.incomingFromText("From Alice: crash");
		check(dm != null && "Alice".equals(dm.getSender()) && "crash".equals(dm.getBody()),
			"a plain Hypixel DM is parsed into sender and body");
		dm = ChatHelperRules.incomingFromText("From [VIP] Alice: crash");
		check(dm != null && "Alice".equals(dm.getSender()), "a rank prefix is not part of the sender name");
		dm = ChatHelperRules.incomingFromText("From [YOUTUBE] [MVP+] Alice: crash");
		check(dm != null && "Alice".equals(dm.getSender()), "repeated rank prefixes are skipped");
		dm = ChatHelperRules.incomingFromText("\u00a77From \u00a7b[MVP+] \u00a7fAlice\u00a77: \u00a7fcrash");
		check(dm != null && "Alice".equals(dm.getSender()) && "crash".equals(dm.getBody()),
			"section-sign formatting codes are stripped before parsing");
		check(ChatHelperRules.incomingFromText("To Alice: crash") == null,
			"an outgoing DM line is not an incoming trigger");
		check(ChatHelperRules.incomingFromText("Alice: crash") == null,
			"public chat without the From prefix is not a DM");
		check(ChatHelperRules.incomingFromText("From Alice: crash test") != null
			&& !ChatHelperRules.matchesTrigger(ChatHelperRules.incomingFromText("From Alice: crash test").getBody(), "crash"),
			"a body containing the trigger word is not an exact match");
		check(ChatHelperRules.incomingFromText("From Server: crash") != null
			&& ChatHelperRules.isNonPlayerSender(ChatHelperRules.incomingFromText("From Server: crash").getSender()),
			"a server sender is parsed but rejected as a non-player");
		check(ChatHelperRules.incomingFromText("From [SERVER] Server: crash") != null
			&& ChatHelperRules.isNonPlayerSender(ChatHelperRules.incomingFromText("From [SERVER] Server: crash").getSender()),
			"a ranked server sender is parsed but rejected as a non-player");
		dm = ChatHelperRules.incomingFromText("From Alice: crash.");
		check(dm != null && !ChatHelperRules.matchesTrigger(dm.getBody(), "crash"),
			"a trailing period makes the body a different message");
		dm = ChatHelperRules.incomingFromText("From Alice: crash: now");
		check(dm != null && !ChatHelperRules.matchesTrigger(dm.getBody(), "crash"),
			"a body with a second colon is not the bare trigger");
	}

	private static void triggerRules() {
		equals("crash", ChatHelperRules.normalizedTrigger("  crash  "), "the trigger is trimmed");
		equals(null, ChatHelperRules.normalizedTrigger("   "), "a blank trigger disables matching");
		equals(null, ChatHelperRules.normalizedTrigger(null), "a missing trigger disables the hook");
		check(ChatHelperRules.matchesTrigger(" crash ", "Crash"), "matching is trimmed and case-insensitive");
		check(ChatHelperRules.matchesTrigger("CRASH", "crash"), "uppercase bodies match");
		check(!ChatHelperRules.matchesTrigger("crash test", "crash"), "substring matches are rejected");
		check(!ChatHelperRules.matchesTrigger("crash.", "crash"), "punctuation makes a different body");
		check(!ChatHelperRules.matchesTrigger("crash", ""), "an empty trigger never matches");
		check(!ChatHelperRules.matchesTrigger("crash", "   "), "a blank trigger never matches");
		check(!ChatHelperRules.matchesTrigger(null, "crash"), "a missing body never matches");
		check(!ChatHelperRules.matchesTrigger("crash", null), "a null trigger never matches");
	}

	private static void senderRules() {
		equals("Alice", ChatHelperRules.playerName("[MVP+] Alice"), "ranked display names resolve to a username");
		equals("Alice", ChatHelperRules.playerName("Alice"), "bare usernames resolve unchanged");
		equals("Server", ChatHelperRules.playerName("[SERVER] Server"), "a server display still resolves for the denylist");
		equals(null, ChatHelperRules.playerName("Not A Username"), "display names with spaces are not players");
		equals(null, ChatHelperRules.playerName(null), "a missing sender is not a player");
		check(ChatHelperRules.isLocalSender("[VIP] Alice", "alice"), "the local player is recognised case-insensitively");
		check(!ChatHelperRules.isLocalSender("Alice", "Bob"), "a different sender is allowed");
		check(!ChatHelperRules.isLocalSender("Alice", null), "a missing local name does not match");
		check(ChatHelperRules.isNonPlayerSender("Server"), "the server sender is rejected");
		check(ChatHelperRules.isNonPlayerSender("console"), "the console sender is rejected");
		check(ChatHelperRules.isNonPlayerSender("[SERVER] System"), "a ranked system sender is rejected");
		check(ChatHelperRules.isNonPlayerSender(null), "a missing sender is rejected");
		check(!ChatHelperRules.isNonPlayerSender("[VIP] Alice"), "a player sender is allowed");
	}

	private static void vanillaComponents() {
		Component incoming = Component.translatable("commands.message.display.incoming",
			Component.literal("[VIP] Alice"), Component.literal(" crash "));
		ChatHelperRules.Incoming dm = ChatHelperRules.incomingPlayerChat(incoming);
		check(dm != null && "[VIP] Alice".equals(dm.getSender()) && " crash ".equals(dm.getBody()),
			"the vanilla incoming translatable component exposes sender and body");
		check(dm != null && ChatHelperRules.matchesTrigger(dm.getBody(), "crash"),
			"the vanilla incoming body matches after trimming");
		check(ChatHelperRules.isLocalSender(dm.getSender(), "Alice"),
			"the vanilla ranked sender still resolves to the local player");

		check(ChatHelperRules.incomingPlayerChat(Component.translatable("commands.message.display.outgoing",
			Component.literal("Bob"), Component.literal("crash"))) == null,
			"the outgoing translatable key is not an incoming trigger");
		check(ChatHelperRules.incomingPlayerChat(Component.translatable("chat.type.text",
			Component.literal("Alice"), Component.literal("crash"))) == null,
			"public player chat uses a different key and cannot trigger the chat helper");
		check(ChatHelperRules.incomingPlayerChat(Component.literal("From Alice: crash")) == null,
			"player-chat parsing never falls back to a formatted text match");
		check(ChatHelperRules.incomingSystemChat(Component.literal("From Alice: crash")) != null,
			"system chat falls back to the Hypixel From line");
		check(ChatHelperRules.incomingPlayerChat(Component.translatable("commands.message.display.incoming")) == null,
			"a translatable message without arguments is ignored");
	}

	private static void settingsDefaults() {
		ChatModule chat = ChatModule.INSTANCE;
		check(chat.chatHelper().value(), "the chat helper is on by default");
		equals("crash", chat.chatHelperTrigger().value(), "the default trigger is set");
		check(chat.booleanSettings().stream().anyMatch(setting -> "Chat Helper".equals(setting.name())),
			"the chat helper is a persisted Chat setting");
		check(chat.textSettings().stream().anyMatch(setting -> "Chat Helper Trigger".equals(setting.name())),
			"the chat helper trigger is a persisted Chat setting");
	}

	private static void moduleIndependence() {
		ChatModule chat = ChatModule.INSTANCE;
		boolean moduleEnabled = chat.isEnabled();
		boolean helperEnabled = chat.chatHelper().value();
		try {
			chat.setEnabled(false);
			check(ChatHelper.isEnabled(), "the chat helper stays on while the Chat module is off");
			chat.chatHelper().setValue(false);
			check(!ChatHelper.isEnabled(), "the chat helper switch still disables it");
		} finally {
			chat.chatHelper().setValue(helperEnabled);
			chat.setEnabled(moduleEnabled);
		}
	}

	private static void shutdownContract() {
		// The client task loop and packet handler recover from ordinary exceptions, so the shutdown
		// report must be classified as non-recoverable.
		Object gate;
		Method shutdown;
		try {
			Class<?> type = Class.forName("geiler.addons.client.chat.ChatHelper");
			gate = type.getField("INSTANCE").get(null);
			shutdown = type.getDeclaredMethod("shutdownOnClientThread");
			shutdown.setAccessible(true);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("cannot reach the chat helper: " + e);
		}
		try {
			shutdown.invoke(gate);
			throw new AssertionError("the chat helper must raise the shutdown report");
		} catch (InvocationTargetException e) {
			Throwable thrown = e.getCause();
			check(thrown instanceof ReportedException,
				"the chat helper throws a ReportedException, not " + thrown);
			ReportedException reported = (ReportedException) thrown;
			check(reported.getCause() instanceof OutOfMemoryError,
				"the cause is an OutOfMemoryError");
			check(reported.getCause().getStackTrace().length == 0,
				"the cause carries no stack frame");
			check(reported.getStackTrace().length == 0,
				"the ReportedException carries no stack frame");
			check("Ticking player".equals(reported.getReport().getTitle()),
				"the report keeps a vanilla description");
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("the chat helper did not throw a report: " + e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void equals(Object expected, Object actual, String message) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
		}
	}
}
