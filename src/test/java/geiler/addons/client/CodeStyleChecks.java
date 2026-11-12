package geiler.addons.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ratchets from docs/engineering-style.md. The numbers may only go down: delete a screen or merge a
 * facade pair, then lower the baseline in the same change.
 */
public final class CodeStyleChecks {
	private static final Path ROOT = Path.of("src/client/kotlin");
	/** Separate screens that remain on purpose; new per-module hubs must be inline settings instead. */
	private static final Set<String> SCREEN_ALLOWLIST = Set.of(
		"BlockPickerScreen", "ClickGuiScreen", "ClickGuiScreenImpl", "ConfigResetScreen", "ConfigTransferScreen",
		"DungeonFloorPickerScreen", "DungeonGuideEditorScreen", "DungeonGuideStagesScreen", "FolderManagerScreen",
		"GuiColorPickerScreen", "HotbarBindingCaptureScreen", "InventoryButtonPickerScreen", "InventoryButtonTextScreen", "MacroShareScreen", "MoveUiScreen",
		"PlayerSizeEditorScreen", "ScoreboardFontImportScreen", "ScratchMacroEditorScreen", "SecretWaypointEditorScreen",
		"TikiCoordManagerScreen");
	private static final int MAX_FACADE_PAIRS = 62;
	private static final int MAX_FILE_LINES = 4300;
	private static final Pattern SCREEN_SUBCLASS = Pattern.compile("class\\s+(\\w+)[^{]*?:\\s*Screen\\(", Pattern.DOTALL);

	private CodeStyleChecks() { }

	public static void run() throws IOException {
		if (!Files.isDirectory(ROOT)) return;
		Set<String> names = new TreeSet<>();
		int longest = 0;
		Set<String> screens = new TreeSet<>();
		try (Stream<Path> files = Files.walk(ROOT)) {
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".kt"))::iterator) {
				String name = file.getFileName().toString();
				names.add(name.substring(0, name.length() - 3));
				String text = Files.readString(file);
				longest = Math.max(longest, (int) text.lines().count());
				var matcher = SCREEN_SUBCLASS.matcher(text);
				while (matcher.find()) screens.add(matcher.group(1));
			}
		}
		int pairs = 0;
		for (String name : names) {
			for (String suffix : new String[] { "Core", "Logic", "Support" }) if (names.contains(name + suffix)) pairs++;
		}
		check(pairs <= MAX_FACADE_PAIRS, "facade + Core/Logic/Support pairs grew to " + pairs + " (limit " + MAX_FACADE_PAIRS + ")");
		check(longest <= MAX_FILE_LINES, "a Kotlin file grew to " + longest + " lines (limit " + MAX_FILE_LINES + ")");
		for (String screen : screens) {
			check(SCREEN_ALLOWLIST.contains(screen), "new separate screen " + screen + "; use inline module settings instead");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
