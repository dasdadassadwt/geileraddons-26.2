package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Focused Java-ABI and persisted-setting checks for the Kotlin Pest Highlighter module. */
public final class PestHighlighterModuleChecks {
    private PestHighlighterModuleChecks() {
    }

    public static void main(String[] arguments) {
        run();
    }

    public static void run() {
        checkJavaEntrypoints();
        checkSettingIdentityAndDefaults();
        checkGroupsAndVisibility();
        checkBehaviorDecisions();
        checkDiagnosticAppearanceSnapshot();
        System.out.println("Pest Highlighter module checks passed");
    }

    private static void checkJavaEntrypoints() {
        PestHighlighterModule module = PestHighlighterModule.INSTANCE;
        try {
            Field instance = PestHighlighterModule.class.getField("INSTANCE");
            if (!Modifier.isPublic(instance.getModifiers()) || !Modifier.isStatic(instance.getModifiers())
                || !Modifier.isFinal(instance.getModifiers()) || instance.get(null) != module) {
                throw new AssertionError("INSTANCE must remain a public static final Java singleton field");
            }
            Field publishedSightings = PestHighlighterModule.class.getDeclaredField("publishedSightings");
            if (!Modifier.isVolatile(publishedSightings.getModifiers())) {
                throw new AssertionError("Published sightings must remain volatile for render readers");
            }
            assertMethod("isActive", boolean.class);
            assertMethod("inactiveReason", String.class);
            assertMethod("tick", void.class);
            assertMethod("render", void.class, LevelRenderContext.class);
            assertMethod("renderHud", void.class, GuiGraphicsExtractor.class);
            assertMethod("isSettingVisible", boolean.class, Setting.class);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Pest Highlighter Java entrypoints changed", error);
        }
        if (!module.name().equals("Pest Highlighter")
            || !module.configName().equals("Pest Highlighter")
            || !module.description().equals("Highlights all known Garden pests by their head texture.")
            || module.category() != Category.FARMING
            || module.defaultEnabled()) {
            throw new AssertionError("Pest Highlighter module identity or fresh-install state changed");
        }
    }

    private static void assertMethod(String name, Class<?> returnType, Class<?>... parameters)
        throws ReflectiveOperationException {
        Method method = PestHighlighterModule.class.getMethod(name, parameters);
        if (method.getReturnType() != returnType || !Modifier.isPublic(method.getModifiers())) {
            throw new AssertionError("Expected public " + name + " Java entrypoint with return type "
                + returnType.getName());
        }
    }

    private static void checkSettingIdentityAndDefaults() {
        PestHighlighterModule module = PestHighlighterModule.INSTANCE;
        List<BooleanSetting> booleans = module.booleanSettings();
        List<NumberSetting> numbers = module.numberSettings();
        List<ColorSetting> colors = module.colorSettings();
        assertNames(booleans, List.of("Box", "Circle", "Tracer", "Show Name", "Depth Check"),
            "boolean persisted setting IDs");
        assertNames(numbers, List.of("Scan Interval", "Outline Width", "Circle Width", "Circle Radius",
            "Circle Speed", "Ring Count", "Tracer Width", "Label Size"), "number persisted setting IDs");
        assertNames(colors, List.of("Outline Color", "Fill Color", "Circle Color", "Tracer Color", "Name Color"),
            "color persisted setting IDs");

        assertBoolean(booleans.get(0), false, "Box");
        assertBoolean(booleans.get(1), true, "Circle");
        if (!booleans.get(1).displayName().equals("Ring Mode")) {
            throw new AssertionError("Circle must keep its persisted key and Ring Mode display alias");
        }
        assertBoolean(booleans.get(2), true, "Tracer");
        assertBoolean(booleans.get(3), false, "Show Name");
        assertBoolean(booleans.get(4), true, "Depth Check");

        assertNumber(numbers.get(0), 1.0f, 200.0f, 20.0f, true, "Scan Interval");
        assertNumber(numbers.get(1), 0.5f, 5.0f, 2.0f, false, "Outline Width");
        assertNumber(numbers.get(2), 0.5f, 5.0f, 5.0f, false, "Circle Width");
        assertNumber(numbers.get(3), 0.1f, 3.0f, 0.99761915f, false, "Circle Radius");
        assertNumber(numbers.get(4), 0.1f, 4.0f, 0.20129871f, false, "Circle Speed");
        assertNumber(numbers.get(5), 1.0f, 8.0f, 1.0f, true, "Ring Count");
        assertNumber(numbers.get(6), 0.5f, 5.0f, 2.0f, false, "Tracer Width");
        assertNumber(numbers.get(7), 0.5f, 4.0f, 1.0f, false, "Label Size");

        assertColor(colors.get(0), "Outline Color", 182, 47, 0, 255);
        assertColor(colors.get(1), "Fill Color", 182, 47, 0, 60);
        assertColor(colors.get(2), "Circle Color", 0, 167, 255, 255);
        assertColor(colors.get(3), "Tracer Color", 0, 201, 255, 255);
        assertColor(colors.get(4), "Name Color", 182, 47, 0, 255);
    }

