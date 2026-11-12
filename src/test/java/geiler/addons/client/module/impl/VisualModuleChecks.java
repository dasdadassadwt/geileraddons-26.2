package geiler.addons.client.module.impl;

import geiler.addons.client.gui.GuiTheme;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Focused parity and Java-interoperability checks for the Kotlin Theme module. */
public final class VisualModuleChecks {
	private static final String[] COLOR_IDS = {
		"Background", "Border", "Accent", "Text", "Muted Text", "Icon color"
	};
	private static final String[] GROUP_NAMES = {"Colours", "Presets", "Click GUI", "Surfaces"};
	private static final String[] PRESET_NAMES = {
		"Tracker", "Amethyst", "Midnight", "Forest", "Aurora", "Ember", "Orchid", "Ice Glass", "Rose Glass"
	};
	private static final VisualModule.Preset[] PRESETS = {
		VisualModule.Preset.TRACKER, VisualModule.Preset.AMETHYST, VisualModule.Preset.MIDNIGHT,
		VisualModule.Preset.FOREST, VisualModule.Preset.AURORA, VisualModule.Preset.EMBER,
		VisualModule.Preset.ORCHID, VisualModule.Preset.ICE_GLASS, VisualModule.Preset.ROSE_GLASS
	};
	private static final int[][] PRESET_COLORS = {
		{0xA2071925, 0xFF3A8198, 0xFF27CDE5, 0xFFF3FBFF, 0xFFF3FBFF, 0xFF9DB9C6},
		{0x9E1A1430, 0xFF826BAC, 0xFF9B78F3, 0xFFF8F2FF, 0xFFF8F2FF, 0xFFB1A3C8},
		{0xB40B1220, 0x33A8C7FF, 0xFF3B82F6, 0xFFEAF2FF, 0xFFEAF2FF, 0xFF7E8FA8},
		{0xA011211A, 0xFF5B9B76, 0xFF51C987, 0xFFF0FFF4, 0xFFF0FFF4, 0xFFA0C4AA},
		{0x971A2635, 0xFF56A99B, 0xFF48DCC2, 0xFFF0FFFC, 0xFFF0FFFC, 0xFF9EC8C0},
		{0x9D251718, 0xFFC77F66, 0xFFFF795F, 0xFFFFF5F0, 0xFFFFF5F0, 0xFFD2AAA0},
		{0x9E1C1830, 0xFF967AC2, 0xFFE08EEB, 0xFFFFF4FF, 0xFFFFF4FF, 0xFFC4AACA},
		{0x82142735, 0xFF71A7B6, 0xFF63DDF1, 0xFFF4FCFF, 0xFFF4FCFF, 0xFFB2D3DE},
		{0x9D161B2B, 0xFFB6819B, 0xFFFF8EB7, 0xFFFFF5FA, 0xFFFFF5FA, 0xFFD2ADB9}
	};

	private VisualModuleChecks() { }

	public static void run() {
		checkJavaSurface();
		checkModuleAndSettings();
		checkPresetsAndRefresh();
	}

	private static void checkJavaSurface() {
		check(VisualModule.INSTANCE == VisualModule.INSTANCE,
			"VisualModule.INSTANCE remains a Java-visible singleton field");
		check(VisualModule.Preset.class.isRecord(),
			"Preset remains a JVM record for Java callers");
		List<String> components = new ArrayList<>();
		for (RecordComponent component : VisualModule.Preset.class.getRecordComponents()) {
			components.add(component.getName());
		}
		check(components.equals(List.of("background", "border", "accent", "text", "iconColor", "muted")),
			"Preset retains its Java record component names and order");
		try {
			check(Modifier.isStatic(VisualModule.class.getDeclaredMethod(
				"applyPreset", VisualModule.Preset.class).getModifiers()),
				"applyPreset remains a Java static method");
		} catch (NoSuchMethodException missing) {
			throw new AssertionError("The Java static applyPreset entry point is missing", missing);
		}

		// These direct calls are compile-time checks for the getter-shaped methods used by Java
		// config, GUI, and check callers.
		VisualModule theme = VisualModule.INSTANCE;
		check(theme.clickGuiMotion() != null && theme.iconColorSetting() != null && theme.themeSurfaces() != null,
			"Java callers retain the Theme setting accessor methods");
		theme.refreshTheme();
	}

