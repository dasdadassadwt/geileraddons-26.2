package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.party.PartyLookupAttempts;
import geiler.addons.client.party.PartyMember;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Direct checks for Party Finder Stats registration, persisted settings, and Java interop. */
public final class PartyFinderStatsModuleChecks {
    private PartyFinderStatsModuleChecks() { }

    public static void run() {
        checkPartyLookupAttemptsJavaApi();
        PartyFinderStatsModule module = PartyFinderStatsModule.INSTANCE;
        checkSingletonAndRegistration(module);
        checkSettingsAndGroups(module);
        checkJavaApi(module);
        checkPureHelpersAndPreview(module);
    }

    private static void checkPartyLookupAttemptsJavaApi() {
        check(Modifier.isPublic(PartyLookupAttempts.class.getModifiers())
                && Modifier.isFinal(PartyLookupAttempts.class.getModifiers()),
            "PartyLookupAttempts remains a public final Java-facing class");
        try {
            Field cooldown = PartyLookupAttempts.class.getField("MANUAL_RETRY_COOLDOWN_TICKS");
            check(cooldown.getType() == long.class
                    && Modifier.isPublic(cooldown.getModifiers())
                    && Modifier.isStatic(cooldown.getModifiers())
                    && Modifier.isFinal(cooldown.getModifiers())
                    && cooldown.getLong(null) == 20L * 30L,
                "the manual retry cooldown remains a public static final Java constant");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("PartyLookupAttempts exposes its Java cooldown constant", error);
        }

        requirePartyLookupMethod("beginAutomatic", PartyLookupAttempts.BeginResult.class,
            long.class, PartyMember.class);
        requirePartyLookupMethod("beginManualRetry", PartyLookupAttempts.RetryResult.class,
            long.class, PartyMember.class, long.class);
        requirePartyLookupMethod("complete", boolean.class, PartyLookupAttempts.RequestHandle.class,
            long.class, geiler.addons.client.dungeon.DungeonStats.class, String.class);
        requirePartyLookupMethod("get", PartyLookupAttempts.AttemptView.class, long.class, PartyMember.class);
        requirePartyLookupMethod("forgetMember", boolean.class, long.class, String.class);
        requirePartyLookupMethod("markUnavailableReported", boolean.class, long.class, PartyMember.class);
        requirePartyLookupMethod("reset", void.class, long.class);

        check(java.util.Arrays.equals(PartyLookupAttempts.State.values(), new PartyLookupAttempts.State[] {
                    PartyLookupAttempts.State.PENDING,
                    PartyLookupAttempts.State.SUCCESS,
                    PartyLookupAttempts.State.UNAVAILABLE,
                })
                && java.util.Arrays.equals(PartyLookupAttempts.RetryBlockReason.values(),
                    new PartyLookupAttempts.RetryBlockReason[] {
                        PartyLookupAttempts.RetryBlockReason.NOT_TRACKED,
                        PartyLookupAttempts.RetryBlockReason.PENDING,
                        PartyLookupAttempts.RetryBlockReason.NOT_RETRYABLE,
                        PartyLookupAttempts.RetryBlockReason.COOLDOWN,
                    }),
            "lookup and retry enum values preserve their Java order");

        requireRecordComponents(PartyLookupAttempts.RequestHandle.class,
            List.of("partyGeneration", "key", "token"), long.class, String.class, long.class);
        requireRecordComponents(PartyLookupAttempts.BeginResult.class,
            List.of("handle", "attempt"), PartyLookupAttempts.RequestHandle.class,
            PartyLookupAttempts.AttemptView.class);
        requireRecordComponents(PartyLookupAttempts.RetryResult.class,
            List.of("handle", "attempt", "blockReason", "cooldownTicksRemaining"),
            PartyLookupAttempts.RequestHandle.class, PartyLookupAttempts.AttemptView.class,
            PartyLookupAttempts.RetryBlockReason.class, long.class);
        requireRecordComponents(PartyLookupAttempts.AttemptView.class,
            List.of("name", "uuid", "state", "stats", "error", "retryTicksRemaining"),
            String.class, java.util.UUID.class, PartyLookupAttempts.State.class,
            geiler.addons.client.dungeon.DungeonStats.class, String.class, long.class);
        requirePartyLookupMethod(PartyLookupAttempts.BeginResult.class, "started", boolean.class);
        requirePartyLookupMethod(PartyLookupAttempts.RetryResult.class, "started", boolean.class);

        PartyLookupAttempts.RequestHandle nullKey = new PartyLookupAttempts.RequestHandle(3L, null, 4L);
        PartyLookupAttempts.BeginResult emptyBegin = new PartyLookupAttempts.BeginResult(null, null);
        PartyLookupAttempts.RetryResult emptyRetry = new PartyLookupAttempts.RetryResult(null, null, null, 5L);
        PartyLookupAttempts.AttemptView emptyView = new PartyLookupAttempts.AttemptView(
            null, null, null, null, null, 6L);
        check(nullKey.key() == null && nullKey.partyGeneration() == 3L && nullKey.token() == 4L
                && nullKey.equals(new PartyLookupAttempts.RequestHandle(3L, null, 4L))
                && nullKey.hashCode() == new PartyLookupAttempts.RequestHandle(3L, null, 4L).hashCode()
                && nullKey.toString().equals("RequestHandle[partyGeneration=3, key=null, token=4]")
                && !emptyBegin.started() && emptyBegin.handle() == null && emptyBegin.attempt() == null
                && !emptyRetry.started() && emptyRetry.blockReason() == null
                && emptyRetry.cooldownTicksRemaining() == 5L
                && emptyView.name() == null && emptyView.uuid() == null && emptyView.state() == null
                && emptyView.stats() == null && emptyView.error() == null,
            "record constructors preserve nullable reference components and Java accessors");

        PartyLookupAttempts attempts = new PartyLookupAttempts();
        PartyLookupAttempts.BeginResult missingMember = attempts.beginAutomatic(3L, null);
        PartyLookupAttempts.RetryResult untrackedMember = attempts.beginManualRetry(3L, null, 0L);
        check(!missingMember.started() && missingMember.attempt() == null
                && !untrackedMember.started()
                && untrackedMember.blockReason() == PartyLookupAttempts.RetryBlockReason.NOT_TRACKED
                && attempts.get(3L, null) == null
                && !attempts.forgetMember(3L, null)
                && !attempts.markUnavailableReported(3L, null)
                && !attempts.complete(null, 3L, null, null)
                && !attempts.complete(nullKey, 3L, null, null),
            "lookup entry points preserve their null-input and null-key behavior");

        PartyLookupAttempts whitespaceAttempts = new PartyLookupAttempts();
        check(whitespaceAttempts.beginAutomatic(4L,
                    new PartyMember("\u00a0Alias\u00a0", null, null)).started()
                && whitespaceAttempts.beginAutomatic(4L,
                    new PartyMember("Alias", null, null)).started(),
            "name validation and normalization preserve Java String.isBlank and String.trim semantics");
    }

