package geiler.addons.client.module.impl;

import geiler.addons.client.chat.ChatNativeCopy;
import geiler.addons.client.chat.ChatNativeSizeRules;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.TextSetting;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Direct checks for Chat module identity, Java ABI, settings, hidden migration fields, and groups. */
public final class ChatModuleChecks {
    private ChatModuleChecks() { }

    public static void run() {
        ChatModule module = ChatModule.INSTANCE;
        List<NumberSetting> numbers = module.numberSettings();
        List<Setting> numberRows = numbers.stream().map(setting -> (Setting) setting).toList();

        check(module.name().equals("Chat")
            && module.configName().equals("Chat")
            && module.category() == Category.MISCELLANEOUS
            && !module.defaultEnabled(),
            "module and persisted config identities remain stable");
        try {
            var singleton = ChatModule.class.getField("INSTANCE");
            check(singleton.getType() == ChatModule.class && singleton.get(null) == module,
                "the Java-facing singleton field remains public and stable");
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("ChatModuleChecks: missing public INSTANCE field", missing);
        }
        check(module.numberSettings().stream().map(Setting::name).toList().equals(List.of(
                "Extra Width",
                "Extra Focused Height",
                "Extra Unfocused Height",
                "Window Width",
                "Window Height")),
            "numeric setting IDs and order remain stable for config loading");
        check(module.booleanSettings().size() == 2
            && module.booleanSettings().get(0).name().equals("Copy Message on Right-click")
            && module.booleanSettings().get(1).name().equals("Chat Helper"),
            "the message-copy and chat-helper setting IDs remain stable");
        check(numbers.stream().allMatch(setting -> setting.intValue() == 0
            && setting.min() == 0f
            && setting.max() == ChatNativeSizeRules.MAX_EXTRA_PIXELS),
            "native size settings and hidden migration fields retain their defaults and bounds");
        check(module.copyOnRightClick(), "right-click message copying defaults on");
        check(module.extraWidth() == 0
            && module.extraFocusedHeight() == 0
            && module.extraUnfocusedHeight() == 0,
            "native chat dimension accessors default to vanilla size");
        check(module.isSettingVisible(numbers.get(0))
            && module.isSettingVisible(numbers.get(1))
            && module.isSettingVisible(numbers.get(2))
            && !module.isSettingVisible(numbers.get(3))
            && !module.isSettingVisible(numbers.get(4)),
            "legacy width and height settings remain hidden while config-readable");
        BooleanSetting chatHelper = module.booleanSettings().get(1);
        TextSetting chatHelperTrigger = module.textSettings().getFirst();
        check(module.groups().size() == 3
            && module.groups().get(0).name().equals("Size")
            && module.groups().get(0).settings().equals(numberRows.subList(0, 3))
            && module.groups().get(1).name().equals("Messages")
            && module.groups().get(1).settings().equals(List.of(module.booleanSettings().getFirst()))
            && module.groups().get(2).name().equals("Chat Helper")
            && module.groups().get(2).settings().equals(List.of(chatHelper, chatHelperTrigger)),
            "visible settings stay grouped into Size, Messages, and Chat Helper in order");
        check(chatHelper.rawValue() && "crash".equals(chatHelperTrigger.value())
            && chatHelperTrigger.maxLength() == 32,
            "chat helper defaults on with the default trigger and a bounded text field");
        nativeSettingsDriveModuleAccessors(module, numbers);

        requireMethod("initializeAfterConfig");
        requireMethod("tick", long.class);
        requireMethod("extraWidth");
        requireMethod("extraFocusedHeight");
        requireMethod("extraUnfocusedHeight");
        requireMethod("copyOnRightClick");
        requireMethod("chatHelper");
        requireMethod("chatHelperTrigger");
        requireMethod("logTabSelection", String.class);
        requireMethod("logOutgoingRouting", String.class, String.class, boolean.class);
        requireMethod("logMessageCopied", String.class);
        try {
            Method copyHook = ChatNativeCopy.class.getMethod("copyMessageAt", double.class, double.class);
            check(Modifier.isPublic(copyHook.getModifiers()) && Modifier.isStatic(copyHook.getModifiers())
                && copyHook.getReturnType() == boolean.class,
                "MouseHandler Mixin keeps the Java-visible native copy hook");
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("ChatModuleChecks: missing native copy hook entrypoint", missing);
        }
    }

    private static void nativeSettingsDriveModuleAccessors(ChatModule module, List<NumberSetting> numbers) {
        float[] previousNumbers = new float[numbers.size()];
        for (int index = 0; index < numbers.size(); index++) {
            previousNumbers[index] = numbers.get(index).value();
        }
        BooleanSetting copySetting = module.booleanSettings().getFirst();
        boolean previousCopy = copySetting.rawValue();
        boolean previousEnabled = module.isEnabled();
        try {
            module.setEnabled(false);
            numbers.get(0).setValue(128f);
            numbers.get(1).setValue(72f);
            numbers.get(2).setValue(44f);
            copySetting.setValue(false);
            check(module.extraWidth() == 128
                && module.extraFocusedHeight() == 72
                && module.extraUnfocusedHeight() == 44
                && !module.copyOnRightClick(),
                "configured native dimensions and copy toggle drive the module's public behavior");

            numbers.get(0).setValue(-1f);
            check(module.extraWidth() == 0, "native width remains clamped at its lower bound");
            numbers.get(0).setValue(ChatNativeSizeRules.MAX_EXTRA_PIXELS + 1f);
            check(module.extraWidth() == ChatNativeSizeRules.MAX_EXTRA_PIXELS,
                "native width remains clamped at its upper bound");

            module.setEnabled(true);
            check(module.isEnabled(), "Chat can be enabled after its settings are applied");
            module.setEnabled(false);
            check(!module.isEnabled(), "Chat can be disabled after its settings are applied");
        } finally {
            for (int index = 0; index < numbers.size(); index++) numbers.get(index).setValue(previousNumbers[index]);
            copySetting.setValue(previousCopy);
            module.setEnabled(previousEnabled);
        }
    }

    private static void requireMethod(String name, Class<?>... parameterTypes) {
        try {
            Method method = ChatModule.class.getMethod(name, parameterTypes);
            check(method.getDeclaringClass() == ChatModule.class,
                "Java-facing method remains declared on ChatModule: " + name);
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException("ChatModuleChecks: missing Java-facing method " + name, missing);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("ChatModuleChecks: " + message);
    }
}