	private static void checkModuleAndSettings() {
		VisualModule theme = VisualModule.INSTANCE;
		check("Theme".equals(theme.name()) && "Theme".equals(theme.configName())
			&& theme.category() == Category.VISUAL,
			"the registered module identity and persisted config prefix remain Theme/Visual");
		check(!theme.defaultEnabled() && !theme.showsToggleControl() && !theme.showsKeybindControl(),
			"Theme retains its hidden, disabled-by-default pseudo-module controls");

		check(names(theme.colorSettings()).equals(Arrays.asList(COLOR_IDS)),
			"Theme color setting IDs and declaration order stay stable");
		checkColor(theme, "Background", 0x810B1220);
		checkColor(theme, "Border", 0xFF191D24);
		checkColor(theme, "Accent", 0xFF3B82F6);
		checkColor(theme, "Text", 0xFFEAF2FF);
		checkColor(theme, "Muted Text", 0xFF7E8FA8);
		checkColor(theme, "Icon color", 0xFFEAF2FF);

		ChoiceSetting motion = theme.clickGuiMotion();
		check("Motion".equals(motion.name())
			&& "Expressive".equals(motion.value())
			&& motion.choices().equals(List.of("None", "Reduced", "Expressive")),
			"the Motion key, default and choices remain stable");
		check("Theme Macro + Inventory Colors".equals(theme.themeSurfaces().name())
			&& theme.themeSurfaces().value(),
			"the themed-surfaces key and enabled default remain stable");

		List<SettingGroup> groups = theme.groups();
		check(groups.stream().map(SettingGroup::name).toList().equals(Arrays.asList(GROUP_NAMES)),
			"Theme group names and order remain stable");
		check(names(groups.get(0).settings()).equals(Arrays.asList(COLOR_IDS)),
			"the Colours group retains the same rows and order");
		check(names(groups.get(1).settings()).equals(Arrays.asList(PRESET_NAMES)),
			"the Presets group retains all nine actions in order");
		check(names(groups.get(2).settings()).equals(List.of("Motion"))
			&& names(groups.get(3).settings()).equals(List.of("Theme Macro + Inventory Colors")),
			"Click GUI and Surfaces retain their setting rows");
		check(names(theme.actions()).equals(Arrays.asList(PRESET_NAMES)),
			"preset action names and order remain stable");
	}

	private static void checkPresetsAndRefresh() {
		VisualModule theme = VisualModule.INSTANCE;
		List<ColorSetting> colors = theme.colorSettings();
		int[][] original = new int[colors.size()][4];
		for (int i = 0; i < colors.size(); i++) {
			ColorSetting color = colors.get(i);
			original[i] = new int[]{color.red(), color.green(), color.blue(), color.alpha()};
		}
		String originalMotion = theme.clickGuiMotion().value();
		boolean originalSurfaces = theme.themeSurfaces().value();
		try {
			for (int i = 0; i < PRESETS.length; i++) {
				VisualModule.Preset preset = PRESETS[i];
				int[] expected = PRESET_COLORS[i];
				check(preset.background() == expected[0] && preset.border() == expected[1]
					&& preset.accent() == expected[2] && preset.text() == expected[3]
					&& preset.iconColor() == expected[4] && preset.muted() == expected[5],
					"preset constants and record accessors stay stable for " + PRESET_NAMES[i]);

				VisualModule.applyPreset(preset);
				// Preset record order is iconColor then muted; the UI setting order is muted then iconColor.
				int[] appliedColors = {
					expected[0], expected[1], expected[2], expected[3], expected[5], expected[4]
				};
				for (int colorIndex = 0; colorIndex < appliedColors.length; colorIndex++) {
					check(colors.get(colorIndex).argb() == appliedColors[colorIndex],
						"preset applies " + COLOR_IDS[colorIndex] + " for " + PRESET_NAMES[i]);
				}
				check(GuiTheme.PANEL_TOP == expected[0] && GuiTheme.BORDER == expected[1]
					&& GuiTheme.CATEGORY_SELECTED == (expected[2] | 0xFF000000)
					&& GuiTheme.TEXT_PRIMARY == (expected[3] | 0xFF000000)
					&& GuiTheme.TEXT_MUTED == (expected[5] | 0xFF000000),
					"preset refreshes the shared GUI palette for " + PRESET_NAMES[i]);
			}
			theme.clickGuiMotion().setValue("Reduced");
			theme.themeSurfaces().setValue(false);
			colors.get(1).set(1, 2, 3, 4);
			theme.refreshTheme();
			check(GuiTheme.BORDER == 0x04010203,
				"refreshTheme still applies color setting changes independently of preset actions");
		} finally {
			for (int i = 0; i < colors.size(); i++) {
				int[] rgba = original[i];
				colors.get(i).set(rgba[0], rgba[1], rgba[2], rgba[3]);
			}
			theme.clickGuiMotion().setValue(originalMotion);
			theme.themeSurfaces().setValue(originalSurfaces);
			theme.refreshTheme();
		}
	}

	private static List<String> names(List<? extends Setting> settings) {
		return settings.stream().map(Setting::name).toList();
	}

	private static void checkColor(VisualModule theme, String id, int argb) {
		ColorSetting setting = theme.colorSettings().stream()
			.filter(candidate -> id.equals(candidate.name()))
			.findFirst().orElseThrow();
		check(setting.argb() == argb && (theme.configName() + "." + id).equals("Theme." + id),
			"setting default and persisted key remain stable for " + id);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
