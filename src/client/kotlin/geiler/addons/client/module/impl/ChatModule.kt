package geiler.addons.client.module.impl

import geiler.addons.client.chat.ChatChannelsBackend
import geiler.addons.client.chat.ChatChannelsLocalStore
import geiler.addons.client.chat.ChatNativeSizeRules
import geiler.addons.client.chat.ChatWindowLayoutRules
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.mixin.ChatComponentPresentationAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.ChatComponent
import kotlin.math.floor

/** Extends Minecraft's native chat dimensions while retaining its renderer and interaction model. */
class ChatModule private constructor(settings: Settings) : Module(
    "Chat",
    "Extend native chat dimensions, copy messages, and handle direct messages.",
    Category.MISCELLANEOUS,
    false,
    settings.extraWidth,
    settings.extraFocusedHeight,
    settings.extraUnfocusedHeight,
    settings.copyOnRightClick,
    settings.chatHelper,
    settings.chatHelperTrigger,
    settings.legacyWindowWidth,
    settings.legacyWindowHeight,
) {
    private val extraWidth = settings.extraWidth
    private val extraFocusedHeight = settings.extraFocusedHeight
    private val extraUnfocusedHeight = settings.extraUnfocusedHeight
    private val legacyWindowWidth = settings.legacyWindowWidth
    private val legacyWindowHeight = settings.legacyWindowHeight
    private val copyOnRightClick = settings.copyOnRightClick
    private val chatHelperSetting = settings.chatHelper
    private val chatHelperTriggerSetting = settings.chatHelperTrigger

    private var currentTick = 0L
    private var lastStatusTick = Long.MIN_VALUE
    private var eventTick = Long.MIN_VALUE
    private var eventsThisTick = 0
    private var suppressedEvents = 0
    private var legacyMigrationPending = false
    private var lastSizedChat: ChatComponent? = null
    private var lastWrapWidth = Int.MIN_VALUE
    private var lastFocusedHeight = Int.MIN_VALUE
    private var lastUnfocusedHeight = Int.MIN_VALUE
    private var lastChatScale = Double.NaN

    init {
        group(
            SettingGroup("Size", extraWidth, extraFocusedHeight, extraUnfocusedHeight),
            SettingGroup("Messages", copyOnRightClick),
            SettingGroup("Chat Helper", chatHelperSetting, chatHelperTriggerSetting),
        )
    }

    /** Declared in `module-settings.json`; the hidden legacy width and height entries are config-readable only. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Chat")
        val extraWidth = spec.slider("extraWidth")
        val extraFocusedHeight = spec.slider("extraFocusedHeight")
        val extraUnfocusedHeight = spec.slider("extraUnfocusedHeight")
        val copyOnRightClick = spec.toggle("copyOnRightClick")
        val chatHelper = spec.toggle("chatHelper")
        val chatHelperTrigger = spec.text("chatHelperTrigger")
        val legacyWindowWidth = spec.slider("legacyWindowWidth")
        val legacyWindowHeight = spec.slider("legacyWindowHeight")
    }

    override fun configName(): String = "Chat"

    override fun isSettingVisible(setting: Setting): Boolean =
        setting !== legacyWindowWidth && setting !== legacyWindowHeight && super.isSettingVisible(setting)

    override fun settingTooltip(setting: Setting): String? = when (setting) {
        chatHelperSetting ->
            "On by default. Handles matching direct messages locally before they reach chat history. This switch is independent of the Chat module's master switch."
        chatHelperTriggerSetting ->
            "Exact direct-message body to handle. Surrounding spaces and letter case are ignored; leave it blank to disable matching."
        else -> null
    }

    fun extraWidth(): Int = ChatNativeSizeRules.clampExtra(extraWidth.intValue())

    fun extraFocusedHeight(): Int = ChatNativeSizeRules.clampExtra(extraFocusedHeight.intValue())

    fun extraUnfocusedHeight(): Int = ChatNativeSizeRules.clampExtra(extraUnfocusedHeight.intValue())

    fun copyOnRightClick(): Boolean = copyOnRightClick.value()

    fun chatHelper(): BooleanSetting = chatHelperSetting

    fun chatHelperTrigger(): TextSetting = chatHelperTriggerSetting

    /** Defers saved-size conversion until the client options are initialized. */
    fun initializeAfterConfig() {
        legacyMigrationPending = true
    }

    private fun initializeAfterOptions() {
        if (!legacyMigrationPending) return
        val mc = Minecraft.getInstance()
        val options = mc.options
        if (options == null) return
        legacyMigrationPending = false

        val old: ChatWindowLayoutRules.Settings? = ChatChannelsLocalStore.instance().migrateLegacyHudBounds()
        val oldWidth: Int? = if (old != null && old.width != null) {
            old.width
        } else if (legacyWindowWidth.intValue() > 0) {
            legacyWindowWidth.intValue()
        } else {
            null
        }
        val oldHeight: Int? = if (old != null && old.height != null) {
            old.height
        } else if (legacyWindowHeight.intValue() > 0) {
            legacyWindowHeight.intValue()
        } else {
            null
        }
        var migrated = false
        var scale = Double.NaN
        var vanillaWidth = -1
        var vanillaFocusedHeight = -1
        var vanillaUnfocusedHeight = -1
        if (oldWidth != null || oldHeight != null) {
            scale = safeScale(options.chatScale().get())
            vanillaWidth = ChatComponent.getWidth(options.chatWidth().get())
            vanillaFocusedHeight = ChatComponent.getHeight(options.chatHeightFocused().get())
            vanillaUnfocusedHeight = ChatComponent.getHeight(options.chatHeightUnfocused().get())
            if (oldWidth != null) {
                extraWidth.setValue(
                    ChatNativeSizeRules.migrateLegacyWidthExtra(oldWidth, vanillaWidth).toFloat(),
                )
                migrated = true
            }
            if (oldHeight != null) {
                extraFocusedHeight.setValue(
                    ChatNativeSizeRules.migrateLegacyHeightExtra(
                        oldHeight,
                        vanillaFocusedHeight,
                        scale,
                    ).toFloat(),
                )
                extraUnfocusedHeight.setValue(
                    ChatNativeSizeRules.migrateLegacyHeightExtra(
                        oldHeight,
                        vanillaUnfocusedHeight,
                        scale,
                    ).toFloat(),
                )
                migrated = true
            }
        }
        if (legacyWindowWidth.intValue() != 0) {
            legacyWindowWidth.setValue(0f)
            migrated = true
        }
        if (legacyWindowHeight.intValue() != 0) {
            legacyWindowHeight.setValue(0f)
            migrated = true
        }
        if (migrated) {
            ModConfig.markDirty()
            val scaleText = if (scale.isFinite()) scale.toString() else "n/a"
            logEvent(
                "LEGACY_SIZE_MIGRATION oldWidth=${oldWidth?.toString() ?: "none"}" +
                    " oldHeight=${oldHeight?.toString() ?: "none"}" +
                    " chatScale=$scaleText" +
                    " vanillaWidth=$vanillaWidth vanillaFocusedHeight=$vanillaFocusedHeight" +
                    " vanillaUnfocusedHeight=$vanillaUnfocusedHeight" +
                    " extraWidth=${extraWidth.intValue()}" +
                    " extraFocusedHeight=${extraFocusedHeight.intValue()}" +
                    " extraUnfocusedHeight=${extraUnfocusedHeight.intValue()}",
            )
        }
    }

    /** Automatic, bounded chat diagnostics; message text and player names are intentionally omitted. */
    fun tick(tick: Long) {
        currentTick = tick
        val diagnosticsActive = isEnabled() || ChatChannelsModule.INSTANCE.isEnabled()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, name(), diagnosticsActive)
        initializeAfterOptions()
        refreshNativeChatLayout()
        if (!diagnosticsActive ||
            (lastStatusTick != Long.MIN_VALUE && tick - lastStatusTick < STATUS_INTERVAL_TICKS)
        ) {
            return
        }
        lastStatusTick = tick

        val mc = Minecraft.getInstance()
        val options = mc.options
        val scale = safeScale(options.chatScale().get())
        val baseWidth = ChatComponent.getWidth(options.chatWidth().get())
        val baseFocusedHeight = ChatComponent.getHeight(options.chatHeightFocused().get())
        val baseUnfocusedHeight = ChatComponent.getHeight(options.chatHeightUnfocused().get())
        val viewportWidth = mc.window.guiScaledWidth
        val viewportHeight = mc.window.guiScaledHeight
        val availableWidth = ChatNativeSizeRules.availableWidth(viewportWidth, scale)
        val availableHeight = maxOf(1, viewportHeight - 40)
        val effectiveWidth = if (isEnabled()) {
            ChatNativeSizeRules.effectiveWidth(baseWidth, extraWidth(), availableWidth)
        } else {
            baseWidth
        }
        val effectiveFocusedHeight = if (isEnabled()) {
            ChatNativeSizeRules.effectiveHeight(baseFocusedHeight, extraFocusedHeight(), availableHeight, scale)
        } else {
            baseFocusedHeight
        }
        val effectiveUnfocusedHeight = if (isEnabled()) {
            ChatNativeSizeRules.effectiveHeight(baseUnfocusedHeight, extraUnfocusedHeight(), availableHeight, scale)
        } else {
            baseUnfocusedHeight
        }
        val gui = mc.gui
        val focused = gui != null && gui.hud.getChat().isChatFocused()
        val channel = if (ChatChannelsModule.INSTANCE.isEnabled()) {
            ChatChannelsBackend.selectedChannel().id()
        } else {
            "all"
        }
        val allMessages = ChatChannelsBackend.messages("all").size
        GeilerAddonsLog.write(
            Category.MISCELLANEOUS,
            name(),
            tick,
            "STATUS enabled=${isEnabled()} channels=${ChatChannelsModule.INSTANCE.isEnabled()}" +
                " channel=$channel messages=$allMessages focused=$focused" +
                " chatScale=$scale viewport=${viewportWidth}x$viewportHeight" +
                " available=${availableWidth}x$availableHeight" +
                " vanillaWidth=$baseWidth extraWidth=${extraWidth.intValue()}" +
                " effectiveWidth=$effectiveWidth" +
                " vanillaFocusedHeight=$baseFocusedHeight extraFocusedHeight=${extraFocusedHeight.intValue()}" +
                " effectiveFocusedHeight=${ChatNativeSizeRules.scaledHeight(effectiveFocusedHeight, scale)}" +
                " vanillaUnfocusedHeight=$baseUnfocusedHeight" +
                " extraUnfocusedHeight=${extraUnfocusedHeight.intValue()}" +
                " effectiveUnfocusedHeight=${ChatNativeSizeRules.scaledHeight(effectiveUnfocusedHeight, scale)}" +
                " copyOnRightClick=${copyOnRightClick.value()}",
        )
    }

    fun logTabSelection(channelId: String?) {
        logEvent("TAB_SELECTED channel=${bounded(channelId, 24)} serverCommand=false")
    }

    fun logOutgoingRouting(channelId: String?, prefix: String?, command: Boolean) {
        logEvent(
            "OUTGOING ${if (command) "slashCommandPassthrough" else "messagePrefixApplied"}" +
                " channel=${bounded(channelId, 24)} prefix=${bounded(prefix, 8)}",
        )
    }

    fun logMessageCopied(channelId: String?) {
        logEvent("MESSAGE_COPIED channel=${bounded(channelId, 24)}")
    }

    private fun refreshNativeChatLayout() {
        val mc = Minecraft.getInstance()
        val gui = mc.gui ?: return
        val chat = gui.hud.getChat()
        val scale = safeScale(mc.options.chatScale().get())
        val viewportWidth = mc.window.guiScaledWidth
        val viewportHeight = mc.window.guiScaledHeight
        val availableWidth = ChatNativeSizeRules.availableWidth(viewportWidth, scale)
        val availableHeight = maxOf(1, viewportHeight - 40)
        val baseWidth = ChatComponent.getWidth(mc.options.chatWidth().get())
        val width = if (isEnabled()) {
            ChatNativeSizeRules.effectiveWidth(baseWidth, extraWidth(), availableWidth)
        } else {
            baseWidth
        }
        val wrapWidth = maxOf(1, floor(width / scale).toInt())
        val baseFocusedHeight = ChatComponent.getHeight(mc.options.chatHeightFocused().get())
        val baseUnfocusedHeight = ChatComponent.getHeight(mc.options.chatHeightUnfocused().get())
        val focusedHeight = if (isEnabled()) {
            ChatNativeSizeRules.effectiveHeight(baseFocusedHeight, extraFocusedHeight(), availableHeight, scale)
        } else {
            baseFocusedHeight
        }
        val unfocusedHeight = if (isEnabled()) {
            ChatNativeSizeRules.effectiveHeight(
                baseUnfocusedHeight,
                extraUnfocusedHeight(),
                availableHeight,
                scale,
            )
        } else {
            baseUnfocusedHeight
        }
        if (chat !== lastSizedChat || wrapWidth != lastWrapWidth ||
            focusedHeight != lastFocusedHeight || unfocusedHeight != lastUnfocusedHeight ||
            java.lang.Double.compare(scale, lastChatScale) != 0
        ) {
            val access = chat as ChatComponentPresentationAccessor
            val scroll = access.`geileraddons$scrollbarPosition`()
            val newMessageSinceScroll = access.`geileraddons$newMessageSinceScroll`()
            chat.rescaleChat()
            val maxScroll = maxOf(
                0,
                access.`geileraddons$trimmedMessages`().size - access.`geileraddons$linesPerPage`(),
            )
            access.`geileraddons$setScrollbarPosition`(scroll.coerceIn(0, maxScroll))
            access.`geileraddons$setNewMessageSinceScroll`(newMessageSinceScroll)
            lastSizedChat = chat
            lastWrapWidth = wrapWidth
            lastFocusedHeight = focusedHeight
            lastUnfocusedHeight = unfocusedHeight
            lastChatScale = scale
        }
    }

    private fun logEvent(event: String) {
        if (!isEnabled() && !ChatChannelsModule.INSTANCE.isEnabled()) return
        if (eventTick != currentTick) {
            if (suppressedEvents > 0) {
                GeilerAddonsLog.write(
                    Category.MISCELLANEOUS,
                    name(),
                    currentTick,
                    "EVENTS_SUPPRESSED count=$suppressedEvents limit=$MAX_EVENTS_PER_TICK",
                )
            }
            eventTick = currentTick
            eventsThisTick = 0
            suppressedEvents = 0
        }
        if (eventsThisTick >= MAX_EVENTS_PER_TICK) {
            suppressedEvents++
            return
        }
        eventsThisTick++
        GeilerAddonsLog.write(Category.MISCELLANEOUS, name(), currentTick, "EVENT $event")
    }

    companion object {
        private const val STATUS_INTERVAL_TICKS = 100L
        private const val MAX_EVENTS_PER_TICK = 16

        @JvmField
        val INSTANCE = ChatModule(Settings())

        private fun safeScale(scale: Double): Double =
            if (scale.isFinite() && scale > 0.0) scale else 1.0

        private fun bounded(value: String?, maxLength: Int): String {
            if (value == null) return "none"
            val normalized = value.replace('\n', ' ').replace('\r', ' ')
            return if (normalized.length <= maxLength) normalized else normalized.substring(0, maxLength)
        }
    }
}
