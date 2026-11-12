package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;

import java.util.List;

/** Direct checks for the Livid Helper module identity, Java adapter API, and HUD behavior. */
public final class LividHelperModuleChecks {
    private LividHelperModuleChecks() { }

    public static void run() {
        DungeonLividSolverModule module = DungeonLividSolverModule.INSTANCE;
        boolean previousEnabled = module.isEnabled();
        boolean hudBehaviorCheckStarted = false;
        BooleanSetting[] toggles = {
            DungeonLividSolverModule.highlight,
            DungeonLividSolverModule.showTimer,
            DungeonLividSolverModule.showTargetTitle,
            DungeonLividSolverModule.showTracer,
            DungeonLividSolverModule.useTargetColor,
        };
        boolean[] previousToggles = new boolean[toggles.length];
        for (int index = 0; index < toggles.length; index++) {
            previousToggles[index] = toggles[index].rawValue();
        }
        int previousRed = DungeonLividSolverModule.tracerColor.red();
        int previousGreen = DungeonLividSolverModule.tracerColor.green();
        int previousBlue = DungeonLividSolverModule.tracerColor.blue();
        int previousAlpha = DungeonLividSolverModule.tracerColor.alpha();
        float previousWidth = DungeonLividSolverModule.tracerWidth.value();

        try {
            for (BooleanSetting toggle : toggles) toggle.reset();
            DungeonLividSolverModule.tracerColor.reset();
            DungeonLividSolverModule.tracerWidth.reset();

            check(module.name().equals("Livid Helper")
                && module.configName().equals("Livid Solver")
                && module.id().equals("dungeon_livid_solver")
                && module.displayName().equals("Livid Helper")
                && module.category() == Category.F7
                && !module.defaultEnabled(), "module, config, and HUD identities remain stable");
            check(List.of("Highlight Livid", "Show Invulnerability Timer", "Show Target Color Title",
                    "Show Livid Tracer", "Match Target Color").equals(
                    module.booleanSettings().stream().map(Setting::name).toList()),
                "toggle setting IDs and order remain compatible");
            check(module.booleanSettings().equals(java.util.Arrays.asList(toggles)),
                "the Odin adapter's static setting fields are the registered module settings");
            check(java.util.Arrays.stream(toggles).allMatch(BooleanSetting::value)
                && DungeonLividSolverModule.showTimer.value(),
                "all five toggles and the Java adapter's timer setting default on");
            check(List.of("Tracer Color").equals(module.colorSettings().stream().map(Setting::name).toList())
                && DungeonLividSolverModule.tracerColor.argb() == 0xFFFF5555,
                "tracer color ID and default ARGB are retained");
            check(List.of("Tracer Width").equals(module.numberSettings().stream().map(Setting::name).toList())
                && DungeonLividSolverModule.tracerWidth.value() == 2.0f
                && DungeonLividSolverModule.tracerWidth.min() == 0.5f
                && DungeonLividSolverModule.tracerWidth.max() == 5.0f,
                "tracer width ID, range, and default remain unchanged");
            check(module.groups().size() == 1
                && module.groups().getFirst().name().equals("Livid")
                && module.groups().getFirst().toggle() == DungeonLividSolverModule.highlight
                && !module.groups().getFirst().collapsedByDefault()
                && module.groups().getFirst().settings().equals(List.of(
                    DungeonLividSolverModule.showTimer,
                    DungeonLividSolverModule.showTargetTitle,
                    DungeonLividSolverModule.showTracer,
                    DungeonLividSolverModule.useTargetColor,
                    DungeonLividSolverModule.tracerColor,
                    DungeonLividSolverModule.tracerWidth)),
                "the switched Livid group and child setting order are preserved");
            check(module.showTargetTitle() && module.showTracer() && module.useTargetColor()
                && module.tracerColorArgb() == 0xFFFF5555 && module.tracerWidth() == 2.0f,
                "Java adapter accessors retain their defaults and values");
            DungeonLividSolverModule.showTargetTitle.setValue(false);
            DungeonLividSolverModule.showTracer.setValue(false);
            DungeonLividSolverModule.useTargetColor.setValue(false);
            DungeonLividSolverModule.tracerColor.set(12, 34, 56, 78);
            DungeonLividSolverModule.tracerWidth.setValue(3.25f);
            check(!module.showTargetTitle() && !module.showTracer() && !module.useTargetColor()
                && module.tracerColorArgb() == 0x4E0C2238 && module.tracerWidth() == 3.25f,
                "Java adapter accessors reflect live title, tracer, color, and width setting changes");
            check(module.previewLines().equals(List.of("GRAY", "Invulnerable · 3.2s")),
                "the two-line Livid HUD preview shows only the color name");
            check(DungeonLividSolverModule.lividLabel("gray").equals("GRAY")
                && DungeonLividSolverModule.lividLabel("lime").equals("LIME")
                && DungeonLividSolverModule.lividLabel("Red").equals("RED"),
                "the Livid label is the upper-case color name only");

            check(module.previewMode().equals("sample") && !module.visible(),
                "the Livid HUD starts with its editor sample and no live content");
            hudBehaviorCheckStarted = true;
            module.setEnabled(false);
            module.publishHudLines(List.of("RED", "Livid invulnerability · 3.2s"), 0xFFFF5555);
            check(module.previewMode().equals("live") && !module.visible(),
                "published Livid lines remain hidden while the module is disabled");
            module.setEnabled(true);
            check(module.previewMode().equals("live") && module.visible(),
                "enabling Livid Helper exposes its published target and timer lines on the HUD");
            module.setEnabled(false);
            check(module.previewMode().equals("live") && !module.visible(),
                "disabling Livid Helper hides its live lines without changing their source mode");
            module.clearHudLines();
            check(module.previewMode().equals("sample") && !module.visible(),
                "clearing Livid output restores the editor sample and hides the live element");
        } finally {
            if (hudBehaviorCheckStarted) module.clearHudLines();
            module.setEnabled(previousEnabled);
            for (int index = 0; index < toggles.length; index++) {
                toggles[index].setValue(previousToggles[index]);
            }
            DungeonLividSolverModule.tracerColor.set(
                previousRed,
                previousGreen,
                previousBlue,
                previousAlpha
            );
            DungeonLividSolverModule.tracerWidth.setValue(previousWidth);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("LividHelperModuleChecks: " + message);
    }
}
