package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import net.minecraft.network.chat.Component

/**
 * Legacy render-lookup facade retained for existing call sites.
 * New registrations and feature activation use Player Names and Player Size directly.
 */
@Deprecated("Legacy bridge; use Player Names and Player Size directly")
object PlayerPresentationModule : Module(
    "Player Presentation",
    "Legacy settings bridge for Player Names and Player Size.",
    Category.VISUAL,
    false,
) {
    override fun configName(): String = "Player Presentation"

    override fun showsToggleControl(): Boolean = false

    override fun showsKeybindControl(): Boolean = false

    /** Compatibility tick entry point; it does not change either feature module's activation state. */
    fun tick() {
        PlayerNamesModule.INSTANCE.tick()
        PlayerSizeModule.INSTANCE.tick()
    }

    fun replaceRenderedName(playerName: String?, renderedName: Component?): Component? =
        PlayerNamesModule.INSTANCE.replaceRenderedName(playerName, renderedName)

    fun replaceKnownRenderedName(renderedName: Component?): Component? =
        PlayerNamesModule.INSTANCE.replaceKnownRenderedName(renderedName)

    fun sizeMultiplier(playerName: String?): Float =
        PlayerSizeModule.INSTANCE.sizeMultiplier(playerName)

    fun hasEnabledNameStyle(playerName: String?): Boolean =
        PlayerNamesModule.INSTANCE.hasEnabledNameStyle(playerName)

    fun chatLayoutPresentationRevision(): Long = PlayerNamesModule.INSTANCE.chatLayoutPresentationRevision()

    fun chatComponentPresentationRevision(): Long = PlayerNamesModule.INSTANCE.chatComponentPresentationRevision()

    fun chatAnimationFrameRevision(): Long = PlayerNamesModule.INSTANCE.chatAnimationFrameRevision()

    fun chatMessagePresentationRevision(message: Component?): Long =
        PlayerNamesModule.INSTANCE.chatMessagePresentationRevision(message)

    fun chatMessageHasNamePresentation(message: Component?): Boolean =
        PlayerNamesModule.INSTANCE.chatMessageHasNamePresentation(message)

    fun chatMessageHasAnimatedNameStyle(message: Component?): Boolean =
        PlayerNamesModule.INSTANCE.chatMessageHasAnimatedNameStyle(message)

    fun hasActiveNamePresentationProfiles(): Boolean =
        PlayerNamesModule.INSTANCE.hasActiveNamePresentationProfiles()

    fun effectMetadata(playerName: String?): String? =
        PlayerNamesModule.INSTANCE.effectMetadata(playerName)

    fun replaceNamesSetting(): BooleanSetting = PlayerNamesModule.INSTANCE.replaceNamesSetting()

    fun applyNameStylesSetting(): BooleanSetting = PlayerNamesModule.INSTANCE.applyNameStylesSetting()

    fun applySizesSetting(): BooleanSetting = PlayerSizeModule.INSTANCE.useRemoteSizesSetting()
}
