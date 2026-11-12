package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.ModulePreview;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.presentation.ComponentWidthLimiter;
import geiler.addons.client.presentation.PlayerNameGradientMath;
import geiler.addons.client.presentation.PlayerNameGradientRenderState;
import geiler.addons.client.presentation.PlayerPresentationProfileStore;
import geiler.addons.client.presentation.PlayerPresentationProfiles;
import geiler.addons.client.presentation.PlayerTabNameSource;
import geiler.addons.client.presentation.TooltipComponentMapper;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/** Focused config and Java ABI checks for the Player Names Kotlin port. */
public final class PlayerNamesModuleChecks {
	private PlayerNamesModuleChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		PlayerNamesModule module = PlayerNamesModule.INSTANCE;
		check("Player Names".equals(module.name()) && "Player Names".equals(module.configName())
			&& "Applies hosted name profiles and local alias and style settings for your own name."
				.equals(module.description())
			&& module.category() == Category.VISUAL && !module.defaultEnabled() && module.isWorkInProgress(),
			"module identity, category, default state, and work-in-progress status remain stable");

		check(names(module.booleanSettings()).equals(List.of(
			"Replace Names", "Apply Name Styles", "Own Color Enabled", "Own Gradient Enabled", "Animated Gradient"))
			&& values(module.booleanSettings()).equals(List.of(true, true, false, false, false)),
			"boolean setting IDs, declaration order, and defaults remain stable");
		check(names(module.colorSettings()).equals(List.of("Own Color", "Gradient Start", "Gradient End",
			"Gradient Stop 1", "Gradient Stop 2", "Gradient Stop 3", "Gradient Stop 4", "Gradient Stop 5",
			"Gradient Stop 6"))
			&& List.of(module.colorSettings().get(0).argb(), module.colorSettings().get(1).argb(),
				module.colorSettings().get(2).argb(), module.colorSettings().get(3).argb(),
				module.colorSettings().get(4).argb(), module.colorSettings().get(5).argb(),
				module.colorSettings().get(6).argb(), module.colorSettings().get(7).argb(),
				module.colorSettings().get(8).argb()).equals(List.of(0xFFFFFF55, 0xFFFF5555, 0xFF55AAFF,
				0xFFFF5555, 0xFFFFFF55, 0xFF55FF55, 0xFF55FFFF, 0xFF5555FF, 0xFFFF55FF)),
			"color setting IDs, order, and defaults use the shared per-color picker model");
		check(names(module.choiceSettings()).equals(List.of("Gradient Mode")),
			"gradient mode remains the sole choice setting");
		ChoiceSetting gradientMode = module.choiceSettings().get(0);
		check("TWO_COLOR".equals(gradientMode.value()) && "Two color".equals(gradientMode.displayValue())
			&& gradientMode.choices().equals(List.of("TWO_COLOR", "REVERSE", "RAINBOW", "MULTI_STOP")),
			"gradient choice values, labels, and order remain stable");
		check(names(module.textSettings()).equals(List.of("Own Alias", "Gradient Stops"))
			&& "Your Alias".equals(module.textSettings().get(0).displayName())
			&& "".equals(module.textSettings().get(0).value()) && module.textSettings().get(0).maxLength() == 64
			&& "#FF5555,#FFFF55,#55FF55,#55FFFF,#5555FF,#FF55FF".equals(module.textSettings().get(1).value())
			&& module.textSettings().get(1).maxLength() == 80,
			"text setting IDs, labels, defaults, and length bounds remain stable");
		check(names(module.numberSettings()).equals(List.of("Gradient Speed"))
			&& module.numberSettings().get(0).min() == 0.05f && module.numberSettings().get(0).max() == 2.0f
			&& module.numberSettings().get(0).value() == 0.25f,
			"gradient speed ID, bounds, and default remain stable");

