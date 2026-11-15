package geiler.addons.client.module.impl;

import geiler.addons.client.chat.ChatChannel;
import geiler.addons.client.chat.ChatChannelsLocalStore;
import geiler.addons.client.chat.ChatReplyEntry;
import geiler.addons.client.collections.FolderTree;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/** Focused compatibility checks for Party Commands identity, settings, and its dynamic library. */
public final class PartyCommandsModuleChecks {
    private PartyCommandsModuleChecks() { }

    public static void run() {
        PartyCommandsModule module = PartyCommandsModule.INSTANCE;
        BooleanSetting[] booleans = module.booleanSettings().toArray(BooleanSetting[]::new);
        boolean[] previousBooleans = new boolean[booleans.length];
        for (int index = 0; index < booleans.length; index++) previousBooleans[index] = booleans[index].rawValue();
        TextSetting prefix = module.commandPrefix();
        String previousPrefix = prefix.value();
        NumberSetting delay = module.responseDelayTicks();
        float previousDelay = delay.value();

        try {
            for (BooleanSetting setting : booleans) setting.reset();
            prefix.reset();
            delay.reset();
            module.refreshReplySettings();

            check(module.name().equals("Party Commands")
                && module.configName().equals("Party Commands")
                && module.description().equals(
                    "Answers configured incoming commands and handles local party-transfer helpers.")
                && module.category() == Category.MISCELLANEOUS
                && !module.defaultEnabled()
                && module.isWorkInProgress(),
                "module identity, description, category, WIP marker, and disabled default remain stable");
            check(publicJavaEntrypointsRemainStable(module),
                "the singleton field, static chat entrypoint, and public setting getters remain Java-visible");
            check(!PartyCommandsModule.handleLocalChatCommand(null),
                "a null Java local-chat argument remains unconsumed instead of failing Kotlin interop checks");
            check(publicDiagnosticsHooksRemainStable(),
                "bounded diagnostics expose the Java queue outcome enum and client tick hooks");

            check(List.of("Reply Commands", "Party", "Guild", "Co-op", "Private", "Custom Channels",
                    "Warp (!w)", "All Invite (!allinv)", "Transfer (!pt)", "Dungeon Queue (!f1-!t5)",
                    "Boop (!boop)", "Kick (!kick)", "Kick Offline (!ko)", "Promote (!promote)",
                    "Demote (!demote)", "Invite (!inv)").equals(
                    module.booleanSettings().stream().map(Setting::name).toList())
                && Arrays.equals(booleans, new BooleanSetting[] {
                    module.chatRepliesEnabled(), module.replyPartyEnabled(), module.replyGuildEnabled(),
                    module.replyCoopEnabled(), module.replyPrivateEnabled(), module.replyCustomEnabled(),
                    PartyCommandsModule.presetToggles().get(0), PartyCommandsModule.presetToggles().get(1),
                    PartyCommandsModule.presetToggles().get(2), PartyCommandsModule.presetToggles().get(3),
                    PartyCommandsModule.presetToggles().get(4), PartyCommandsModule.presetToggles().get(5),
                    PartyCommandsModule.presetToggles().get(6), PartyCommandsModule.presetToggles().get(7),
                    PartyCommandsModule.presetToggles().get(8), PartyCommandsModule.presetToggles().get(9),
                }),
                "boolean setting IDs and declaration order remain compatible");
            check(Arrays.stream(booleans).map(BooleanSetting::rawValue).toList()
                    .equals(List.of(true, true, false, false, false, false,
                        true, true, false, true, true, false, false, false, false, true)),
                "reply command and Party switches default on; harmless presets on, transfer/kick/promote/demote off (Odin defaults)");
            check(List.of("Command Prefix").equals(module.textSettings().stream().map(Setting::name).toList())
                && prefix.value().equals("!") && prefix.maxLength() == 4,
                "command prefix setting ID, default, and length bound remain stable");
            check(List.of("Reply Delay (Ticks)").equals(module.numberSettings().stream().map(Setting::name).toList())
                && delay.min() == 0f && delay.max() == 40f && delay.value() == 4f && delay.isInteger(),
                "reply delay ID, bounds, integer mode, and default remain stable");

            List<ModuleAction> actions = module.actions();
            check(actions.stream().map(ModuleAction::name).toList()
                    .equals(List.of("How Replies Work (i)", "Save Settings Edits"))
                && "Explains triggers, Text Reply, Local Action and {args} in chat.".equals(actions.get(0).description())
                && "Save changes made to the inline reply controls.".equals(actions.get(1).description()),
                "reply editor actions retain their labels and descriptions");

            List<SettingGroup> groups = module.groups();
            check(groups.size() == 7
                && groups.subList(0, 2).stream().map(SettingGroup::name).toList()
                    .equals(List.of("Replies", "Incoming Reply Channels"))
                && groups.get(0).settings().equals(List.of(
                    module.chatRepliesEnabled(), prefix, delay, actions.get(0), actions.get(1)))
                && groups.get(1).settings().equals(List.of(
                    module.replyPartyEnabled(), module.replyGuildEnabled(), module.replyCoopEnabled(),
                    module.replyPrivateEnabled(), module.replyCustomEnabled())),
                "static setting groups retain names, order, and setting instances");
            check("Preset Commands".equals(groups.get(2).name())
                && groups.get(2).settings().equals(List.copyOf(PartyCommandsModule.presetToggles())),
                "built-in party presets are listed as visible per-command toggles before the custom library");
            equals0(List.of("Add Reply", "Blocklist", "Reply Folders"),
                groups.subList(3, 6).stream().map(SettingGroup::name).toList());
            SettingGroup library = groups.get(6);
            SettingGroup blocklist = groups.get(4);
            check("party-command-blocklist".equals(blocklist.stateKey()) && blocklist.toggle() != null
                && blocklist.settings().stream().map(geiler.addons.client.module.Setting::name).toList()
                    .equals(List.of("Player Name", "Block")),
                "the blocklist is a switched group with a Player Name field and a Block button, then one row per name");
            check("Custom Reply Library".equals(library.name())
                && "party-command-library".equals(library.stateKey())
                && library.toggle() == null
                && library.collapsedByDefault()
                && library.settings().isEmpty()
                && !library.debugOnly(),
                "the dynamic custom library remains a folded, un-toggled top-level group");

            ChatChannelsLocalStore store = ChatChannelsLocalStore.instance();
            assertLibraryMatchesStore(library, store, prefix.value());
            List<SettingGroup> repeatedGroups = module.groups();
            check(groups.get(0) == repeatedGroups.get(0) && groups.get(1) == repeatedGroups.get(1),
                "static group instances remain stable across dynamic library refreshes");
            assertSameDynamicControls(library, repeatedGroups.get(6));
            module.refreshReplySettings();
            SettingGroup refreshedLibrary = module.groups().get(6);
            assertLibraryMatchesStore(refreshedLibrary, store, prefix.value());
            assertDifferentDynamicControls(library, refreshedLibrary);

            check(module.allowsIncomingReply(ChatChannel.PARTY)
                && !module.allowsIncomingReply(ChatChannel.GUILD)
                && !module.allowsIncomingReply(ChatChannel.COOP)
                && !module.allowsIncomingReply(ChatChannel.PRIVATE)
                && !module.allowsIncomingReply(new ChatChannel("custom", "Custom", "/say", true)),
                "incoming reply channel gates preserve their defaults");
            if (module.isEnabled()) {
                module.chatRepliesEnabled().setValue(false);
                check(!PartyCommandsModule.handleLocalChatCommand(prefix.value() + "help"),
                    "the Reply Commands switch prevents local commands from consuming chat");
                module.chatRepliesEnabled().setValue(true);
            } else {
                check(!PartyCommandsModule.handleLocalChatCommand(prefix.value() + "help"),
                    "a disabled module leaves local chat commands unconsumed");
            }
            module.replyGuildEnabled().setValue(true);
            module.replyCustomEnabled().setValue(true);
            check(module.allowsIncomingReply(ChatChannel.GUILD)
                && module.allowsIncomingReply(new ChatChannel("custom", "Custom", "/say", true))
                && module.allowsIncomingReply(ChatChannel.PARTY),
                "enabling Guild and Custom channels leaves the Party route active");
        } finally {
            for (int index = 0; index < booleans.length; index++) booleans[index].setValue(previousBooleans[index]);
            prefix.setValue(previousPrefix);
            delay.setValue(previousDelay);
            module.refreshReplySettings();
        }
    }