    private static void requirePartyLookupMethod(String name, Class<?> returnType, Class<?>... parameters) {
        requirePartyLookupMethod(PartyLookupAttempts.class, name, returnType, parameters);
    }

    private static void requirePartyLookupMethod(Class<?> owner, String name, Class<?> returnType,
        Class<?>... parameters) {
        try {
            Method method = owner.getMethod(name, parameters);
            check(method.getReturnType() == returnType
                    && Modifier.isPublic(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers()),
                "Java callers retain " + owner.getSimpleName() + "." + name);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("missing Java-facing PartyLookupAttempts method: " + name, missing);
        }
    }

    private static void requireRecordComponents(Class<?> owner, List<String> names, Class<?>... types) {
        java.lang.reflect.RecordComponent[] components = owner.getRecordComponents();
        check(owner.isRecord() && components != null && components.length == names.size()
                && components.length == types.length,
            owner.getSimpleName() + " remains a Java record with the same component count");
        for (int index = 0; index < components.length; index++) {
            check(components[index].getName().equals(names.get(index))
                    && components[index].getType() == types[index]
                    && components[index].getAccessor().getName().equals(names.get(index)),
                owner.getSimpleName() + " retains record component " + names.get(index));
        }
    }

    private static void checkSingletonAndRegistration(PartyFinderStatsModule module) {
        try {
            Field instance = PartyFinderStatsModule.class.getField("INSTANCE");
            int modifiers = instance.getModifiers();
            check(instance.getType() == PartyFinderStatsModule.class
                    && Modifier.isPublic(modifiers)
                    && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)
                    && instance.get(null) == module,
                "the Kotlin module preserves the public static final Java singleton");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("the Kotlin module exposes its Java singleton", error);
        }

