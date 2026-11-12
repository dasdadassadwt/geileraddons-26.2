package geiler.addons.client.module.impl;

import geiler.addons.client.gui.SecretWaypointEditorTextRules;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/** Java ABI and persisted-setting checks for the Kotlin-backed Secret Waypoints owner. */
public final class SecretWaypointsModuleKotlinChecks {
    private SecretWaypointsModuleKotlinChecks() { }

    public static void run() {
        checkJavaAbi();
        checkDiagnosticScheduling();
        checkSettingsAndActions();
        checkEditorNameWhitespace();
    }

    private static void checkDiagnosticScheduling() {
        boolean waypointRuntimeOutsideDungeon = SecretWaypointDiagnosticsPolicy.hasRuntimeConsumer(true, false, false);
        boolean clickRuntimeOutsideDungeon = SecretWaypointDiagnosticsPolicy.hasRuntimeConsumer(false, true, false);
        check(!waypointRuntimeOutsideDungeon && !clickRuntimeOutsideDungeon,
            "hasRuntimeConsumer keeps both features dungeon-gated");
        check(SecretWaypointDiagnosticsPolicy.shouldRunDiagnosticsOnlyTick(true, false, waypointRuntimeOutsideDungeon),
            "enabled Secret Waypoints keeps automatic diagnostics running outside a dungeon");
        check(SecretWaypointDiagnosticsPolicy.shouldRunDiagnosticsOnlyTick(false, true, clickRuntimeOutsideDungeon),
            "enabled Secret Clicked keeps the shared waypoint trace running outside a dungeon");
        check(!SecretWaypointDiagnosticsPolicy.shouldRunDiagnosticsOnlyTick(false, false, false),
            "the diagnostics-only tick stays idle when both features are disabled");
        boolean waypointRuntimeInDungeon = SecretWaypointDiagnosticsPolicy.hasRuntimeConsumer(true, false, true);
        boolean clickRuntimeInDungeon = SecretWaypointDiagnosticsPolicy.hasRuntimeConsumer(false, true, true);
        check(waypointRuntimeInDungeon && clickRuntimeInDungeon,
            "either enabled feature activates the shared runtime in a dungeon");
        check(!SecretWaypointDiagnosticsPolicy.shouldRunDiagnosticsOnlyTick(true, true, waypointRuntimeInDungeon),
            "active dungeon consumers leave diagnostics to the normal dungeon tick");

        check(SecretWaypointDiagnosticsPolicy.shouldWriteStatus(Long.MIN_VALUE, 0, false, false),
            "the first automatic status is emitted immediately");
        check(!SecretWaypointDiagnosticsPolicy.shouldWriteStatus(100, 119, true, false)
                && SecretWaypointDiagnosticsPolicy.shouldWriteStatus(100, 120, true, false),
            "changed status is coalesced to the 20-tick minimum");
        check(!SecretWaypointDiagnosticsPolicy.shouldWriteStatus(120, 219, false, false)
                && SecretWaypointDiagnosticsPolicy.shouldWriteStatus(120, 220, false, false),
            "unchanged status uses a 100-tick heartbeat");
        check(SecretWaypointDiagnosticsPolicy.shouldWriteStatus(100, 101, false, true),
            "enable and disable lifecycle status can bypass cadence coalescing");
        check(SecretWaypointDiagnosticsPolicy.shouldWriteStatus(100, 90, false, false),
            "a client-tick reset starts a fresh status cadence");
    }

    private static void checkEditorNameWhitespace() {
        check(SecretWaypointEditorTextRules.nameForAdd(null).equals("Personal Secret")
                && SecretWaypointEditorTextRules.nameForAdd(" \t\n").equals("Personal Secret")
                && SecretWaypointEditorTextRules.nameForAdd("\u2003").equals("Personal Secret"),
            "editor falls back for null and Java-defined whitespace-only names");
        check(SecretWaypointEditorTextRules.nameForAdd("\u00A0").equals("\u00A0"),
            "editor preserves a non-breaking-space name as Java String.isBlank does");
        check(SecretWaypointEditorTextRules.nameForAdd("  marker  ").equals("  marker  "),
            "editor preserves nonblank user text without trimming it");
    }

