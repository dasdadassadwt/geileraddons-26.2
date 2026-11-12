package geiler.addons.client.module.impl;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.client.multiplayer.ClientLevel;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Direct checks for Glass Doors identity, its Java-facing entry points, and picker settings. */
public final class DungeonDoorBlockReplacementModuleChecks {
    private DungeonDoorBlockReplacementModuleChecks() { }

    public static void run() {
        DungeonDoorBlockReplacementModule module = DungeonDoorBlockReplacementModule.INSTANCE;
        check(module.name().equals("Glass Doors")
                && module.configName().equals("Dungeon Door Block Glass")
                && module.category() == Category.F7
                && !module.defaultEnabled(),
            "module display identity, persisted identity, category, and fresh-install state remain stable");

        try {
            var singleton = DungeonDoorBlockReplacementModule.class.getField("INSTANCE");
            check(singleton.getType() == DungeonDoorBlockReplacementModule.class
                    && singleton.get(null) == module,
                "the Java-facing singleton remains a public field on the module class");
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("DungeonDoorBlockReplacementModuleChecks: missing public INSTANCE", missing);
        }

        check(module.booleanSettings().stream().map(Setting::name).toList().equals(List.of(
                "Start Door Glass",
                "Wither Door Glass",
                "Blood Door Glass",
                "Use Mort-Based Start Detection"))
                && module.booleanSettings().stream().allMatch(setting -> setting.value()
                    == !setting.name().equals("Use Mort-Based Start Detection")),
            "the three door toggles and inert legacy key keep their persisted names and defaults");
        check(module.choiceSettings().stream().map(Setting::name).toList().equals(List.of(
                "Start Door Replacement",
                "Wither Door Replacement",
                "Blood Door Replacement")),
            "each replacement choice keeps its persisted setting name and order");
        check(module.choiceSettings().stream().map(ChoiceSetting::value).toList().equals(List.of(
                "glass",
                "black_stained_glass",
                "red_stained_glass")),
            "the picker defaults stay clear, black-stained, and red-stained glass");

        List<SettingGroup> groups = module.groups();
        check(groups.size() == 2
                && groups.get(0).name().equals("Door Types")
                && groups.get(1).name().equals("Replacement Blocks")
                && groups.get(1).collapsedByDefault(),
            "door toggles and folded replacement picker controls stay grouped as before");
        check(groups.get(1).settings().stream().map(ModuleAction.class::cast)
                .map(ModuleAction::label).toList().equals(List.of(
                    "Start Door Replacement: minecraft:glass",
                    "Wither Door Replacement: minecraft:black_stained_glass",
                    "Blood Door Replacement: minecraft:red_stained_glass")),
            "picker actions keep their door-specific labels and current registry IDs");
        check(groups.stream().flatMap(group -> group.settings().stream())
                .noneMatch(setting -> setting.name().equals("Use Mort-Based Start Detection")),
            "the compatibility key remains hidden from visible groups");
        check(module.actions().size() == 1
                && module.actions().getFirst().label().equals("Show Replacement Status"),
            "the replacement-status action remains registered");

        requireMethod("tick");
        requireMethod("recordStartDoorCaptureDiagnostic", ClientLevel.class, String.class);
        try {
            Method capture = DungeonDoorBlockReplacementModule.class.getMethod(
                "recordStartDoorCaptureDiagnostic", ClientLevel.class, String.class);
            check(Modifier.isStatic(capture.getModifiers())
                    && capture.getDeclaringClass() == DungeonDoorBlockReplacementModule.class,
                "the command capture hook remains a static Java-facing method on the module class");
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException(
                "DungeonDoorBlockReplacementModuleChecks: missing command capture hook", missing);
        }
    }

    private static void requireMethod(String name, Class<?>... parameterTypes) {
        try {
            Method method = DungeonDoorBlockReplacementModule.class.getMethod(name, parameterTypes);
            check(method.getDeclaringClass() == DungeonDoorBlockReplacementModule.class,
                "Java-facing method remains declared on the module class: " + name);
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException(
                "DungeonDoorBlockReplacementModuleChecks: missing Java-facing method " + name, missing);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("DungeonDoorBlockReplacementModuleChecks: " + message);
    }
}
