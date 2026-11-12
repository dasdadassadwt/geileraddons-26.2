package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Direct checks for I4 context, settings, parsing, transitions, and aim geometry. */
public final class I4HelperModuleChecks {
    private I4HelperModuleChecks() {
    }

    public static void run() {
        identityAndSettings();
        dungeonContextAndDeviceBounds();
        panelAndTransitions();
        completionMessagesAndSubtitles();
        aimGeometry();
    }

    private static void identityAndSettings() {
        I4HelperModule module = I4HelperModule.INSTANCE;
        check("i4 helper".equals(module.name()), "the module ID remains stable");
        check("i4 helper".equals(module.configName()), "the persisted config identity remains stable");
        check(module.category() == Category.F7, "the module remains in F7");
        check(!module.defaultEnabled(), "the feature remains disabled on a fresh install");
        check("Highlights the F7/M7 device panel and marks the next block to click.".equals(module.description()),
            "the module description remains stable");
        check(module.colorSettings().stream().map(ColorSetting::name).toList().equals(List.of(
                "Completed Block", "Active Block", "Aim Sphere", "Prefire Sphere")),
            "color setting IDs and order remain stable");
        check(module.colorSettings().stream().map(ColorSetting::hex).toList().equals(List.of(
                "FF0000FF", "00FF00FF", "FFFF00C8", "FF8C00C8")),
            "block and sphere color defaults remain stable");
        check(module.numberSettings().stream().map(NumberSetting::name).toList().equals(List.of(
                "Aim Sphere Size", "Prefire Sphere Size")),
            "sphere size setting IDs remain stable");
        check(module.numberSettings().get(0).min() == 0.05f
                && module.numberSettings().get(0).max() == 0.6f
                && module.numberSettings().get(0).value() == 0.25f
                && module.numberSettings().get(1).min() == 0.05f
                && module.numberSettings().get(1).max() == 0.6f
                && module.numberSettings().get(1).value() == 0.2f,
            "sphere size ranges and defaults remain stable");
        check(module.groups().stream().map(SettingGroup::name).toList().equals(List.of("Blocks", "Aim Spheres")),
            "the settings groups remain stable");
        check(module.groups().get(0).settings().stream().map(setting -> setting.name()).toList()
                .equals(List.of("Completed Block", "Active Block")),
            "block colors stay grouped together");
        check(module.groups().get(1).settings().stream().map(setting -> setting.name()).toList()
                .equals(List.of("Aim Sphere", "Aim Sphere Size", "Prefire Sphere", "Prefire Sphere Size")),
            "each sphere color stays next to its size");
        check("i4 Done!".equals(I4HelperRules.DONE_TITLE)
                && I4HelperRules.DONE_TITLE_DURATION_TICKS == 30,
            "completion keeps the same green title text and duration");
        check(I4HelperRules.IMMEDIATE_SOUND_PITCH == 1.414f
                && I4HelperRules.FIRST_DELAYED_SOUND_TICKS == 3
                && I4HelperRules.FIRST_DELAYED_SOUND_PITCH == 1.587f
                && I4HelperRules.SECOND_DELAYED_SOUND_TICKS == 6
                && I4HelperRules.SECOND_DELAYED_SOUND_PITCH == 1.782f,
            "the immediate and two delayed completion sounds keep their original timing and pitch");
    }

    private static void dungeonContextAndDeviceBounds() {
        check(I4HelperModule.supportsDungeonContext(true, DungeonFloor.F7), "I4 activates in F7");
        check(I4HelperModule.supportsDungeonContext(true, DungeonFloor.M7), "I4 activates in M7");
        check(!I4HelperModule.supportsDungeonContext(false, DungeonFloor.F7),
            "a floor label without dungeon context stays inactive");
        check(!I4HelperModule.supportsDungeonContext(true, DungeonFloor.F6)
                && !I4HelperModule.supportsDungeonContext(true, null),
            "other and unknown floors stay inactive");

        check(I4HelperRules.isOnDevice(63.5, 127, 35.0), "the plate center activates the device");
        check(!I4HelperRules.isOnDevice(62.0, 127, 35.0)
                && !I4HelperRules.isOnDevice(65.0, 127, 35.0)
                && !I4HelperRules.isOnDevice(63.5, 126, 35.0)
                && !I4HelperRules.isOnDevice(63.5, 128, 35.0)
                && !I4HelperRules.isOnDevice(63.5, 127, 33.0)
                && !I4HelperRules.isOnDevice(63.5, 127, 37.0),
            "device bounds remain strict at every edge and exact feet height");
        check(I4HelperRules.isOnDevice(62.0 + 1e-8, 127, 35.0),
            "a coordinate strictly inside the plate boundary activates");
    }