    private static void checkJavaAbi() {
        check(Modifier.isPublic(SecretWaypointsModule.class.getModifiers())
            && Modifier.isFinal(SecretWaypointsModule.class.getModifiers()),
            "the Kotlin owner keeps its public final Java class");
        try {
            Field instance = SecretWaypointsModule.class.getField("INSTANCE");
            check(Modifier.isPublic(instance.getModifiers()) && Modifier.isStatic(instance.getModifiers())
                && Modifier.isFinal(instance.getModifiers()) && instance.getType() == SecretWaypointsModule.class
                && instance.get(null) == SecretWaypointsModule.INSTANCE,
                "Java callers retain the public static final SecretWaypointsModule.INSTANCE field");
            checkMethod("isSettingVisible", boolean.class, Setting.class);
            checkMethod("isActive", boolean.class);
            checkMethod("inactiveReason", String.class);
            checkMethod("hasRuntimeConsumer", boolean.class);
            checkMethod("tick", void.class);
            checkMethod("render", void.class, LevelRenderContext.class);
            checkMethod("renderHud", void.class, GuiGraphicsExtractor.class);
            checkMethod("catalogStatus", String.class);
            checkMethod("onChatMessage", void.class, String.class);
            checkMethod("onSecretBlockEvent", void.class, ClientboundBlockEventPacket.class);
            checkMethod("onSecretBlockUpdate", void.class, BlockPos.class, BlockState.class, BlockState.class);
            checkMethod("onSecretPickupPacket", void.class, ClientboundTakeItemEntityPacket.class);
            checkMethod("onBatDeathSound", void.class, String.class, double.class, double.class, double.class);
            checkDeclaredMethod("onDisable", void.class, Modifier.PROTECTED);
            checkDeclaredMethod("onSecretClickedEnabled", void.class, 0);
            checkDeclaredMethod("onSecretClickedDisabled", void.class, 0);
            checkDeclaredMethod("clearClickFeedbackFromFeature", void.class, 0);
            checkRecord("RoomCell", "gridX", "gridZ", "baseX", "baseZ");
            checkRecord("Target", "id", "label", "x", "y", "z", "color", "entityId", "waypoint", "type",
                "collectionKey");
            checkRecord("PendingPickup", "type", "entity", "target", "itemCountBefore", "frameHadItem",
                "geometry", "deadlineNanos");
            checkRecord("FeedbackGeometry", "bounds", "source");
            checkRecord("ClickHighlight", "target", "bounds", "geometrySource", "deadlineNanos", "locked");
            checkRecord("CollectionMatch", "key", "target");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Kotlin Secret Waypoints Java ABI is incomplete", exception);
        }
    }