    private static boolean publicJavaEntrypointsRemainStable(PartyCommandsModule module) {
        try {
            var instance = PartyCommandsModule.class.getField("INSTANCE");
            var handler = PartyCommandsModule.class.getMethod("handleLocalChatCommand", String.class);
            return Modifier.isPublic(instance.getModifiers())
                && Modifier.isStatic(instance.getModifiers())
                && instance.get(null) == module
                && Modifier.isPublic(handler.getModifiers())
                && Modifier.isStatic(handler.getModifiers())
                && handler.getReturnType() == boolean.class
                && PartyCommandsModule.class.getMethod("chatRepliesEnabled").getReturnType() == BooleanSetting.class
                && PartyCommandsModule.class.getMethod("replyPartyEnabled").getReturnType() == BooleanSetting.class
                && PartyCommandsModule.class.getMethod("replyGuildEnabled").getReturnType() == BooleanSetting.class
                && PartyCommandsModule.class.getMethod("replyCoopEnabled").getReturnType() == BooleanSetting.class
                && PartyCommandsModule.class.getMethod("replyPrivateEnabled").getReturnType() == BooleanSetting.class
                && PartyCommandsModule.class.getMethod("replyCustomEnabled").getReturnType() == BooleanSetting.class
                && PartyCommandsModule.class.getMethod("commandPrefix").getReturnType() == TextSetting.class
                && PartyCommandsModule.class.getMethod("responseDelayTicks").getReturnType() == NumberSetting.class;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private static boolean publicDiagnosticsHooksRemainStable() {
        try {
            var tick = PartyCommandsModule.class.getMethod("tickDiagnostics");
            var queue = PartyCommandsModule.class.getMethod(
                "recordQueueDiagnostic",
                PartyCommandsModule.QueueDiagnosticOutcome.class,
                int.class
            );
            return Modifier.isPublic(tick.getModifiers())
                && tick.getReturnType() == void.class
                && Modifier.isPublic(queue.getModifiers())
                && queue.getReturnType() == void.class
                && Arrays.stream(PartyCommandsModule.QueueDiagnosticOutcome.values())
                    .map(Enum::name)
                    .toList()
                    .equals(List.of("OFFERED", "OFFER_REJECTED", "READY", "COMMAND_SUBMITTED", "CLEARED"));
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private static void assertLibraryMatchesStore(SettingGroup library, ChatChannelsLocalStore store, String prefix) {
        List<FolderTree.Folder> folders = store.replyFolders().childrenOf(null);
        List<ChatReplyEntry> rootReplies = store.chatReplies().stream()
            .filter(entry -> entry.folderId() == null || !store.replyFolders().contains(entry.folderId()))
            .toList();
        check(library.children().size() == folders.size() + rootReplies.size(),
            "the library reflects the live store's top-level folders and root replies");
        int index = 0;
        for (FolderTree.Folder folder : folders) {
            assertFolderGroup(library.children().get(index++), folder, store, prefix);
        }
        for (ChatReplyEntry entry : rootReplies) {
            assertReplyGroup(library.children().get(index++), entry, prefix);
        }
    }

    private static void assertFolderGroup(
        SettingGroup group,
        FolderTree.Folder folder,
        ChatChannelsLocalStore store,
        String prefix
    ) {
        check(folder.name().equals(group.name())
            && ("party-command-folder:" + folder.id()).equals(group.stateKey())
            && group.toggle() != null
            && "Enabled".equals(group.toggle().name())
            && group.toggle().rawValue() == store.replyFolderEnabled(folder.id())
            && !group.collapsedByDefault()
            && group.settings().isEmpty(),
            "folder setting identity, enabled state, and layout match the live store");

        List<FolderTree.Folder> childFolders = store.replyFolders().childrenOf(folder.id());
        List<ChatReplyEntry> replies = store.chatReplies().stream()
            .filter(entry -> folder.id().equals(entry.folderId()))
            .toList();
        check(group.children().size() == childFolders.size() + replies.size(),
            "a folder contains its direct child folders and replies in store order");
        int index = 0;
        for (FolderTree.Folder child : childFolders) {
            assertFolderGroup(group.children().get(index++), child, store, prefix);
        }
        for (ChatReplyEntry entry : replies) {
            assertReplyGroup(group.children().get(index++), entry, prefix);
        }
    }

    private static void assertReplyGroup(SettingGroup group, ChatReplyEntry entry, String prefix) {
        check((prefix + entry.command()).equals(group.name())
            && ("party-command:" + entry.id()).equals(group.stateKey())
            && group.toggle() != null
            && "Enabled".equals(group.toggle().name())
            && group.toggle().rawValue() == entry.enabled()
            && !group.collapsedByDefault()
            && group.children().isEmpty()
            && group.settings().size() == 4,
            "reply group identity and enabled state match the live store");
        List<Setting> settings = group.settings();
        check(settings.get(0) instanceof TextSetting
            && "Command".equals(settings.get(0).name())
            && entry.command().equals(((TextSetting) settings.get(0)).value())
            && settings.get(1) instanceof ChoiceSetting
            && "Mode".equals(settings.get(1).name())
            && entry.mode().name().equals(((ChoiceSetting) settings.get(1)).value())
            && ((ChoiceSetting) settings.get(1)).choices().equals(List.of("TEXT_REPLY", "LOCAL_ACTION"))
            && settings.get(2) instanceof TextSetting
            && "Reply / Local Action Value".equals(settings.get(2).name())
            && expectedPayload(entry).equals(((TextSetting) settings.get(2)).value())
            && settings.get(3) instanceof ModuleAction
            && "Delete Command".equals(settings.get(3).name()),
            "reply command, mode, payload, and delete controls preserve their stored meanings");
    }

    private static String expectedPayload(ChatReplyEntry entry) {
        return entry.mode() == ChatReplyEntry.Mode.TEXT_REPLY ? entry.response() : entry.actionTemplate();
    }

    private static void assertSameDynamicControls(SettingGroup first, SettingGroup second) {
        check(java.util.Objects.equals(first.name(), second.name())
            && java.util.Objects.equals(first.stateKey(), second.stateKey())
            && first.children().size() == second.children().size(),
            "rebuilding the library keeps each dynamic group's identity");
        if (first.toggle() != null) check(first.toggle() == second.toggle(),
            "inline dynamic switches are cached until an explicit refresh");
        for (int index = 0; index < first.children().size(); index++) {
            assertSameDynamicControls(first.children().get(index), second.children().get(index));
        }
    }

    private static void assertDifferentDynamicControls(SettingGroup first, SettingGroup refreshed) {
        check(java.util.Objects.equals(first.stateKey(), refreshed.stateKey())
            && first.children().size() == refreshed.children().size(),
            "refreshing inline settings preserves the library's dynamic structure");
        if (first.toggle() != null) check(first.toggle() != refreshed.toggle(),
            "refreshReplySettings drops cached folder and reply switch state");
        for (int index = 0; index < first.children().size(); index++) {
            assertDifferentDynamicControls(first.children().get(index), refreshed.children().get(index));
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("PartyCommandsModuleChecks: " + message);
    }

    private static void equals0(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