		List<SettingGroup> groups = module.groups();
		check(groups.stream().map(SettingGroup::name).toList().equals(List.of("Name Profiles", "Your Name"))
			&& names(groups.get(0).settings()).equals(List.of("Replace Names", "Apply Name Styles"))
			&& names(groups.get(1).settings()).equals(List.of(
				"Own Alias", "Own Color Enabled", "Own Color", "Own Gradient Enabled", "Gradient Mode",
				"Gradient Start", "Gradient End", "Gradient Stop 1", "Gradient Stop 2", "Gradient Stop 3",
				"Gradient Stop 4", "Gradient Stop 5", "Gradient Stop 6", "Animated Gradient", "Gradient Speed")),
			"settings remain in their original group and row order");
		checkSettingVisibility(module);
		checkGradientResolution(module);
		checkPreviewBehavior(module);
		checkModuleTransformBehavior(module);

		check(PlayerNamesModule.PATH_PREPARE_STRING == 1 && PlayerNamesModule.PATH_PREPARE_SEQUENCE == 2
			&& PlayerNamesModule.PATH_DRAW_OUTLINE == 3 && PlayerNamesModule.PATH_WIDTH_STRING == 4
			&& PlayerNamesModule.PATH_WIDTH_TEXT == 5 && PlayerNamesModule.PATH_WIDTH_SEQUENCE == 6
			&& PlayerNamesModule.PATH_SPLIT_TEXT == 7 && PlayerNamesModule.PATH_SPLIT_OUTPUT == 8
			&& PlayerNamesModule.PATH_CHAT_SPLIT_OUTPUT == 9 && PlayerNamesModule.PATH_VISUAL_ORDER == 10,
			"public transform path constants remain Java static fields with their original values");
		check(module instanceof ModulePreview, "module keeps the Java-visible preview interface");
		checkPublicMethod("configName");
		checkPublicMethod("isSettingVisible", Setting.class);
		checkPublicMethod("isWorkInProgress");
		checkPublicMethod("tick");
		checkPublicMethod("replaceRenderedName", String.class, Component.class);
		checkPublicMethod("replaceKnownRenderedName", Component.class);
		checkPublicMethod("replaceFontComponent", Component.class, int.class);
		checkPublicMethod("replaceFontComponent", Component.class, int.class, long.class);
		checkPublicMethod("replaceFontComponent", Component.class, int.class, long.class, boolean.class);
		checkPublicMethod("replaceFontString", String.class, int.class);
		checkPublicMethod("replaceFormattedText", FormattedText.class, int.class);
		checkPublicMethod("replaceFormattedSequence", FormattedCharSequence.class, int.class);
		checkPublicMethod("markPresentedSequences", List.class, int.class);
		checkPublicMethod("recordFontTransform", int.class, boolean.class);
		checkPublicMethod("beginFontTransformTiming", int.class);
		checkPublicMethod("recordFontTransform", int.class, boolean.class, long.class, boolean.class, boolean.class);
		checkPublicMethod("hasEnabledNameStyle", String.class);
		checkPublicMethod("chatLayoutPresentationRevision");
		checkPublicMethod("chatComponentPresentationRevision");
		checkPublicMethod("chatAnimationFrameRevision");
		checkPublicMethod("chatMessagePresentationRevision", Component.class);
		checkPublicMethod("fontPresentationRevision");
		checkPublicMethod("shouldTransformFontInput", int.class);
		checkPublicMethod("hasFontNameCandidate", String.class, int.class);
		checkPublicMethod("hasFontNameCandidate", FormattedText.class);
		checkPublicMethod("hasFontNameCandidate", FormattedCharSequence.class);
		checkPublicMethod("chatMessageHasNamePresentation", Component.class);
		checkPublicMethod("chatMessageHasAnimatedNameStyle", Component.class);
		checkPublicMethod("hasActiveNamePresentationProfiles");
		checkPublicMethod("effectMetadata", String.class);
		checkPublicMethod("replaceNamesSetting");
		checkPublicMethod("applyNameStylesSetting");
		checkPublicMethod("previewHeight", int.class, int.class);
		checkPublicMethod("previewLines", Font.class, int.class);
		checkPublicMethod("drawPreview", GuiGraphicsExtractor.class, Font.class, int.class, int.class,
			int.class, int.class);
		checkPresentationHelperAbi();
	}

	private static void checkPresentationHelperAbi() {
		checkStaticMethod(ComponentWidthLimiter.class, "fit", Component.class,
			Component.class, int.class, ToIntFunction.class);
		checkStaticMethod(PlayerNameGradientMath.class, "glyphStart", double.class, int.class, int.class);
		checkStaticMethod(PlayerNameGradientMath.class, "glyphEnd", double.class, int.class, int.class);
		checkStaticMethod(PlayerNameGradientMath.class, "sampleRgb", int.class, List.class, double.class, boolean.class);
		checkStaticMethod(PlayerNameGradientMath.class, "animatedPhase", double.class,
			double.class, long.class, long.class, float.class);
		checkStaticMethod(PlayerNameGradientMath.class, "colorAtGlyph", int.class,
			List.class, int.class, int.class, double.class, double.class, boolean.class);
		checkStaticMethod(PlayerNameGradientRenderState.class, "gradientStyle", Style.class,
			Style.class, List.class, int.class, int.class, boolean.class, float.class, double.class, long.class);
		checkStaticMethod(PlayerNameGradientRenderState.class, "beginGlyph", void.class, Style.class, int.class);
		checkStaticMethod(PlayerNameGradientRenderState.class, "beginRenderPass", void.class);
		checkStaticMethod(PlayerNameGradientRenderState.class, "vertexColor", int.class, int.class, boolean.class);
		checkStaticMethod(PlayerNameGradientRenderState.class, "endGlyph", void.class);
		checkStaticMethod(PlayerNameGradientRenderState.class, "shadowColorFor", Integer.class,
			Style.class, int.class, boolean.class);
		checkStaticMethod(PlayerTabNameSource.class, "rawLine", String.class, PlayerInfo.class);
		checkStaticMethod(PlayerTabNameSource.class, "rawLine", String.class,
			Component.class, String.class, Component.class, Component.class);
		checkStaticMethod(TooltipComponentMapper.class, "mapComponents", List.class, List.class, UnaryOperator.class);
	}

	private static void checkSettingVisibility(PlayerNamesModule module) {
		List<BooleanSetting> booleans = module.booleanSettings();
		BooleanSetting ownColorEnabled = booleans.get(2);
		BooleanSetting ownGradientEnabled = booleans.get(3);
		BooleanSetting animatedGradient = booleans.get(4);
		List<ColorSetting> colors = module.colorSettings();
		ColorSetting ownColor = colors.get(0);
		ColorSetting start = colors.get(1);
		ColorSetting end = colors.get(2);
		List<ColorSetting> customStops = colors.subList(3, 9);
		ChoiceSetting mode = module.choiceSettings().get(0);
		TextSetting ownAlias = module.textSettings().get(0);
		TextSetting stops = module.textSettings().get(1);
		NumberSetting speed = module.numberSettings().get(0);
		boolean originalOwnColorEnabled = ownColorEnabled.rawValue();
		boolean originalGradientEnabled = ownGradientEnabled.rawValue();
		boolean originalAnimated = animatedGradient.rawValue();
		String originalMode = mode.value();
		try {
			check(module.isSettingVisible(ownAlias) && !module.isSettingVisible(ownColor)
				&& !module.isSettingVisible(mode) && !module.isSettingVisible(start)
				&& !module.isSettingVisible(end) && !module.isSettingVisible(stops)
				&& customStops.stream().noneMatch(module::isSettingVisible)
				&& !module.isSettingVisible(speed),
				"default visibility keeps own color and gradient controls hidden");
			ownColorEnabled.setValue(true);
			check(module.isSettingVisible(ownColor), "own solid color appears when enabled");
			ownGradientEnabled.setValue(true);
			check(!module.isSettingVisible(ownColor) && module.isSettingVisible(mode)
				&& module.isSettingVisible(start) && module.isSettingVisible(end)
				&& !module.isSettingVisible(stops) && !module.isSettingVisible(speed),
				"two-color gradient controls follow gradient enablement");
			mode.setValue("REVERSE");
			check(module.isSettingVisible(start) && module.isSettingVisible(end),
				"reverse gradient mode keeps endpoint colors visible");
			mode.setValue("RAINBOW");
			check(!module.isSettingVisible(start) && !module.isSettingVisible(end)
				&& !module.isSettingVisible(stops),
				"rainbow mode hides unused endpoint and stop controls");
			mode.setValue("MULTI_STOP");
			check(!module.isSettingVisible(start) && !module.isSettingVisible(end)
				&& !module.isSettingVisible(stops)
				&& customStops.stream().allMatch(module::isSettingVisible),
				"custom gradient mode exposes the shared per-color picker rows");
			animatedGradient.setValue(true);
			check(module.isSettingVisible(speed), "gradient speed appears only for animated gradients");
		} finally {
			ownColorEnabled.setValue(originalOwnColorEnabled);
			ownGradientEnabled.setValue(originalGradientEnabled);
			animatedGradient.setValue(originalAnimated);
			mode.setValue(originalMode);
		}
	}

	private static void checkGradientResolution(PlayerNamesModule module) {
		BooleanSetting enabled = module.booleanSettings().get(3);
		ChoiceSetting mode = module.choiceSettings().get(0);
		TextSetting legacyStops = module.textSettings().get(1);
		List<ColorSetting> stops = module.colorSettings().subList(3, 9);
		boolean originalEnabled = enabled.rawValue();
		String originalMode = mode.value();
		String originalLegacyStops = legacyStops.value();
		List<int[]> originalColors = stops.stream()
			.map(setting -> new int[] { setting.red(), setting.green(), setting.blue(), setting.alpha() }).toList();
		try {
			enabled.setValue(true);
			mode.setValue("TWO_COLOR");
			check(ownGradientColors(module).equals(List.of(0xFF5555, 0x55AAFF)),
				"two-color mode resolves the configured endpoints");

			mode.setValue("REVERSE");
			check(ownGradientColors(module).equals(List.of(0x55AAFF, 0xFF5555)),
				"reverse mode swaps the configured endpoints");

			mode.setValue("RAINBOW");
			check(ownGradientColors(module).equals(List.of(
				0xFFFF5555, 0xFFFFFF55, 0xFF55FF55, 0xFF55FFFF, 0xFF5555FF, 0xFFFF55FF)),
				"rainbow mode keeps the six original color stops");

			mode.setValue("MULTI_STOP");
			int[] chosenStops = { 0x123456, 0xABCDEF, 0x000001, 0x002244, 0x335577, 0x99AABB };
			for (int index = 0; index < stops.size(); index++) setRgb(stops.get(index), chosenStops[index]);
			check(ownGradientColors(module).equals(List.of(0x123456, 0xABCDEF, 0x000001, 0x002244, 0x335577, 0x99AABB)),
				"custom gradient reads the selected values from each shared picker");

			int[] defaultStops = { 0xFF5555, 0xFFFF55, 0x55FF55, 0x55FFFF, 0x5555FF, 0xFF55FF };
			for (int index = 0; index < stops.size(); index++) setRgb(stops.get(index), defaultStops[index]);
			legacyStops.setValue("#123456,#12G456,#ABCDEF,#000001");
			migrateLegacyStops(module);
			check(ownGradientColors(module).equals(List.of(
				0x123456, 0xABCDEF, 0x000001, 0x55FFFF, 0x5555FF, 0xFF55FF))
				&& legacyStops.value().equals("#FF5555,#FFFF55,#55FF55,#55FFFF,#5555FF,#FF55FF"),
				"saved custom stop text migrates to picker values while malformed entries are skipped");
		} finally {
			enabled.setValue(originalEnabled);
			mode.setValue(originalMode);
			legacyStops.setValue(originalLegacyStops);
			for (int index = 0; index < stops.size(); index++) {
				int[] color = originalColors.get(index);
				stops.get(index).set(color[0], color[1], color[2], color[3]);
			}
		}
	}

	private static void checkPreviewBehavior(PlayerNamesModule module) {
		List<BooleanSetting> booleans = module.booleanSettings();
		BooleanSetting replaceNames = booleans.get(0);
		BooleanSetting applyStyles = booleans.get(1);
		BooleanSetting ownColorEnabled = booleans.get(2);
		BooleanSetting ownGradientEnabled = booleans.get(3);
		TextSetting alias = module.textSettings().get(0);
		ColorSetting ownColor = module.colorSettings().get(0);
		boolean originalReplace = replaceNames.rawValue();
		boolean originalStyles = applyStyles.rawValue();
		boolean originalColorEnabled = ownColorEnabled.rawValue();
		boolean originalGradientEnabled = ownGradientEnabled.rawValue();
		String originalAlias = alias.value();
		int[] originalColor = { ownColor.red(), ownColor.green(), ownColor.blue(), ownColor.alpha() };
		try {
			replaceNames.setValue(true);
			applyStyles.setValue(true);
			ownColorEnabled.setValue(true);
			ownGradientEnabled.setValue(false);
			alias.setValue("PreviewAlias");
			setRgb(ownColor, 0x123456);
			Component preview = previewName(module);
			check("PreviewAlias".equals(preview.getString()),
				"in-menu preview reflects the current alias immediately: " + preview.getString());
			check(hasColor(preview, 0x123456),
				"in-menu preview reflects the current solid color immediately: " + preview.getStyle().getColor());

			applyStyles.setValue(false);
			Component unstyled = previewName(module);
			check("PreviewAlias".equals(unstyled.getString()) && !hasColor(unstyled, 0x123456),
				"preview honors the Apply Name Styles toggle");
		} finally {
			replaceNames.setValue(originalReplace);
			applyStyles.setValue(originalStyles);
			ownColorEnabled.setValue(originalColorEnabled);
			ownGradientEnabled.setValue(originalGradientEnabled);
			alias.setValue(originalAlias);
			ownColor.set(originalColor[0], originalColor[1], originalColor[2], originalColor[3]);
		}
	}

	private static Component previewName(PlayerNamesModule module) {
		try {
			Method method = PlayerNamesModule.class.getDeclaredMethod("previewName");
			method.setAccessible(true);
			return (Component) method.invoke(module);
		} catch (ReflectiveOperationException missing) {
			throw new AssertionError("Could not inspect the current Player Names preview", missing);
		}
	}

	private static void migrateLegacyStops(PlayerNamesModule module) {
		try {
			Method method = PlayerNamesModule.class.getDeclaredMethod("migrateLegacyGradientStops");
			method.setAccessible(true);
			method.invoke(module);
		} catch (ReflectiveOperationException missing) {
			throw new AssertionError("Could not migrate legacy Player Names color stops", missing);
		}
	}

	private static void setRgb(ColorSetting setting, int rgb) {
		setting.set((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF, 255);
	}

	private static void checkModuleTransformBehavior(PlayerNamesModule module) {
		PlayerPresentationProfileStore store = PlayerPresentationProfileStore.INSTANCE;
		java.lang.reflect.Field profilesField = privateField(store.getClass(), "currentProfiles");
		java.lang.reflect.Field refreshAfterField = privateField(store.getClass(), "refreshAfterMillis");
		Object originalProfiles = get(profilesField, store);
		long originalRefreshAfter = getLong(refreshAfterField, store);
		boolean originalEnabled = module.isEnabled();
		BooleanSetting replaceNames = module.booleanSettings().get(0);
		BooleanSetting applyNameStyles = module.booleanSettings().get(1);
		BooleanSetting ownSolidColorEnabled = module.booleanSettings().get(2);
		BooleanSetting ownGradientEnabled = module.booleanSettings().get(3);
		BooleanSetting ownGradientAnimated = module.booleanSettings().get(4);
		TextSetting ownAlias = module.textSettings().get(0);
		boolean originalReplaceNames = replaceNames.rawValue();
		boolean originalApplyNameStyles = applyNameStyles.rawValue();
		boolean originalSolidColorEnabled = ownSolidColorEnabled.rawValue();
		boolean originalGradientEnabled = ownGradientEnabled.rawValue();
		boolean originalGradientAnimated = ownGradientAnimated.rawValue();
		String originalOwnAlias = ownAlias.value();
		String targetName = "GA_Check_Target";
		Component source = Component.literal(targetName);
		try {
			// Keep the module's normal enable callback deterministic without starting an HTTP refresh.
			refreshAfterField.setLong(store, Long.MAX_VALUE);
			profilesField.set(store, profileWithAlias(targetName, "FriendlyTarget", "#123456"));
			replaceNames.setValue(true);
			applyNameStyles.setValue(true);
			ownSolidColorEnabled.setValue(false);
			ownGradientEnabled.setValue(false);
			ownGradientAnimated.setValue(false);
			ownAlias.setValue("");

			module.setEnabled(false);
			check(module.replaceRenderedName(targetName, source) == source,
				"disabled Player Names returns the original rendered component");
			module.setEnabled(true);
			Component aliased = module.replaceRenderedName(targetName, source);
			check("FriendlyTarget".equals(aliased.getString()) && hasColor(aliased, 0x123456),
				"the registered module applies a hosted alias and color to the targeted player name");
			check(module.replaceRenderedName(targetName, source) == aliased,
				"repeated component transforms reuse the cached presentation result");
			check(module.replaceRenderedName("OtherPlayer", source) == source,
				"a targeted render call does not apply another player's profile");
			check("FriendlyTarget".equals(module.replaceKnownRenderedName(source).getString()),
				"team-formatted names use the same known-profile alias path");
			check(module.hasFontNameCandidate(targetName, PlayerNamesModule.PATH_WIDTH_STRING)
				&& !module.hasFontNameCandidate("UnrelatedPlayer", PlayerNamesModule.PATH_WIDTH_STRING),
				"font hooks detect candidate usernames and skip unrelated strings");
			String legacy = "\u00a76" + targetName + "\u00a7r joined";
			check(("\u00a76FriendlyTarget\u00a7r joined")
				.equals(module.replaceFontString(legacy, PlayerNamesModule.PATH_WIDTH_STRING)),
				"legacy font-string replacement preserves surrounding formatting codes and text");

			replaceNames.setValue(false);
			Component styledOnly = module.replaceRenderedName(targetName, source);
			check(targetName.equals(styledOnly.getString()) && hasColor(styledOnly, 0x123456)
				&& !module.shouldTransformFontInput(PlayerNamesModule.PATH_WIDTH_STRING),
				"disabling aliases retains profile styling while disabling the alias-only width path");
			applyNameStyles.setValue(false);
			check(!module.shouldTransformFontInput(PlayerNamesModule.PATH_WIDTH_TEXT)
				&& module.replaceRenderedName(targetName, source) == source,
				"disabling both presentation controls restores the original component and skips font hooks");

			replaceNames.setValue(true);
			applyNameStyles.setValue(true);
			profilesField.set(store, profileWithAlias(targetName, "UpdatedTarget", "#654321"));
			Component updated = module.replaceRenderedName(targetName, source);
			check("UpdatedTarget".equals(updated.getString()) && hasColor(updated, 0x654321),
				"a refreshed profile invalidates the cached component and applies its latest alias and style");
		} catch (IllegalAccessException failure) {
			throw new AssertionError("Could not install an isolated Player Names profile fixture", failure);
		} finally {
			replaceNames.setValue(originalReplaceNames);
			applyNameStyles.setValue(originalApplyNameStyles);
			ownSolidColorEnabled.setValue(originalSolidColorEnabled);
			ownGradientEnabled.setValue(originalGradientEnabled);
			ownGradientAnimated.setValue(originalGradientAnimated);
			ownAlias.setValue(originalOwnAlias);
			try {
				profilesField.set(store, originalProfiles);
				module.setEnabled(originalEnabled);
				refreshAfterField.setLong(store, originalRefreshAfter);
			} catch (IllegalAccessException failure) {
				throw new AssertionError("Could not restore the Player Names profile fixture", failure);
			}
		}
	}

	private static PlayerPresentationProfiles profileWithAlias(String playerName, String alias, String color) {
		try {
			return PlayerPresentationProfiles.parse("""
				{"schemaVersion":1,"players":[{"name":"%s","alias":"%s","color":"%s"}]}
				""".formatted(playerName, alias, color));
		} catch (java.io.IOException failure) {
			throw new AssertionError("Could not parse the Player Names profile fixture", failure);
		}
	}

	private static java.lang.reflect.Field privateField(Class<?> owner, String name) {
		try {
			java.lang.reflect.Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("Could not access Player Names fixture field " + name, failure);
		}
	}

	private static Object get(java.lang.reflect.Field field, Object target) {
		try {
			return field.get(target);
		} catch (IllegalAccessException failure) {
			throw new AssertionError("Could not read Player Names fixture field " + field.getName(), failure);
		}
	}

	private static long getLong(java.lang.reflect.Field field, Object target) {
		try {
			return field.getLong(target);
		} catch (IllegalAccessException failure) {
			throw new AssertionError("Could not read Player Names fixture field " + field.getName(), failure);
		}
	}

	private static boolean hasColor(Component component, int expectedRgb) {
		var color = component.getStyle().getColor();
		if (color != null && color.getValue() == expectedRgb) return true;
		return component.getSiblings().stream().anyMatch(sibling -> hasColor(sibling, expectedRgb));
	}

	@SuppressWarnings("unchecked")
	private static List<Integer> ownGradientColors(PlayerNamesModule module) {
		try {
			Method method = PlayerNamesModule.class.getDeclaredMethod("ownGradientColors");
			method.setAccessible(true);
			return (List<Integer>) method.invoke(module);
		} catch (ReflectiveOperationException missing) {
			throw new AssertionError("Could not inspect the module's resolved gradient colors", missing);
		}
	}

	private static List<String> names(List<? extends Setting> settings) {
		return settings.stream().map(Setting::name).toList();
	}

	private static List<Boolean> values(List<BooleanSetting> settings) {
		return settings.stream().map(BooleanSetting::rawValue).toList();
	}

	private static void checkPublicMethod(String name, Class<?>... parameterTypes) {
		try {
			Method method = PlayerNamesModule.class.getMethod(name, parameterTypes);
			check(java.lang.reflect.Modifier.isPublic(method.getModifiers()),
				"Java hook method remains public: " + name);
		} catch (NoSuchMethodException missing) {
			throw new AssertionError("Missing Java hook method: " + name, missing);
		}
	}

	private static void checkStaticMethod(Class<?> owner, String name, Class<?> returnType,
		Class<?>... parameterTypes) {
		try {
			Method method = owner.getMethod(name, parameterTypes);
			check(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
				&& method.getReturnType() == returnType,
				"Java helper ABI remains public static with its original signature: " + owner.getSimpleName() + "." + name);
		} catch (NoSuchMethodException missing) {
			throw new AssertionError("Missing Java helper method: " + owner.getSimpleName() + "." + name, missing);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