    private static void panelAndTransitions() {
        check(I4HelperRules.panelSize() == 9, "the panel keeps nine tracked positions");
        List<BlockPos> expected = List.of(
            new BlockPos(68, 130, 50), new BlockPos(66, 130, 50), new BlockPos(64, 130, 50),
            new BlockPos(68, 128, 50), new BlockPos(66, 128, 50), new BlockPos(64, 128, 50),
            new BlockPos(68, 126, 50), new BlockPos(66, 126, 50), new BlockPos(64, 126, 50));
        for (int i = 0; i < expected.size(); i++) {
            check(expected.get(i).equals(I4HelperRules.panelPosition(i)), "panel coordinate " + i + " stays stable");
            check(I4HelperRules.findPanelIndex(expected.get(i)) == i, "panel position maps to its own state slot");
        }
        check(I4HelperRules.findPanelIndex(new BlockPos(64, 127, 50)) == -1
                && I4HelperRules.findPanelIndex(null) == -1,
            "off-panel and null updates are ignored");

        check(I4HelperRules.afterBlockChange(I4HelperRules.NONE, true, false) == I4HelperRules.REMAINING,
            "blue terracotta marks an untouched block as a future target");
        check(I4HelperRules.afterBlockChange(I4HelperRules.REMAINING, false, true) == I4HelperRules.ACTIVE,
            "emerald marks the active target");
        check(I4HelperRules.afterBlockChange(I4HelperRules.ACTIVE, true, false) == I4HelperRules.COMPLETED,
            "an active target reverting to blue becomes completed");
        check(I4HelperRules.afterBlockChange(I4HelperRules.COMPLETED, false, false) == I4HelperRules.COMPLETED,
            "unrelated block changes preserve completed state");
        check(I4HelperRules.afterBlockChange(I4HelperRules.COMPLETED, true, false) == I4HelperRules.REMAINING,
            "blue updates retain the current transition semantics");
    }

    private static void completionMessagesAndSubtitles() {
        I4HelperModule module = I4HelperModule.INSTANCE;
        boolean wasEnabled = module.isEnabled();
        module.setEnabled(false);
        try {
            module.onChatMessage(null);
            check(!module.onSubtitle(null), "public chat and subtitle entry points retain null-safe guards");
        } finally {
            module.setEnabled(wasEnabled);
        }

        check("Test_User".equals(I4HelperRules.completedDeviceUsername(
                "§a[MVP+] Test_User completed a device! (1/1)")),
            "formatted completion text with a rank prefix parses");
        check("Player".equals(I4HelperRules.completedDeviceUsername("Player completed a device! (9/9)")),
            "unranked completion text parses");
        check(I4HelperRules.completedDeviceUsername("Other completed a device! (1/10)") == null
                && I4HelperRules.completedDeviceUsername("Other completed a device! (1/1).") == null
                && I4HelperRules.completedDeviceUsername("A player completed a device! (1/1)") == null,
            "completion parsing keeps its exact count and username shape");

        check(I4HelperRules.shouldSuppressSubtitle("Other completed a device! (1/1)"),
            "device completion subtitles are suppressed");
        check(I4HelperRules.shouldSuppressSubtitle("Other activated a terminal! (3/7)"),
            "terminal activation subtitles are suppressed");
        check(I4HelperRules.shouldSuppressSubtitle("Other activated a lever! (1/2)"),
            "lever activation subtitles are suppressed");
        check(I4HelperRules.shouldSuppressSubtitle("The gate will open in 5 seconds!")
                && I4HelperRules.shouldSuppressSubtitle("The gate has been destroyed!"),
            "the two exact gate subtitles are suppressed");
        check(!I4HelperRules.shouldSuppressSubtitle("The gate will open in 4 seconds!")
                && !I4HelperRules.shouldSuppressSubtitle("Other completed a device! (1/10)"),
            "unrelated subtitle text remains visible");
    }

    private static void aimGeometry() {
        List<Vec3> left = I4HelperRules.aimSpots(0, List.of());
        check(left.size() == 1 && close(left.get(0), 67.5, 131.0, 50.0),
            "the rightmost block aims to its outside edge");
        List<Vec3> right = I4HelperRules.aimSpots(2, List.of());
        check(right.size() == 1 && close(right.get(0), 65.5, 131.0, 50.0),
            "the leftmost block aims to its outside edge");
        List<Vec3> center = I4HelperRules.aimSpots(1, List.of());
        check(center.size() == 2 && close(center.get(0), 65.5, 131.0, 50.0)
                && close(center.get(1), 67.5, 131.0, 50.0),
            "the center block presents both sides before either neighbor is complete");
        List<Vec3> onlyRight = I4HelperRules.aimSpots(1, List.of(2));
        check(onlyRight.size() == 1 && close(onlyRight.get(0), 67.5, 131.0, 50.0),
            "a completed left neighbor selects the right aim side");
        List<Vec3> onlyLeft = I4HelperRules.aimSpots(1, List.of(0));
        check(onlyLeft.size() == 1 && close(onlyLeft.get(0), 65.5, 131.0, 50.0),
            "a completed right neighbor selects the left aim side");
        check(I4HelperRules.aimSpots(1, List.of(0, 2)).size() == 2,
            "two completed neighbors retain the two-side fallback");
        check(I4HelperRules.matchesAny(center.get(0), center)
                && !I4HelperRules.matchesAny(new Vec3(1, 2, 3), center),
            "only coincident prefire positions are skipped");
    }

    private static boolean close(Vec3 actual, double x, double y, double z) {
        return Math.abs(actual.x - x) < 1e-9 && Math.abs(actual.y - y) < 1e-9
            && Math.abs(actual.z - z) < 1e-9;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