    private static void checkGroupsAndVisibility() {
        PestHighlighterModule module = PestHighlighterModule.INSTANCE;
        List<BooleanSetting> booleans = module.booleanSettings();
        List<NumberSetting> numbers = module.numberSettings();
        List<ColorSetting> colors = module.colorSettings();
        BooleanSetting box = booleans.get(0);
        BooleanSetting circle = booleans.get(1);
        BooleanSetting tracer = booleans.get(2);
        BooleanSetting showName = booleans.get(3);
        BooleanSetting depthCheck = booleans.get(4);
        NumberSetting scanInterval = numbers.get(0);

        List<SettingGroup> groups = module.groups();
        if (groups.size() != 2 || !groups.get(0).name().equals("Display")
            || !groups.get(1).name().equals("Appearance")) {
            throw new AssertionError("Setting group names or order changed");
        }
        assertGroup(groups.get(0), List.of(box, circle, tracer, showName, depthCheck, scanInterval), List.of(),
            "Display");

        SettingGroup appearance = groups.get(1);
        assertGroup(appearance, List.of(), List.of("Box", "Ring", "Tracer", "Name"), "Appearance");
        List<SettingGroup> appearanceGroups = appearance.children();
        assertGroup(appearanceGroups.get(0), List.of(colors.get(0), numbers.get(1), colors.get(1)), List.of(),
            "Appearance / Box");
        assertGroup(appearanceGroups.get(1),
            List.of(colors.get(2), numbers.get(2), numbers.get(3), numbers.get(4), numbers.get(5)), List.of(),
            "Appearance / Ring");
        assertGroup(appearanceGroups.get(2), List.of(colors.get(3), numbers.get(6)), List.of(),
            "Appearance / Tracer");
        assertGroup(appearanceGroups.get(3), List.of(colors.get(4), numbers.get(7)), List.of(),
            "Appearance / Name");

        boolean[] previous = {
            box.rawValue(), circle.rawValue(), tracer.rawValue(), showName.rawValue(),
        };
        try {
            box.setValue(false);
            circle.setValue(false);
            tracer.setValue(false);
            showName.setValue(false);
            assertHidden(module, List.of(colors.get(0), numbers.get(1), colors.get(1)), "box settings");
            assertHidden(module, List.of(colors.get(2), numbers.get(2), numbers.get(3), numbers.get(4), numbers.get(5)),
                "ring settings");
            assertHidden(module, List.of(colors.get(3), numbers.get(6)), "tracer settings");
            assertHidden(module, List.of(colors.get(4), numbers.get(7)), "name settings");

            box.setValue(true);
            circle.setValue(true);
            tracer.setValue(true);
            showName.setValue(true);
            assertVisible(module, List.of(colors.get(0), numbers.get(1), colors.get(1)), "box settings");
            assertVisible(module,
                List.of(colors.get(2), numbers.get(2), numbers.get(3), numbers.get(4), numbers.get(5)),
                "ring settings");
            assertVisible(module, List.of(colors.get(3), numbers.get(6)), "tracer settings");
            assertVisible(module, List.of(colors.get(4), numbers.get(7)), "name settings");
            if (!module.isSettingVisible(depthCheck) || !module.isSettingVisible(scanInterval)) {
                throw new AssertionError("Depth Check and Scan Interval must stay visible");
            }
        } finally {
            box.setValue(previous[0]);
            circle.setValue(previous[1]);
            tracer.setValue(previous[2]);
            showName.setValue(previous[3]);
        }
    }

