package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Setting;

import java.util.List;

/** Direct checks for Terminal Stats identity, Java adapter fields, settings, and HUD preview. */
public final class DungeonTerminalStatsModuleChecks {
    private DungeonTerminalStatsModuleChecks() { }

    public static void run() {
        DungeonTerminalStatsModule module = DungeonTerminalStatsModule.INSTANCE;
        BooleanSetting[] toggles = {DungeonTerminalStatsModule.breakdown, DungeonTerminalStatsModule.selfOnly};
        boolean[] previous = {toggles[0].rawValue(), toggles[1].rawValue()};
        try {
            toggles[0].reset();
            toggles[1].reset();
            check(module.name().equals("Terminal Stats") && module.configName().equals("Terminal Stats")
                && module.id().equals("dungeon_terminal_stats") && module.displayName().equals("Terminal Stats")
                && module.category() == Category.F7 && !module.defaultEnabled(),
                "module, config, and HUD identities remain stable");
            check(module.booleanSettings().equals(java.util.Arrays.asList(toggles))
                && module.booleanSettings().stream().map(Setting::name).toList()
                    .equals(List.of("Show Breakdown", "Count Self Only")),
                "Java-visible setting fields preserve the persisted IDs and order");
            check(DungeonTerminalStatsModule.breakdown.value() && !DungeonTerminalStatsModule.selfOnly.value(),
                "breakdown defaults on and self-only defaults off");
            check(module.groups().size() == 1 && module.groups().getFirst().name().equals("Breakdown")
                && module.groups().getFirst().toggle() == DungeonTerminalStatsModule.breakdown
                && !module.groups().getFirst().collapsedByDefault()
                && module.groups().getFirst().settings().equals(List.of(DungeonTerminalStatsModule.selfOnly)),
                "the switched Breakdown group preserves its toggle and child order");
            check(module.previewLines().equals(List.of("Terminals 4 · Levers 1 · Devices 2")),
                "the Terminal Stats HUD editor preview remains stable");
        } finally {
            toggles[0].setValue(previous[0]);
            toggles[1].setValue(previous[1]);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("DungeonTerminalStatsModuleChecks: " + message);
    }
}