    private static void checkSettingsAndActions() {
        SecretWaypointsModule module = SecretWaypointsModule.INSTANCE;
        BooleanSetting[] booleans = module.booleanSettings().toArray(BooleanSetting[]::new);
        boolean[] previousBooleans = new boolean[booleans.length];
        for (int index = 0; index < booleans.length; index++) previousBooleans[index] = booleans[index].rawValue();
        BooleanSetting keepCollected = Arrays.stream(booleans)
            .filter(setting -> setting.name().equals("Keep Collected Markers Visible"))
            .findFirst().orElseThrow();
        ColorSetting[] colors = module.colorSettings().toArray(ColorSetting[]::new);
        int[][] previousColors = Arrays.stream(colors)
            .map(color -> new int[] {color.red(), color.green(), color.blue(), color.alpha()})
            .toArray(int[][]::new);
        NumberSetting[] numbers = module.numberSettings().toArray(NumberSetting[]::new);
        float[] previousNumbers = new float[numbers.length];
        for (int index = 0; index < numbers.length; index++) previousNumbers[index] = numbers[index].value();
        TextSetting[] texts = module.textSettings().toArray(TextSetting[]::new);
        String[] previousTexts = Arrays.stream(texts).map(TextSetting::value).toArray(String[]::new);

        try {
            for (BooleanSetting setting : booleans) setting.reset();
            for (ColorSetting setting : colors) setting.reset();
            for (NumberSetting setting : numbers) setting.reset();
            for (TextSetting setting : texts) setting.reset();

            check(module.name().equals("Secret Waypoints") && module.configName().equals("Secret Waypoints")
                && module.description().equals("Shows authored, personal, and loaded-room secret markers.")
                && module.category() == Category.F7 && !module.defaultEnabled() && module.hasSettings(),
                "module identity, config key, description, category, and disabled default remain stable");
            check(List.of("Show Waypoints", "Find Live Markers", "Chests", "Dropped Items", "Essence", "Bats",
                    "Item Frames", "Redstone Keys", "Levers", "Keep Collected Markers Visible").equals(
                    module.booleanSettings().stream().map(Setting::name).toList())
                && allEnabledExcept(booleans, keepCollected)
                && !keepCollected.rawValue(),
                "waypoint/live-marker setting IDs and defaults remain stable, including the persisted collected-markers toggle");
            check(List.of("Waypoint Name", "Personal Waypoint ID", "Built-in Waypoint ID").equals(
                    module.textSettings().stream().map(Setting::name).toList())
                && texts[0].value().equals("Personal Secret") && texts[1].value().isEmpty()
                && texts[2].value().isEmpty()
                && Arrays.stream(texts).mapToInt(TextSetting::maxLength).boxed().toList().equals(List.of(64, 128, 128)),
                "personal and built-in waypoint text keys, defaults, and length bounds remain stable");
            check(List.of("Marker Range").equals(module.numberSettings().stream().map(Setting::name).toList())
                && numbers[0].value() == 64f && numbers[0].min() == 8f && numbers[0].max() == 128f
                && numbers[0].isInteger(),
                "marker range key, default, bounds, and integer mode remain stable");
            check(List.of("Personal Waypoint Color", "Built-in Waypoint Color").equals(
                    module.colorSettings().stream().map(Setting::name).toList())
                && colors[0].argb() == 0xFF38CAFF && colors[1].argb() == 0xFF5FFF87,
                "personal and built-in waypoint color keys and defaults remain stable");
            List<SettingGroup> groups = module.groups();
            check(groups.size() == 2
                && groups.stream().map(SettingGroup::name).toList().equals(List.of("Waypoints", "Live Markers"))
                && groups.get(0).toggle() == booleans[0]
                && groups.get(0).settings().stream().map(Setting::name).toList().equals(List.of(
                    "Waypoint Name", "Personal Waypoint ID", "Built-in Waypoint ID", "Marker Range",
                    "Personal Waypoint Color", "Built-in Waypoint Color", "Keep Collected Markers Visible"))
                && groups.get(0).settings().contains(keepCollected)
                && groups.get(1).toggle() == booleans[1]
                && groups.get(1).settings().stream().map(Setting::name).toList().equals(List.of(
                    "Chests", "Dropped Items", "Essence", "Bats", "Redstone Keys", "Levers")),
                "waypoint and live-marker settings groups retain their controls and ordering");
            BooleanSetting itemFrames = Arrays.stream(booleans)
                .filter(setting -> setting.name().equals("Item Frames"))
                .findFirst().orElseThrow();
            check(!module.isSettingVisible(itemFrames), "the persisted Item Frames setting stays hidden from the editor");
            check(module.actions().stream().map(ModuleAction::name).toList().equals(List.of(
                    "Add Waypoint at Crosshair", "Rename Most Recent Waypoint", "Update Most Recent at Crosshair",
                    "Remove Most Recent Waypoint", "List Personal Waypoints", "Rename Selected Personal Waypoint",
                    "Update Selected at Crosshair", "Remove Selected Personal Waypoint", "Hide Selected Built-in Waypoint",
                    "Replace Selected Built-in at Crosshair", "Restore Selected Built-in Waypoint", "Show Catalog Status",
                    "Edit Current Room Waypoints", "Copy Waypoints JSON", "Import Waypoints JSON")),
                "personal and built-in waypoint actions retain their labels and order");
        } finally {
            for (int index = 0; index < booleans.length; index++) booleans[index].setValue(previousBooleans[index]);
            for (int index = 0; index < colors.length; index++) {
                int[] color = previousColors[index];
                colors[index].set(color[0], color[1], color[2], color[3]);
            }
            for (int index = 0; index < numbers.length; index++) numbers[index].setValue(previousNumbers[index]);
            for (int index = 0; index < texts.length; index++) texts[index].setValue(previousTexts[index]);
        }
    }

    private static void checkMethod(String name, Class<?> returnType, Class<?>... parameterTypes)
        throws ReflectiveOperationException {
        Method method = SecretWaypointsModule.class.getMethod(name, parameterTypes);
        check(method.getReturnType() == returnType && !Modifier.isStatic(method.getModifiers()),
            "Java callers retain instance method " + name + " with its expected signature");
    }

    private static void checkDeclaredMethod(String name, Class<?> returnType, int expectedVisibility)
        throws ReflectiveOperationException {
        Method method = SecretWaypointsModule.class.getDeclaredMethod(name);
        int actualVisibility = method.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
        check(method.getReturnType() == returnType && !Modifier.isStatic(method.getModifiers())
                && (actualVisibility == expectedVisibility || actualVisibility == Modifier.PUBLIC),
            "Java lifecycle/feature callers retain declared method " + name + " with its expected visibility");
    }

    private static void checkRecord(String simpleName, String... components) throws ReflectiveOperationException {
        Class<?> record = Arrays.stream(SecretWaypointsModule.class.getDeclaredClasses())
            .filter(nested -> nested.getSimpleName().equals(simpleName))
            .findFirst().orElseThrow(() -> new AssertionError("Missing nested record " + simpleName));
        check(record.isRecord() && Modifier.isPrivate(record.getModifiers())
                && Arrays.stream(record.getRecordComponents()).map(component -> component.getName()).toList()
                    .equals(List.of(components)),
            "Java callers retain the private " + simpleName + " record components");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("SecretWaypointsModuleKotlinChecks: " + message);
    }

    private static boolean allEnabledExcept(BooleanSetting[] settings, BooleanSetting excluded) {
        for (BooleanSetting setting : settings) if (setting != excluded && !setting.rawValue()) return false;
        return true;
    }
}
