package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.update.ProjectLinks
import geiler.addons.client.utility.TimerCompletionPresentation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmLinkScreen

/** Mod-wide switches that belong to no single overlay. */
class GeneralModule private constructor(settings: Settings) : Module(
    "General",
    "Mod-wide settings for macros, island detection, updates, and the utility timer.",
    Category.MISCELLANEOUS,
    settings.chatTriggersInTextScreens,
    settings.checkForUpdates,
    settings.autoUpdate,
    settings.islandDetectionApi,
    settings.availabilityNotices,
    settings.supplyMessages,
    settings.showTimerHud,
    settings.timerCompletionAlert,
    settings.timerCompletionChat,
    settings.timerCompletionTitle,
    settings.timerCompletionSound,
    settings.timerCompletionText,
    settings.timerTitleDurationTicks,
    settings.customWindowIcon,
    settings.openDiscord,
) {
    /** Chat feedback for /ga ep and /ga sb; the withdrawal itself is unaffected. */
    fun supplyMessagesEnabled(): Boolean = supplyMessagesSetting.value()

    companion object {
        @JvmField
        val INSTANCE = GeneralModule(Settings())
    }

    private val chatTriggersInTextScreensSetting = settings.chatTriggersInTextScreens
    private val checkForUpdatesSetting = settings.checkForUpdates
    private val autoUpdateSetting = settings.autoUpdate
    private val islandDetectionApiSetting = settings.islandDetectionApi
    private val availabilityNoticesSetting = settings.availabilityNotices
    private val supplyMessagesSetting = settings.supplyMessages
    private val showTimerHudSetting = settings.showTimerHud
    private val timerCompletionAlertSetting = settings.timerCompletionAlert
    private val timerCompletionChatSetting = settings.timerCompletionChat
    private val timerCompletionTitleSetting = settings.timerCompletionTitle
    private val timerCompletionSoundSetting = settings.timerCompletionSound
    private val timerCompletionTextSetting = settings.timerCompletionText
    private val timerTitleDurationTicksSetting = settings.timerTitleDurationTicks
    private val customWindowIconSetting = settings.customWindowIcon

    init {
        group(
            SettingGroup("Macros", settings.chatTriggersInTextScreens),
            SettingGroup("Island Detection", settings.islandDetectionApi),
            SettingGroup("Updates", settings.checkForUpdates, settings.autoUpdate),
            SettingGroup("Click GUI", settings.availabilityNotices, settings.supplyMessages),
            SettingGroup.folded(
                "Timer Settings",
                settings.showTimerHud,
                settings.timerCompletionAlert,
                settings.timerCompletionChat,
                settings.timerCompletionTitle,
                settings.timerCompletionSound,
                settings.timerCompletionText,
                settings.timerTitleDurationTicks,
            ),
            SettingGroup("Window", settings.customWindowIcon),
            SettingGroup("Community", settings.openDiscord),
        )
    }

    /** Carrier keeps the module and its setting groups tied to the same setting instances. */
    private class Settings {
        /**
         * Off by default: a macro firing from chat while the player is typing would inject inputs
         * into whatever has the keyboard. Turning it on is the player's explicit choice.
         */
        val chatTriggersInTextScreens = BooleanSetting("Chat Triggers In Text Screens", false)
        val supplyMessages = BooleanSetting("Supply Command Messages", true)
        val checkForUpdates = BooleanSetting("Check for Updates", true)

        /** Off by default: it downloads and installs a new jar, so it only runs when the player turns it on. */
        val autoUpdate = BooleanSetting("Auto Update", false)

        /**
         * On by default. Every island-gated module depends on it, so turning it off makes those
         * modules inert; they now say so in chat rather than doing nothing in silence.
         */
        val islandDetectionApi = BooleanSetting("Island Detection API", true)
        val availabilityNotices = BooleanSetting("Show Availability Notices", true)
        val showTimerHud = BooleanSetting("Show Timer HUD", true)
        val timerCompletionAlert =
            BooleanSetting("Completion Alert", TimerCompletionPresentation.DEFAULT_MASTER_ALERT)
        val timerCompletionChat =
            BooleanSetting("Chat Alert", TimerCompletionPresentation.DEFAULT_CHAT_ENABLED)
        val timerCompletionTitle =
            BooleanSetting("Title Alert", TimerCompletionPresentation.DEFAULT_TITLE_ENABLED)
        val timerCompletionSound =
            BooleanSetting("Sound Alert", TimerCompletionPresentation.DEFAULT_SOUND_ENABLED)
        val timerCompletionText =
            TextSetting("Completion Text", TimerCompletionPresentation.DEFAULT_MESSAGE, 128)
        val customWindowIcon = BooleanSetting("Custom Window Icon", false)
        val openDiscord = ModuleAction(
            "Open Discord",
            "Open the official GeilerAddons Discord server.",
            Runnable { Minecraft.getInstance().gui.screen()?.let { ConfirmLinkScreen.confirmLinkNow(it, ProjectLinks.DISCORD_URL) } },
        )
        val timerTitleDurationTicks = NumberSetting(
            "Title Duration (ticks)",
            1f,
            600f,
            TimerCompletionPresentation.DEFAULT_TITLE_DURATION_TICKS.toFloat(),
            true,
        )
    }

    fun islandDetectionApi(): BooleanSetting = islandDetectionApiSetting

    fun chatTriggersInTextScreens(): BooleanSetting = chatTriggersInTextScreensSetting

    fun checkForUpdates(): BooleanSetting = checkForUpdatesSetting

    fun autoUpdate(): BooleanSetting = autoUpdateSetting

    fun availabilityNotices(): BooleanSetting = availabilityNoticesSetting

    fun showTimerHud(): BooleanSetting = showTimerHudSetting

    fun timerCompletionAlert(): BooleanSetting = timerCompletionAlertSetting

    fun timerCompletionChat(): BooleanSetting = timerCompletionChatSetting

    fun timerCompletionTitle(): BooleanSetting = timerCompletionTitleSetting

    fun timerCompletionSound(): BooleanSetting = timerCompletionSoundSetting

    fun timerCompletionText(): TextSetting = timerCompletionTextSetting

    fun timerTitleDurationTicks(): NumberSetting = timerTitleDurationTicksSetting

    /** Swaps the game window and taskbar icon for the GeilerAddons logo. */
    fun customWindowIcon(): BooleanSetting = customWindowIconSetting

    override fun showsToggleControl(): Boolean = false

    override fun showsKeybindControl(): Boolean = false
}
