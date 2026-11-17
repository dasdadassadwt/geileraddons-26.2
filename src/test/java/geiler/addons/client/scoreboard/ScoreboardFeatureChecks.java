package geiler.addons.client.scoreboard;

import geiler.addons.client.gui.ScoreboardFontImportScreen;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.impl.ScoreboardModule;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Modifier;
import java.util.List;

/** Isolated checks for scoreboard module gating, layout JSON, and panel measurement. Not wired into OfflineChecks. */
public final class ScoreboardFeatureChecks {
	private ScoreboardFeatureChecks() {
	}

	public static void run() {
		scoreboardSidebarMixinTargets26_2Hud();
		moduleSettingsLiveInClickGui();
		moduleLifecycleControlsHudVisibility();
		scoreboardRowsAndSidebarRoutingFollowLiveSnapshot();
		scoreboardFontImporterKeepsItsPublicScreenApi();
		javaRecordAbiIsPreserved();
		snapshotJavaAbiAndValueSemanticsArePreserved();
		legacyJsonFieldNamesAreStillRead();
		codecRoundTripKeepsEditableOrder();
		codecRejectsMalformedAndOversizedInput();
		geometryHonorsFixedAndAutomaticDimensions();
		fixedWidthTextStaysInsideThePanel();
	}

	private static void scoreboardSidebarMixinTargets26_2Hud() {
		try {
			String source = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "java", "geiler",
				"addons", "client", "mixin", "ScoreboardSidebarMixin.java"));
			String config = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "resources",
				"geileraddons.client.mixins.json"));
			if (!source.contains("@Mixin(Hud.class)") || !source.contains("@Inject(method = \"extractScoreboardSidebar\"")
				|| !config.contains("\"ScoreboardSidebarMixin\"")) {
				throw new AssertionError("The custom scoreboard must suppress vanilla's sidebar through the 26.2 Hud hook.");
			}
		} catch (java.io.IOException exception) {
			throw new AssertionError("Could not inspect the scoreboard sidebar mixin target.", exception);
		}
	}

	private static void scoreboardFontImporterKeepsItsPublicScreenApi() {
		assertScreenApi(ScoreboardFontImportScreen.class);
	}

	private static void assertScreenApi(Class<?> type) {
		if (!Screen.class.isAssignableFrom(type) || !Modifier.isFinal(type.getModifiers())) {
			throw new AssertionError(type.getSimpleName() + " must remain a final Screen implementation.");
		}
		try {
			type.getConstructor(Screen.class);
			java.lang.reflect.Method pause = type.getMethod("isPauseScreen");
			java.lang.reflect.Method close = type.getMethod("onClose");
			java.lang.reflect.Method render = type.getMethod("extractRenderState",
				net.minecraft.client.gui.GuiGraphicsExtractor.class, int.class, int.class, float.class);
			if (pause.getReturnType() != boolean.class || pause.getDeclaringClass() != type
				|| close.getReturnType() != void.class || close.getDeclaringClass() != type
				|| render.getReturnType() != void.class || render.getDeclaringClass() != type) {
				throw new AssertionError(type.getSimpleName() + " changed its inherited screen API.");
			}
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(type.getSimpleName() + " lost its public Screen constructor or overrides.", exception);
		}
	}

	private static void javaRecordAbiIsPreserved() {
		assertRecord(ScoreboardGeometry.Box.class, "width", "height", "lineHeight", "padding");
		assertRecord(ScoreboardLayoutRenderer.Metrics.class, "box", "scaledWidth", "scaledHeight");
		assertRecord(ScoreboardLayoutState.ReloadResult.class, "accepted", "layout");
		assertRecord(ScoreboardTextLayout.FittedLine.class, "left", "right", "leftWidth", "rightWidth", "gap");
		assertRecord(ScoreboardFontService.FontChoice.class, "id", "label", "imported");
	}

	private static void snapshotJavaAbiAndValueSemanticsArePreserved() {
		try {
			ScoreboardSnapshot.class.getConstructor(boolean.class, Component.class, List.class);
			ScoreboardSnapshot.Entry.class.getConstructor(String.class, Component.class, Component.class);
			assertMethod(ScoreboardSnapshot.class, "active", boolean.class);
			assertMethod(ScoreboardSnapshot.class, "title", Component.class);
			assertMethod(ScoreboardSnapshot.class, "entries", List.class);
			assertMethod(ScoreboardSnapshot.Entry.class, "owner", String.class);
			assertMethod(ScoreboardSnapshot.Entry.class, "label", Component.class);
			assertMethod(ScoreboardSnapshot.Entry.class, "value", Component.class);
			assertStaticMethod(ScoreboardSnapshot.class, "empty", ScoreboardSnapshot.class);
			assertStaticMethod(ScoreboardSnapshot.class, "preview", ScoreboardSnapshot.class);
			assertStaticMethod(ScoreboardSnapshot.class, "capture", ScoreboardSnapshot.class,
				net.minecraft.client.Minecraft.class);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("ScoreboardSnapshot lost a public Java constructor or accessor.", exception);
		}

		// Java record metadata is absent on this Kotlin class, so verify the constructor, accessor, and value contract directly.
		java.util.ArrayList<ScoreboardSnapshot.Entry> entries = new java.util.ArrayList<>();
		ScoreboardSnapshot.Entry entry = new ScoreboardSnapshot.Entry("owner", Component.literal("label"), Component.literal("1"));
		entries.add(entry);
		ScoreboardSnapshot snapshot = new ScoreboardSnapshot(true, null, entries);
		entries.clear();
		if (!snapshot.title().getString().isEmpty() || snapshot.entries().size() != 1 || snapshot.entries().get(0) != entry) {
			throw new AssertionError("Snapshot construction must normalize a null title and copy the entry list.");
		}
		try {
			snapshot.entries().add(entry);
			throw new AssertionError("Snapshot entries must remain immutable after construction.");
		} catch (UnsupportedOperationException expected) {
			// List.copyOf makes this snapshot boundary immutable.
		}
		ScoreboardSnapshot emptyValues = new ScoreboardSnapshot(true, null, null);
		if (!emptyValues.entries().isEmpty()) {
			throw new AssertionError("A null snapshot entry list must normalize to an empty list.");
		}
		ScoreboardSnapshot empty = ScoreboardSnapshot.empty();
		ScoreboardSnapshot preview = ScoreboardSnapshot.preview();
		if (empty != ScoreboardSnapshot.empty() || empty.active() || !empty.title().getString().isEmpty()
			|| !empty.entries().isEmpty() || preview != ScoreboardSnapshot.preview()
			|| !preview.active() || !preview.title().getString().equals("Example Server") || preview.entries().size() != 3
			|| !preview.entries().get(0).owner().equals("preview-1")
			|| !preview.entries().get(0).label().getString().equals("Profile")
			|| !preview.entries().get(0).value().getString().equals("Level 42")
			|| ScoreboardSnapshot.capture(null) != empty) {
			throw new AssertionError("Empty/preview singleton identity or capture delegation changed.");
		}
		ScoreboardSnapshot.Entry equalEntry = new ScoreboardSnapshot.Entry("owner", Component.literal("label"), Component.literal("1"));
		ScoreboardSnapshot equalSnapshot = new ScoreboardSnapshot(true, Component.empty(), List.of(equalEntry));
		int expectedEntryHash = java.util.Objects.hashCode(entry.owner());
		expectedEntryHash = 31 * expectedEntryHash + java.util.Objects.hashCode(entry.label());
		expectedEntryHash = 31 * expectedEntryHash + java.util.Objects.hashCode(entry.value());
		int expectedSnapshotHash = Boolean.hashCode(snapshot.active());
		expectedSnapshotHash = 31 * expectedSnapshotHash + java.util.Objects.hashCode(snapshot.title());
		expectedSnapshotHash = 31 * expectedSnapshotHash + java.util.Objects.hashCode(snapshot.entries());
		if (!entry.equals(equalEntry) || entry.hashCode() != equalEntry.hashCode()
			|| entry.hashCode() != expectedEntryHash
			|| !entry.toString().equals("Entry[owner=owner, label=" + entry.label() + ", value=" + entry.value() + "]")
			|| !snapshot.equals(equalSnapshot) || snapshot.hashCode() != equalSnapshot.hashCode()
			|| snapshot.hashCode() != expectedSnapshotHash
			|| !snapshot.toString().equals("ScoreboardSnapshot[active=true, title=" + snapshot.title()
				+ ", entries=" + snapshot.entries() + "]")) {
			throw new AssertionError("Snapshot and entry record-like equality, hash, or string behavior changed.");
		}
		ScoreboardSnapshot.Entry nullEntry = new ScoreboardSnapshot.Entry(null, null, null);
		if (nullEntry.owner() != null || nullEntry.label() != null || nullEntry.value() != null
			|| nullEntry.hashCode() != 0
			|| !nullEntry.toString().equals("Entry[owner=null, label=null, value=null]")) {
			throw new AssertionError("Entry values must keep the frozen record's nullable component behavior.");
		}
	}

	private static void assertMethod(Class<?> type, String name, Class<?> returnType) throws NoSuchMethodException {
		if (type.getMethod(name).getReturnType() != returnType) {
			throw new AssertionError(type.getSimpleName() + "." + name + " changed its Java return type.");
		}
	}

	private static void assertStaticMethod(Class<?> type, String name, Class<?> returnType, Class<?>... parameterTypes)
		throws NoSuchMethodException {
		java.lang.reflect.Method method = type.getMethod(name, parameterTypes);
		if (method.getReturnType() != returnType || !Modifier.isStatic(method.getModifiers())) {
			throw new AssertionError(type.getSimpleName() + "." + name + " changed its Java static API.");
		}
	}

	private static void assertRecord(Class<?> type, String... componentNames) {
		if (!type.isRecord()) throw new AssertionError(type.getSimpleName() + " must retain its Java record ABI.");
		java.lang.reflect.RecordComponent[] components = type.getRecordComponents();
		if (components.length != componentNames.length) {
			throw new AssertionError(type.getSimpleName() + " record component count changed.");
		}
		for (int i = 0; i < componentNames.length; i++) {
			if (!components[i].getName().equals(componentNames[i])) {
				throw new AssertionError(type.getSimpleName() + " record component order or name changed at " + i + ".");
			}
		}
	}

	private static void legacyJsonFieldNamesAreStillRead() {
		String legacy = "{\"lines\":[{\"id\":\"legacy-row\",\"type\":\"CUSTOM_TEXT\","
			+ "\"text\":\"Legacy row\",\"sourceIndex\":3,\"color\":-1,\"bold\":true,\"enabled\":false}],"
			+ "\"background\":\"GLASS_ROUNDED\",\"fontId\":\"geileraddons:scoreboard/noto_sans\","
			+ "\"backgroundColor\":-1442840576,\"borderColor\":-16777216,\"width\":210,\"height\":120,"
			+ "\"cornerRadius\":7,\"borderWidth\":1,\"padding\":5,\"lineSpacing\":1,\"scale\":1.0}";
		ScoreboardLayout decoded = ScoreboardLayoutCodec.decode(legacy).orElseThrow();
		ScoreboardLine line = decoded.lines().get(0);
		if (decoded.width() != 210 || decoded.height() != 120 || decoded.backgroundColor() != 0xAA000000
			|| !line.id().equals("legacy-row") || !line.text().equals("Legacy row")
			|| line.sourceIndex() != 3 || line.color() != 0xFFFFFFFF || !line.bold() || line.enabled()) {
			throw new AssertionError("Kotlin model fields no longer read the frozen scoreboard JSON schema.");
		}
	}

	private static void moduleSettingsLiveInClickGui() {
		ScoreboardModule module = ScoreboardModule.INSTANCE;
		if (!module.name().equals("Custom Scoreboard") || !module.configName().equals("Custom Scoreboard")
			|| !module.description().equals("Replace the sidebar with custom text and a chosen font, background, and border.")
			|| module.category() != Category.VISUAL || module.defaultEnabled() || module.isWorkInProgress()) {
			throw new AssertionError("Custom Scoreboard identity, config key, category or default changed, or it regained a WIP marker.");
		}

		if (module.actions().size() != 1 || !module.actions().get(0).label().equals("Import Font")
			|| module.actions().get(0).onClick() == null || module.choiceSettings().size() != 2
			|| module.colorSettings().size() != 2 || module.numberSettings().size() != 1
				|| !module.numberSettings().get(0).name().equals("Line Spacing")
				|| module.numberSettings().get(0).min() != 0f || module.numberSettings().get(0).max() != 16f
				|| !module.numberSettings().get(0).isInteger()
			|| !module.booleanSettings().isEmpty() || module.textSettings().size() != 64) {
			throw new AssertionError("Click GUI settings should expose only font/background/border/line spacing controls and custom row text.");
		}
		for (int index = 0; index < module.textSettings().size(); index++) {
			if (!module.textSettings().get(index).name().equals("Custom Row " + (index + 1))) {
				throw new AssertionError("Custom scoreboard text rows must remain ordered and individually editable.");
			}
		}
		if (java.nio.file.Files.exists(java.nio.file.Path.of("src", "client", "kotlin", "geiler", "addons",
			"client", "gui", "ScoreboardEditorScreen.kt"))) {
			throw new AssertionError("The obsolete native-title and score-layout editor must stay retired.");
		}
		try {
			String renderer = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "kotlin", "geiler",
				"addons", "client", "scoreboard", "ScoreboardLayoutRenderer.kt"));
			if (renderer.contains("line.color()") || renderer.contains("line.bold()")) {
				throw new AssertionError("Legacy per-row styles must not extend beyond font/background/border styling.");
			}
		} catch (java.io.IOException exception) {
			throw new AssertionError("Could not inspect custom scoreboard style controls.", exception);
		}
	}

	private static void moduleLifecycleControlsHudVisibility() {
		ScoreboardModule module = ScoreboardModule.INSTANCE;
		boolean wasEnabled = module.isEnabled();
		ScoreboardLine fixture = ScoreboardLine.customText("Feature check row");
		ScoreboardBackend.layout().lines().add(fixture);
		try {
			module.setEnabled(false);
			if (ScoreboardHudElement.INSTANCE.visible()) {
				throw new AssertionError("Custom Scoreboard must hide its HUD when the module is disabled.");
			}
			module.setEnabled(true);
			if (!ScoreboardHudElement.INSTANCE.visible()) {
				throw new AssertionError("An enabled Custom Scoreboard with a custom row must expose its HUD.");
			}
		} finally {
			module.setEnabled(wasEnabled);
			ScoreboardBackend.layout().lines().remove(fixture);
		}
	}

	private static void scoreboardRowsAndSidebarRoutingFollowLiveSnapshot() {
		ScoreboardModule module = ScoreboardModule.INSTANCE;
		boolean wasEnabled = module.isEnabled();
		ScoreboardLayout layout = ScoreboardBackend.layout();
		List<ScoreboardLine> originalLines = new java.util.ArrayList<>(layout.lines());
		java.lang.reflect.Field snapshotField;
		Object backend;
		ScoreboardSnapshot originalSnapshot;
		try {
			snapshotField = ScoreboardBackend.class.getDeclaredField("snapshot");
			snapshotField.setAccessible(true);
			backend = ScoreboardBackend.class.getField("INSTANCE").get(null);
			originalSnapshot = (ScoreboardSnapshot) snapshotField.get(backend);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Could not isolate scoreboard snapshot state for routing checks.", exception);
		}

		try {
			layout.lines().clear();
			layout.lines().add(ScoreboardLine.title());
			layout.lines().add(ScoreboardLine.scoreEntry(0));
			module.setEnabled(true);
			snapshotField.set(backend, ScoreboardSnapshot.empty());
			if (ScoreboardHudElement.INSTANCE.visible() || ScoreboardBackend.shouldReplaceVanillaSidebar()) {
				throw new AssertionError("Native-only rows without a live sidebar must stay hidden and leave vanilla routing intact.");
			}

			ScoreboardLine custom = ScoreboardLine.customText("Sample row");
			custom.setEnabled(false);
			layout.lines().add(custom);
			if (ScoreboardHudElement.INSTANCE.visible()) {
				throw new AssertionError("A disabled custom row must not make an inactive scoreboard HUD visible.");
			}
			custom.setEnabled(true);
			if (!ScoreboardHudElement.INSTANCE.visible() || ScoreboardBackend.shouldReplaceVanillaSidebar()) {
				throw new AssertionError("An enabled sample row may show the custom HUD without suppressing vanilla's sidebar.");
			}

			snapshotField.set(backend, ScoreboardSnapshot.preview());
			if (!ScoreboardHudElement.INSTANCE.visible() || !ScoreboardBackend.shouldReplaceVanillaSidebar()) {
				throw new AssertionError("An active sidebar snapshot must show the HUD and route away from vanilla's sidebar.");
			}
			module.setEnabled(false);
			if (ScoreboardHudElement.INSTANCE.visible() || ScoreboardBackend.shouldReplaceVanillaSidebar()) {
				throw new AssertionError("Disabling Custom Scoreboard must disable its HUD and vanilla-sidebar replacement.");
			}
		} catch (IllegalAccessException exception) {
			throw new AssertionError("Could not arrange isolated scoreboard routing fixtures.", exception);
		} finally {
			module.setEnabled(wasEnabled);
			layout.lines().clear();
			layout.lines().addAll(originalLines);
			try {
				snapshotField.set(backend, originalSnapshot);
			} catch (IllegalAccessException exception) {
				throw new AssertionError("Could not restore scoreboard snapshot state after routing checks.", exception);
			}
		}
	}

	private static void codecRoundTripKeepsEditableOrder() {
		ScoreboardLayout layout = new ScoreboardLayout();
		layout.lines().clear();
		layout.lines().add(ScoreboardLine.customText("Coins"));
		layout.lines().add(ScoreboardLine.scoreEntry(4));
		layout.setWidth(210);
		layout.setHeight(120);
		layout.setScale(1.25f);
		layout.setBackground(ScoreboardLayout.BackgroundStyle.GLASS_ROUNDED);
		layout.setFontId("geileraddons:scoreboard/noto_sans");
		ScoreboardLayout decoded = ScoreboardLayoutCodec.decode(ScoreboardLayoutCodec.encode(layout)).orElseThrow();
		if (decoded.lines().size() != 2
			|| decoded.lines().get(0).type() != ScoreboardLine.Type.CUSTOM_TEXT
			|| !decoded.lines().get(0).text().equals("Coins")
			|| decoded.lines().get(1).sourceIndex() != 4
			|| decoded.width() != 210 || decoded.height() != 120
			|| decoded.scale() != 1.25f
			|| decoded.background() != ScoreboardLayout.BackgroundStyle.GLASS_ROUNDED
			|| !decoded.fontId().equals("geileraddons:scoreboard/noto_sans")) {
			throw new AssertionError("Scoreboard layout JSON lost user state.");
		}
	}

	private static void codecRejectsMalformedAndOversizedInput() {
		if (ScoreboardLayoutCodec.decode("{").isPresent()) {
			throw new AssertionError("Malformed scoreboard JSON should be rejected.");
		}
		String oversized = " ".repeat(ScoreboardLayoutCodec.MAX_JSON_CHARS + 1);
		if (ScoreboardLayoutCodec.decode(oversized).isPresent()) {
			throw new AssertionError("Oversized scoreboard JSON should be rejected.");
		}
	}

	private static void geometryHonorsFixedAndAutomaticDimensions() {
		ScoreboardGeometry.Box automatic = ScoreboardGeometry.measure(3, 80, 0, 0, 9, 5, 1);
		if (automatic.width() != 90 || automatic.height() != 40 || automatic.lineHeight() != 10
			|| ScoreboardGeometry.rowY(automatic, 2) != 25) {
			throw new AssertionError("Automatic scoreboard dimensions or row spacing are incorrect.");
		}
		ScoreboardGeometry.Box fixed = ScoreboardGeometry.measure(3, 80, 120, 70, 9, 5, 1);
		if (fixed.width() != 120 || fixed.height() != 70) {
			throw new AssertionError("Fixed scoreboard dimensions should override measurement.");
		}
	}

	private static void fixedWidthTextStaysInsideThePanel() {
		Component longTitle = Component.literal("ObjectiveWithLongTitle");
		Component longLabel = Component.literal("PlayerNameABCDEFGHIJ");
		Component longValue = Component.literal("12345678901");
		for (int panelWidth : new int[] {16, 48, 64, 96, 220}) {
			ScoreboardGeometry.Box box = ScoreboardGeometry.measure(1, 200, panelWidth, 36, 9, 5, 1);
			int contentWidth = box.width() - box.padding() * 2;
			ScoreboardTextLayout.FittedLine title = ScoreboardTextLayout.fit(longTitle, null,
				contentWidth, 0, ScoreboardFeatureChecks::testTextWidth);
			ScoreboardTextLayout.FittedLine row = ScoreboardTextLayout.fit(longLabel, longValue,
				contentWidth, 7, ScoreboardFeatureChecks::testTextWidth);

			if (box.width() != panelWidth || box.height() != 36
				|| title.leftWidth() > contentWidth || row.totalWidth() > contentWidth) {
				throw new AssertionError("Fixed scoreboard widths must contain every fitted text column.");
			}
			if (panelWidth <= 16 && !row.left().getString().endsWith("…")
				&& (row.right() == null || !row.right().getString().endsWith("…"))) {
				throw new AssertionError("The minimum scoreboard width should retain an explicit truncation marker.");
			}
			if (panelWidth > 16 && panelWidth <= 96 && (!title.left().getString().endsWith("…")
				|| !row.left().getString().endsWith("…") || row.right() == null
				|| !row.right().getString().endsWith("…"))) {
				throw new AssertionError("Narrow scoreboard rows should show truncation explicitly with an ellipsis.");
			}
		}
	}

	private static int testTextWidth(Component component) {
		String text = component.getString();
		return text.codePointCount(0, text.length()) * 6;
	}
}
