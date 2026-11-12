package geiler.addons.client.module.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Focused parity and diagnostics checks for Crop Fever Timer. */
public final class CropFeverTimerModuleChecks {
	private CropFeverTimerModuleChecks() { }

	public static void main(String[] args) throws IOException {
		run();
		System.out.println("Crop Fever Timer module checks passed.");
	}

	public static void run() throws IOException {
		String module = read("src", "client", "kotlin", "geiler", "addons", "client", "module", "impl",
			"CropFeverTimerModule.kt");
		String state = read("src", "client", "kotlin", "geiler", "addons", "client", "farming",
			"CropFeverTimerState.kt");
		if (!Files.exists(Path.of("..", "archive", "26.1.2"))) {
			System.out.println("Crop Fever baseline comparison skipped: no 26.1.2 source next to this checkout");
			return;
		}
		String legacyModule = read("..", "26.1.2", "src", "client", "java", "geiler", "addons", "client",
			"module", "impl", "CropFeverTimerModule.java");
		String legacyState = read("..", "26.1.2", "src", "client", "java", "geiler", "addons", "client",
			"farming", "CropFeverTimerState.java");

		checkStateParity(state, legacyState);
		checkModuleParity(module, legacyModule);
		checkAutomaticDiagnosticsAndClientThread(module);
	}

	private static void checkStateParity(String state, String legacyState) {
		check(extractStringConstant(state, "START_MESSAGE").equals(extractStringConstant(legacyState, "START_MESSAGE")),
			"Crop Fever start chat text must match frozen 26.1.2 exactly");
		check(extractStringConstant(state, "END_MESSAGE").equals(extractStringConstant(legacyState, "END_MESSAGE")),
			"Crop Fever cure chat text must match frozen 26.1.2 exactly");
		check(state.contains("60_000_000_000L") && legacyState.contains("60_000_000_000L"),
			"Crop Fever duration must remain exactly 60 seconds");

		String[][] stateRules = {
			{"ChatText.stripForMatch(message)", "ChatText.stripForMatch(message)"},
			{"deadlineNanos = nowNanos + DURATION_NANOS", "deadlineNanos = nowNanos + DURATION_NANOS"},
			{"val wasActive = active && deadlineNanos - nowNanos > 0L",
				"boolean wasActive = active && deadlineNanos - nowNanos > 0L"},
			{"val remainingNanos = deadlineNanos - nowNanos",
				"long remainingNanos = deadlineNanos - nowNanos"},
			{"if (!active || remainingNanos <= 0L)", "if (!active || remainingNanos <= 0L)"},
			{"return remainingNanos / 1_000_000_000.0", "return remainingNanos / 1_000_000_000.0"},
			{"deadlineNanos = 0L", "deadlineNanos = 0L"},
			{"active = false", "active = false"},
		};
		for (String[] rule : stateRules) {
			check(state.contains(rule[0]) && legacyState.contains(rule[1]),
				"Crop Fever countdown semantics must match frozen 26.1.2: " + rule[0]);
		}
	}

	private static void checkModuleParity(String module, String legacyModule) {
		List<String> persistedBehavior = List.of(
			"Crop Fever Timer",
			"Counts down the remaining time on Crop Fever.",
			"Category.FARMING",
			"BooleanSetting(\"Background\", true)",
			"ColorSetting(\"Text Color\", 255, 85, 216, 255)",
			"ColorSetting(\"Background Color\", 20, 16, 28, 190)",
			"NumberSetting(\"Text Scale\", 0.75f, 2.0f, 1.0f)",
			"SettingGroup(\"Appearance\"",
			"CROP FEVER 42.00s",
			"%.2fs",
			"crop_fever_timer");
		for (String value : persistedBehavior) {
			check(module.contains(value) && legacyModule.contains(value),
				"Crop Fever module identity, settings, or display must match frozen 26.1.2: " + value);
		}
	}

	private static void checkAutomaticDiagnosticsAndClientThread(String module) {
		check(module.contains("GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.FARMING, name(), true)"),
			"module diagnostics must start automatically when Crop Fever Timer is enabled");
		check(module.contains("GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.FARMING, name(), false)"),
			"module diagnostics must stop when Crop Fever Timer is disabled");
		check(module.contains("if (diagnosticsEnabled) {\n            reportAutomaticStatus("),
			"enabled timer ticks must report automatic status");
		check(module.contains("DIAGNOSTIC_STATUS_MIN_INTERVAL_TICKS = 20L")
				&& module.contains("DIAGNOSTIC_STATUS_HEARTBEAT_TICKS = 100L")
				&& module.contains("MAX_DIAGNOSTIC_DETAILS_PER_TICK = 8")
				&& module.contains("MAX_DIAGNOSTIC_LINE_LENGTH = 480"),
			"automatic timer diagnostics must keep their cadence and output bounds");
		check(module.contains("if (!allowDisabled && !isEnabled()) return")
				&& module.contains("if (suppressedDiagnosticDetails <= 0) return"),
			"timer diagnostic events must remain gated and suppressed output must be bounded");

		check(occurrences(module, "if (!mc.isSameThread)") == 3
				&& module.contains("mc.execute { tick() }")
				&& module.contains("mc.execute { onChatMessage(rawMessage) }")
				&& module.contains("mc.execute { onWorldChanged() }"),
			"tick, chat, and world reset callbacks must marshal to the Minecraft client thread");
	}

	private static String extractStringConstant(String source, String name) {
		Matcher matcher = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(source);
		if (!matcher.find()) throw new AssertionError("missing string constant: " + name);
		return matcher.group(1);
	}

	private static int occurrences(String source, String needle) {
		int count = 0;
		int index = 0;
		while ((index = source.indexOf(needle, index)) >= 0) {
			count++;
			index += needle.length();
		}
		return count;
	}

	private static String read(String... path) throws IOException {
		Path sourcePath = Path.of(path[0]);
		for (int index = 1; index < path.length; index++) sourcePath = sourcePath.resolve(path[index]);
		// The frozen 26.1.2 baseline now lives under ../archive/.
		if (!Files.exists(sourcePath) && path.length > 2 && path[0].equals("..") && path[1].equals("26.1.2")) {
			sourcePath = Path.of("..", "archive");
			for (int index = 1; index < path.length; index++) sourcePath = sourcePath.resolve(path[index]);
		}
		return Files.readString(sourcePath).replace("\r\n", "\n");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