        check(Modifier.isPublic(PartyFinderStatsModule.class.getModifiers())
                && Modifier.isFinal(PartyFinderStatsModule.class.getModifiers()),
            "the registered module remains public and final");
        check(module.name().equals("Party Finder Stats")
                && module.configName().equals("Party Finder Stats")
                && module.description().equals("Shows dungeon statistics for Party Finder members.")
                && module.category() == Category.F7
                && !module.defaultEnabled(),
            "module name, saved config ID, description, category, and fresh-install state are preserved");
        check(ModuleManager.modules().stream().filter(registered -> registered == module).count() == 1L,
            "the original singleton remains registered exactly once");
    }

    private static void checkSettingsAndGroups(PartyFinderStatsModule module) {
        List<BooleanSetting> settings = module.booleanSettings();
        check(settings.stream().map(Setting::name).toList().equals(List.of(
                "Compact", "Cata", "Show overflow Cata", "Class", "CA", "MP", "SA", "PB",
                "Terminator", "Hyperion", "GDrag", "Bank", "Debug")),
            "boolean setting IDs and persistence order are preserved");

        List<Boolean> originalValues = new ArrayList<>();
        for (BooleanSetting setting : settings) originalValues.add(setting.rawValue());
        try {
            for (BooleanSetting setting : settings) setting.reset();
            check(settings.stream().map(BooleanSetting::rawValue).toList().equals(List.of(
                    false, true, true, true, true, true, true, true, true, true, true, true, false)),
                "all Party Finder Stats setting defaults are preserved");
            check(settings.stream().map(BooleanSetting::isDebugOnly).toList().equals(List.of(
                    false, false, false, false, false, false, false, false, false, false, false, false, true)),
                "only the Diagnostics setting remains gated by Dev Debug");
        } finally {
            for (int index = 0; index < settings.size(); index++) {
                settings.get(index).setValue(originalValues.get(index));
            }
        }

        List<SettingGroup> groups = module.groups();
        check(groups.size() == 2
                && "Display".equals(groups.get(0).name())
                && groups.get(0).toggle() == null
                && !groups.get(0).collapsedByDefault()
                && !groups.get(0).debugOnly()
                && groups.get(0).settings().stream().map(Setting::name).toList().equals(
                    List.of("Compact", "Cata", "Show overflow Cata", "Class", "CA", "MP", "SA", "PB",
                        "Terminator", "Hyperion", "GDrag", "Bank"))
                && "Diagnostics".equals(groups.get(1).name())
                && groups.get(1).toggle() == null
                && !groups.get(1).collapsedByDefault()
                && groups.get(1).debugOnly()
                && groups.get(1).settings().stream().map(Setting::name).toList().equals(List.of("Debug")),
            "display and diagnostics groups retain their setting IDs and order");
    }

    private static void checkJavaApi(PartyFinderStatsModule module) {
        requireInstanceMethod("onChatMessage", void.class, String.class);
        requireInstanceMethod("tick", void.class);
        requireInstanceMethod("onContainerSlotClick", void.class, AbstractContainerScreen.class,
            Slot.class, int.class, ContainerInput.class);
        requireInstanceMethod("retryPlayer", void.class, String.class);
        requireInstanceMethod("showLoadedPartyStats", int.class);
        requireInstanceMethod("lookupStats", void.class, String.class);
        requireInstanceMethod("previewLines", List.class, Font.class, int.class);
        requireStaticMethod(PartyFinderStatsModule.class, "laterJoinDisposition",
            PartyFinderStatsModule.LaterJoinDisposition.class, long.class, long.class,
            boolean.class, boolean.class);
        requireStaticMethod(PartyFinderStatsModule.class, "retryCommand", String.class, String.class);
        requireStaticMethod(PartyFinderStatsModule.class, "showStatsCommand", String.class);
        requireStaticMethod(PartyFinderStatsModule.class, "apiUnavailableStatus",
            String.class, String.class, String.class);
        requireStaticMethod(PartyFinderStatsModule.StatsView.class, "preview",
            PartyFinderStatsModule.StatsView.class);
        requireStaticMethod(PartyFinderStatsModule.CardFormatter.class, "format", List.class,
            Font.class, PartyFinderStatsModule.StatsView.class,
            PartyFinderStatsModule.DisplayOptions.class, int.class, List.class);
    }

    private static void checkPureHelpersAndPreview(PartyFinderStatsModule module) {
        check(PartyFinderStatsModule.laterJoinDisposition(5L, 5L, true, false)
                == PartyFinderStatsModule.LaterJoinDisposition.DISPLAY
                && PartyFinderStatsModule.laterJoinDisposition(4L, 5L, true, false)
                    == PartyFinderStatsModule.LaterJoinDisposition.IGNORE
                && PartyFinderStatsModule.laterJoinDisposition(4L, 5L, false, true)
                    == PartyFinderStatsModule.LaterJoinDisposition.AUTO_KICK_ONLY,
            "later-join callbacks preserve their display, auto-kick-only, and ignored dispositions");
        check(PartyFinderStatsModule.retryCommand("Player_1").equals("/ga pfretry Player_1")
                && PartyFinderStatsModule.showStatsCommand().equals("/ga pfstats")
                && PartyFinderStatsModule.apiUnavailableStatus("Player_1", null)
                    .equals("Dungeon stats API unavailable: Player_1 (unavailable)")
                && PartyFinderStatsModule.apiUnavailableStatus("Player_1", "\u00a0")
                    .equals("Dungeon stats API unavailable: Player_1 (\u00a0)"),
            "retry feedback keeps its command and frozen Java blank-string semantics");
        check(PartyFinderStatsModule.kickHiddenReason(true, "Leader", "leader", true) == null,
            "a leader sees Kick for a party member even when the profile is incomplete");
        check("not_in_party".equals(PartyFinderStatsModule.kickHiddenReason(false, "Leader", "Leader", true))
                && "leader_unknown".equals(PartyFinderStatsModule.kickHiddenReason(true, null, "Leader", true))
                && "not_leader".equals(PartyFinderStatsModule.kickHiddenReason(true, "Other", "Leader", true))
                && "not_member".equals(PartyFinderStatsModule.kickHiddenReason(true, "Leader", "Leader", false)),
            "Kick stays hidden with a named reason outside a led party or for non-members");

        PartyFinderStatsModule.DisplayOptions options = new PartyFinderStatsModule.DisplayOptions(
            false, true, true, true, true, true, true, true, true, true, true, true);
        List<Component> preview = PartyFinderStatsModule.CardFormatter.format(null,
            PartyFinderStatsModule.StatsView.preview(), options, 160, List.of());
        check(!preview.isEmpty() && preview.stream().map(Component::getString)
                .anyMatch(line -> line.contains("ExamplePlayer")),
            "the Java-facing card formatter keeps its example preview available without a Font");
        check(preview.stream().map(Component::getString).anyMatch(line -> line.contains("PB 6:42")),
            "the Party Finder card displays the queued-floor S+ personal best");
        try {
            Method completionMethod = PartyFinderStatsModule.class.getDeclaredMethod("partyStatsCompletionAction");
            completionMethod.setAccessible(true);
            Component completion = (Component) completionMethod.invoke(module);
            check(completion.toFlatList().stream().anyMatch(segment ->
                    segment.getString().equals("[Show Stats]")
                        && segment.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run
                        && run.command().equals(PartyFinderStatsModule.showStatsCommand())),
                "party scan completion exposes a clickable Show Stats command");
        } catch (ReflectiveOperationException missing) {
            throw new AssertionError("Could not inspect the Party Finder completion action", missing);
        }
    }

    private static void requireInstanceMethod(String name, Class<?> returnType, Class<?>... parameters) {
        try {
            Method method = PartyFinderStatsModule.class.getMethod(name, parameters);
            check(method.getDeclaringClass() == PartyFinderStatsModule.class
                    && method.getReturnType() == returnType
                    && !Modifier.isStatic(method.getModifiers()),
                "Java caller method remains an instance method with its original signature: " + name);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("missing Java-facing Party Finder Stats method: " + name, missing);
        }
    }

    private static void requireStaticMethod(Class<?> owner, String name, Class<?> returnType,
        Class<?>... parameters) {
        try {
            Method method = owner.getMethod(name, parameters);
            check(method.getReturnType() == returnType && Modifier.isStatic(method.getModifiers()),
                "Java caller retains static method " + owner.getSimpleName() + "." + name);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("missing Java-facing Party Finder Stats method: " + name, missing);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("PartyFinderStatsModuleChecks: " + message);
    }
}
