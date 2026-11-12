package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.dungeon.SecretWaypointClickFeedbackRules;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;

import java.util.Arrays;
import java.util.List;

/** Focused compatibility checks for Secret Clicked identity, settings, and policy gates. */
public final class SecretClickedModuleChecks {
    private SecretClickedModuleChecks() { }

    public static void run() {
        SecretClickedModule module = SecretClickedModule.INSTANCE;
        BooleanSetting[] booleans = {
            SecretClickedModule.boxes,
            SecretClickedModule.depthCheck,
            SecretClickedModule.boxesInBoss,
            SecretClickedModule.itemBoxes,
            SecretClickedModule.chime,
            SecretClickedModule.allowSecretBlocksInBoss,
            SecretClickedModule.chimeInBoss,
        };
        boolean[] previousBooleans = new boolean[booleans.length];
        for (int index = 0; index < booleans.length; index++) previousBooleans[index] = booleans[index].rawValue();
        ChoiceSetting boxStyle = SecretClickedModule.boxStyle;
        String previousBoxStyle = boxStyle.value();
        ColorSetting[] colors = {SecretClickedModule.boxColor, SecretClickedModule.lockedColor};
        int[][] previousColors = new int[colors.length][4];
        for (int index = 0; index < colors.length; index++) {
            previousColors[index] = new int[] {
                colors[index].red(), colors[index].green(), colors[index].blue(), colors[index].alpha(),
            };
        }
        NumberSetting[] numbers = {
            SecretClickedModule.timeToStay,
            SecretClickedModule.feedbackWindowMillis,
            SecretClickedModule.volume,
            SecretClickedModule.pitch,
        };
        float[] previousNumbers = new float[numbers.length];
        for (int index = 0; index < numbers.length; index++) previousNumbers[index] = numbers[index].value();
        TextSetting sound = SecretClickedModule.sound;
        String previousSound = sound.value();
        boolean enabled = module.isEnabled();

        try {
            for (BooleanSetting setting : booleans) setting.reset();
            boxStyle.reset();
            for (ColorSetting setting : colors) setting.reset();
            for (NumberSetting setting : numbers) setting.reset();
            sound.reset();

            check(module.name().equals("Secret Clicked")
                && module.configName().equals("Secret Clicked")
                && module.category() == Category.F7
                && !module.defaultEnabled(), "module identity, category, and disabled default remain stable");
            check(List.of("Secret Boxes", "Depth Check", "Box In Boss", "Item Boxes", "Play Chime",
                    "Allow Secret Blocks in Boss", "Chime In Boss").equals(
                    module.booleanSettings().stream().map(Setting::name).toList()),
                "boolean setting IDs and declaration order remain compatible");
            check(Arrays.asList(booleans).equals(module.booleanSettings())
                && Arrays.stream(booleans).map(BooleanSetting::rawValue).toList().equals(
                    List.of(true, false, false, true, true, false, false)),
                "Java-visible boolean fields remain registered with their original defaults");
            check(SecretClickedModule.chime.displayName().equals("Secret Chime")
                && SecretClickedModule.allowSecretBlocksInBoss.displayName().equals("Track Secret Blocks in Boss"),
                "the persisted setting IDs retain their player-facing aliases");

            check(List.of("Box Style").equals(module.choiceSettings().stream().map(Setting::name).toList())
                && boxStyle.value().equals("FILLED_OUTLINE")
                && boxStyle.displayValue().equals("Filled Outline")
                && boxStyle.choices().equals(List.of("OUTLINE", "FILLED", "FILLED_OUTLINE")),
                "box style values, labels, order, and default remain stable");
            check(List.of("Box Color", "Locked Color").equals(
                    module.colorSettings().stream().map(Setting::name).toList())
                && SecretClickedModule.boxColor.displayName().equals("Box Outline Color")
                && SecretClickedModule.boxColor.argb() == 0xCCFFAA00
                && SecretClickedModule.lockedColor.displayName().equals("Locked Outline Color")
                && SecretClickedModule.lockedColor.argb() == 0xCCFF5555,
                "box color setting IDs, labels, and default alpha colors remain stable");
            check(List.of("Time To Stay", "Confirmation Window (ms)", "Chime Volume", "Chime Pitch").equals(
                    module.numberSettings().stream().map(Setting::name).toList())
                && SecretClickedModule.timeToStay.displayName().equals("Box Time To Stay (s)")
                && SecretClickedModule.timeToStay.value() == 7f
                && SecretClickedModule.timeToStay.min() == 0.5f
                && SecretClickedModule.timeToStay.max() == 120f
                && SecretClickedModule.timeToStay.prefersTextInput()
                && SecretClickedModule.feedbackWindowMillis.value()
                    == SecretWaypointClickFeedbackRules.DEFAULT_WINDOW_MILLIS
                && SecretClickedModule.feedbackWindowMillis.min()
                    == SecretWaypointClickFeedbackRules.MIN_WINDOW_MILLIS
                && SecretClickedModule.feedbackWindowMillis.max()
                    == SecretWaypointClickFeedbackRules.MAX_WINDOW_MILLIS
                && SecretClickedModule.feedbackWindowMillis.isInteger()
                && SecretClickedModule.volume.value() == 1.5f
                && SecretClickedModule.volume.min() == 0f
                && SecretClickedModule.volume.max() == 2f
                && SecretClickedModule.pitch.value() == 1f
                && SecretClickedModule.pitch.min() == 0.1f
                && SecretClickedModule.pitch.max() == 2f,
                "timer, confirmation, and sound numeric IDs, aliases, defaults, and bounds remain stable");
            check(List.of("Chime Sound").equals(module.textSettings().stream().map(Setting::name).toList())
                && sound.value().equals("entity.blaze.hurt") && sound.maxLength() == 96,
                "chime sound ID, default, and text bound remain stable");

            List<SettingGroup> groups = module.groups();
            check(groups.size() == 3
                && groups.stream().map(SettingGroup::name).toList().equals(
                    List.of("Secret Boxes", "Secret Chime", "Click Confirmation"))
                && groups.get(0).toggle() == SecretClickedModule.boxes
                && !groups.get(0).collapsedByDefault()
                && groups.get(0).settings().equals(List.of(boxStyle, SecretClickedModule.boxColor,
                    SecretClickedModule.depthCheck, SecretClickedModule.lockedColor,
                    SecretClickedModule.timeToStay, SecretClickedModule.boxesInBoss, SecretClickedModule.itemBoxes))
                && groups.get(1).toggle() == SecretClickedModule.chime
                && !groups.get(1).collapsedByDefault()
                && groups.get(1).settings().equals(List.of(SecretClickedModule.chimeInBoss, sound,
                    SecretClickedModule.volume, SecretClickedModule.pitch))
                && groups.get(2).toggle() == null
                && !groups.get(2).collapsedByDefault()
                && groups.get(2).settings().equals(List.of(SecretClickedModule.feedbackWindowMillis,
                    SecretClickedModule.allowSecretBlocksInBoss)),
                "the two switched groups and independent confirmation settings retain their layout");

            boolean inDungeon = DungeonContextTracker.inDungeon();
            check(module.isActive() == SecretClickedModule.activeInContext(enabled, inDungeon)
                && java.util.Objects.equals(module.inactiveReason(),
                    SecretClickedModule.inactiveReasonInContext(enabled, inDungeon))
                && !SecretClickedModule.activeInContext(false, true)
                && !SecretClickedModule.activeInContext(true, false)
                && SecretClickedModule.activeInContext(true, true)
                && SecretClickedModule.inactiveReasonInContext(false, false) == null
                && SecretClickedModule.inactiveReasonInContext(true, true) == null
                && "Waiting for a dungeon".equals(SecretClickedModule.inactiveReasonInContext(true, false)),
                "active state and idle reason follow module and dungeon context gates");
            check(module.mayShowBox(false, false) == enabled
                && module.mayShowBox(true, false) == enabled
                && !module.mayShowBox(false, true) && !module.mayShowBox(true, true)
                && module.mayPlayChime(false) == enabled && !module.mayPlayChime(true)
                && !SecretClickedModule.allowsBoxFeedback(false, true, true, true, false, false)
                && !SecretClickedModule.allowsChimeFeedback(false, true, true, false),
                "disabled, normal, and boss-phase runtime gates remain intact");
            check(SecretClickedModule.allowsBoxFeedback(true, true, true, false, false, false)
                && SecretClickedModule.allowsBoxFeedback(true, true, true, false, true, false)
                && !SecretClickedModule.allowsBoxFeedback(true, true, true, false, false, true)
                && !SecretClickedModule.allowsBoxFeedback(true, true, true, false, true, true)
                && SecretClickedModule.allowsChimeFeedback(true, true, false, false)
                && !SecretClickedModule.allowsChimeFeedback(true, true, false, true),
                "default box, item, boss-phase, and chime policy remains intact");
            SecretClickedModule.itemBoxes.setValue(false);
            check(SecretClickedModule.allowsBoxFeedback(true, true, false, false, false, false)
                && !SecretClickedModule.allowsBoxFeedback(true, true, false, false, true, false)
                && module.mayShowBox(false, false) == enabled
                && module.mayShowBox(true, false) == false,
                "item box feedback obeys its independent switch");
            SecretClickedModule.boxesInBoss.setValue(true);
            check(SecretClickedModule.allowsBoxFeedback(true, true, false, true, false, true)
                && !SecretClickedModule.allowsBoxFeedback(true, true, false, true, true, true)
                && module.mayShowBox(false, true) == enabled
                && !module.mayShowBox(true, true),
                "boss-phase boxes require the boss switch and still respect item boxes");
            SecretClickedModule.boxes.setValue(false);
            check(!SecretClickedModule.allowsBoxFeedback(true, false, true, true, false, false)
                && !SecretClickedModule.allowsBoxFeedback(true, false, true, true, false, true)
                && !module.mayShowBox(false, false) && !module.mayShowBox(false, true),
                "the box group toggle gates all box feedback");
            SecretClickedModule.chimeInBoss.setValue(true);
            check(SecretClickedModule.allowsChimeFeedback(true, true, true, false)
                && SecretClickedModule.allowsChimeFeedback(true, true, true, true)
                && module.mayPlayChime(false) == enabled && module.mayPlayChime(true) == enabled,
                "boss-phase chime requires and honors its separate switch");
            SecretClickedModule.chime.setValue(false);
            check(!SecretClickedModule.allowsChimeFeedback(true, false, true, false)
                && !SecretClickedModule.allowsChimeFeedback(true, false, true, true)
                && !module.mayPlayChime(false) && !module.mayPlayChime(true),
                "the chime group toggle gates normal and boss-phase playback");
        } finally {
            for (int index = 0; index < booleans.length; index++) {
                booleans[index].setValue(previousBooleans[index]);
            }
            boxStyle.setValue(previousBoxStyle);
            for (int index = 0; index < colors.length; index++) {
                int[] color = previousColors[index];
                colors[index].set(color[0], color[1], color[2], color[3]);
            }
            for (int index = 0; index < numbers.length; index++) numbers[index].setValue(previousNumbers[index]);
            sound.setValue(previousSound);
        }
        SecretWaypointsModuleKotlinChecks.run();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("SecretClickedModuleChecks: " + message);
    }
}