    private static void checkDiagnosticAppearanceSnapshot() {
        try {
            Method method = PestHighlighterModule.class.getDeclaredMethod("diagnosticAppearanceSummary");
            method.setAccessible(true);
            String summary = (String) method.invoke(PestHighlighterModule.INSTANCE);
            for (String setting : List.of("outline=B62F00FF@2.0", "fill=B62F003C", "ring=00A7FFFF@5.0",
                "/radius=0.99761915", "/speed=0.20129871", "/count=1", "tracer=00C9FFFF@2.0",
                "name=B62F00FF@1.0")) {
                if (!summary.contains(setting)) {
                    throw new AssertionError("Automatic diagnostics must record " + setting + ": " + summary);
                }
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Pest Highlighter appearance diagnostics changed", error);
        }
    }

    private static void checkBehaviorDecisions() {
        int ticksSinceScan = 0;
        for (int expectedTick = 1; expectedTick < 20; expectedTick++) {
            ticksSinceScan++;
            if (PestHighlighterBehavior.scanDue(ticksSinceScan, 20)) {
                throw new AssertionError("Default scan cadence must wait through tick " + expectedTick);
            }
        }
        ticksSinceScan++;
        if (!PestHighlighterBehavior.scanDue(ticksSinceScan, 20)) {
            throw new AssertionError("Default scan cadence must scan on tick 20 and reset its counter");
        }
        ticksSinceScan = 0;
        if (!PestHighlighterBehavior.scanDue(ticksSinceScan + 1, 1)) {
            throw new AssertionError("Minimum scan interval must scan on every client tick");
        }
        if (PestHighlighterBehavior.scanRangeBlocks(32.0) != 32.0
            || PestHighlighterBehavior.scanRangeBlocks(160.0) != 64.0) {
            throw new AssertionError("Pest scans must follow render distance and stop at 64 blocks");
        }
        if (!PestHighlighterBehavior.isCandidate(true, true)
            || PestHighlighterBehavior.isCandidate(false, true)
            || PestHighlighterBehavior.isCandidate(true, false)) {
            throw new AssertionError("Only living armor stands may enter the pest classification path");
        }
        if (!PestHighlighterBehavior.isCurrentSighting(false, true)
            || PestHighlighterBehavior.isCurrentSighting(true, true)
            || PestHighlighterBehavior.isCurrentSighting(false, false)) {
            throw new AssertionError("Removed or dead pest sightings must not be published or rendered");
        }
        int defaultLayers = PestHighlighterBehavior.worldLayerMask(false, true, true);
        int allLayers = PestHighlighterBehavior.worldLayerMask(true, true, true);
        if (defaultLayers != (PestHighlighterBehavior.RING_LAYER | PestHighlighterBehavior.TRACER_LAYER)
            || allLayers != (PestHighlighterBehavior.BOX_LAYER | PestHighlighterBehavior.RING_LAYER
                | PestHighlighterBehavior.TRACER_LAYER)
            || PestHighlighterBehavior.worldLayerMask(false, false, false) != 0) {
            throw new AssertionError("Box, ring, and tracer render choices must remain independent");
        }
    }

    private static void assertGroup(SettingGroup group, List<? extends Setting> expectedSettings,
        List<String> expectedChildren, String label) {
        if (group.toggle() != null || group.collapsedByDefault() || group.debugOnly() || group.stateKey() != null) {
            throw new AssertionError(label + " presentation options changed");
        }
        if (group.settings().size() != expectedSettings.size()) {
            throw new AssertionError(label + " setting count changed");
        }
        for (int index = 0; index < expectedSettings.size(); index++) {
            if (group.settings().get(index) != expectedSettings.get(index)) {
                throw new AssertionError(label + " must reference the persisted setting instances");
            }
        }
        List<String> childNames = group.children().stream().map(SettingGroup::name).toList();
        if (!childNames.equals(expectedChildren)) {
            throw new AssertionError(label + " children changed: " + childNames);
        }
    }

    private static void assertHidden(PestHighlighterModule module, List<? extends Setting> settings, String label) {
        for (Setting setting : settings) {
            if (module.isSettingVisible(setting)) throw new AssertionError(label + " should be hidden while disabled");
        }
    }

    private static void assertVisible(PestHighlighterModule module, List<? extends Setting> settings, String label) {
        for (Setting setting : settings) {
            if (!module.isSettingVisible(setting)) throw new AssertionError(label + " should be visible while enabled");
        }
    }

    private static void assertNames(List<? extends Setting> settings, List<String> expected, String label) {
        List<String> actual = new ArrayList<>();
        for (Setting setting : settings) actual.add(setting.name());
        if (!actual.equals(expected)) throw new AssertionError(label + " changed: " + actual);
    }

    private static void assertBoolean(BooleanSetting setting, boolean expected, String label) {
        if (setting.rawValue() != expected || setting.value() != expected || setting.isDebugOnly()) {
            throw new AssertionError(label + " default or visibility changed");
        }
    }

    private static void assertNumber(NumberSetting setting, float minimum, float maximum, float value,
        boolean integer, String label) {
        if (!close(setting.min(), minimum) || !close(setting.max(), maximum) || !close(setting.value(), value)
            || setting.isInteger() != integer) {
            throw new AssertionError(label + " bounds, default, or integer mode changed");
        }
    }

    private static void assertColor(ColorSetting setting, String name, int red, int green, int blue, int alpha) {
        if (!setting.name().equals(name) || setting.red() != red || setting.green() != green
            || setting.blue() != blue || setting.alpha() != alpha || !setting.alphaEditable()) {
            throw new AssertionError(name + " identity or RGBA default changed");
        }
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) < 0.0001f;
    }
}
