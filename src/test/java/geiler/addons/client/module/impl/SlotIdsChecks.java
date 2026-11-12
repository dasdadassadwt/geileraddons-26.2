package geiler.addons.client.module.impl;

import geiler.addons.client.gui.MoveUiScreen;
import geiler.addons.client.gui.SlotIdBadgeLayout;
import geiler.addons.client.hud.HudElement;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Focused checks for Slot IDs module identity, settings, editor behavior, and badge geometry. */
public final class SlotIdsChecks {
    private SlotIdsChecks() { }

    public static void main(String[] arguments) {
        run();
        System.out.println("Slot IDs checks passed");
    }

    public static void run() {
        checkModuleIdentity();
        checkSettingsAndEditorBehavior();
        checkBadgeGeometry();
        checkMoveScreenInterop();
    }

    private static void checkMoveScreenInterop() {
        try {
            check(MoveUiScreen.class.getConstructor(Screen.class) != null,
                "the shared HUD editor retains its public Java constructor");
            check(!java.lang.reflect.Modifier.isFinal(MoveUiScreen.class.getModifiers()),
                "the shared HUD editor remains subclassable for Java integrations");
            check(MoveUiScreen.class.getMethod("extractRenderState", GuiGraphicsExtractor.class,
                int.class, int.class, float.class).getReturnType() == void.class
                && MoveUiScreen.class.getMethod("mouseClicked", MouseButtonEvent.class, boolean.class)
                    .getReturnType() == boolean.class
                && MoveUiScreen.class.getMethod("mouseDragged", MouseButtonEvent.class,
                    double.class, double.class).getReturnType() == boolean.class
                && MoveUiScreen.class.getMethod("mouseReleased", MouseButtonEvent.class)
                    .getReturnType() == boolean.class
                && MoveUiScreen.class.getMethod("onClose").getReturnType() == void.class,
                "the shared HUD editor retains its Minecraft 26.2 render and input hooks");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Move Elements Java API changed", exception);
        }
    }

    private static void checkModuleIdentity() {
        SlotIdsModule module = SlotIdsModule.INSTANCE;
        check(module == SlotIdsModule.INSTANCE, "the Java-visible singleton remains stable");
        check(module.name().equals("Slot IDs") && module.configName().equals("Slot IDs"),
            "the module and persisted config identity remain stable");
        check(module.id().equals("slot_ids") && module.displayName().equals("Slot IDs"),
            "the HUD element id and display name remain stable");
        check(module.description().equals("Shows each container slot's runtime index for macro setup."),
            "the module description is preserved");
        check(module.category() == Category.DEV && !module.defaultEnabled(),
            "Slot IDs remains a disabled-by-default Dev module");

        // These calls compile from Java and preserve the signatures used by config, overlay, and Mixins.
        ColorSetting color = module.labelTextColor();
        check(color != null, "SlotIdOverlay can access the Java labelTextColor() method");
    }

    private static void checkSettingsAndEditorBehavior() {
        SlotIdsModule module = SlotIdsModule.INSTANCE;
        List<ColorSetting> colors = module.colorSettings();
        check(colors.size() == 1 && colors.getFirst().name().equals("Label Text Color")
            && colors.getFirst().displayName().equals("Label Text Color"),
            "the single color setting retains its id and label");
        check(colors.getFirst().argb() == SlotIdBadgeLayout.LABEL_COLOR,
            "the configurable label color keeps its default ARGB value");

        List<SettingGroup> groups = module.groups();
        check(groups.size() == 1 && Objects.equals(groups.getFirst().name(), "Display"),
            "the color setting remains under the Display group");
        List<String> groupRows = new ArrayList<>();
        for (Setting setting : groups.getFirst().settings()) groupRows.add(setting.name());
        check(groupRows.equals(List.of("Label Text Color")) && groups.getFirst().toggle() == null
            && !groups.getFirst().collapsedByDefault() && groups.getFirst().children().isEmpty(),
            "Display keeps its original single-row presentation");

        HudElement hud = module;
        check(hud.visible() && hud.previewMode().equals("sample") && !hud.renderOnHud(),
            "Move Elements keeps the sample visible while live badges stay attached to menu slots");
    }

    private static void checkBadgeGeometry() {
        List<SlotIdBadgeLayout.SlotPosition> slots = List.of(
            new SlotIdBadgeLayout.SlotPosition(8, 10, 12),
            new SlotIdBadgeLayout.SlotPosition(36, 28, 12),
            new SlotIdBadgeLayout.SlotPosition(123, 46, 12));
        List<SlotIdBadgeLayout.Badge> badges = SlotIdBadgeLayout.layout(slots, 100, 40,
            label -> label.length() * 6);

        check(badges.size() == slots.size(), "every menu slot receives a badge layout");
        for (int i = 0; i < badges.size(); i++) {
            SlotIdBadgeLayout.Badge badge = badges.get(i);
            SlotIdBadgeLayout.SlotPosition slot = slots.get(i);
            check(badge.label().equals(Integer.toString(slot.index())),
                "each badge labels the original runtime slot index");
            check(badge.x() + badge.width() <= 100 + slot.x() + 16
                && badge.y() == 40 + slot.y() + 1,
                "each badge remains anchored to its slot's top-right edge");
        }
        check(badges.get(0).scale() == SlotIdBadgeLayout.LABEL_SCALE
                && badges.get(2).scale() == SlotIdBadgeLayout.LABEL_SCALE,
            "all runtime indices use the reduced text scale");
        check(SlotIdBadgeLayout.BADGE_HEIGHT == 9 && SlotIdBadgeLayout.BADGE_PADDING == 2,
            "labels keep their compact anchor dimensions");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
