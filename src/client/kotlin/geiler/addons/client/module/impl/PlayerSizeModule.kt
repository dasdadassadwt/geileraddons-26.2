package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.gui.PlayerSizeEditorScreen
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.presentation.PlayerPresentationProfileStore
import geiler.addons.client.presentation.PlayerPresentationProfiles
import geiler.addons.client.presentation.PlayerSizeOverrideStore
import net.minecraft.client.Minecraft
import kotlin.jvm.JvmRecord

/** Client-only three-axis model scale with local overrides and optional hosted profiles. */
class PlayerSizeModule private constructor(settings: Settings) : Module(
    "Player Size",
    "Sets local player-model width, height, and depth with local and optional hosted profiles.",
    Category.VISUAL,
    false,
    settings.resizeOwnPlayer,
    settings.ownSizeX,
    settings.ownSizeY,
    settings.ownSizeZ,
    settings.useRemoteSizes,
    settings.manageLocalProfiles,
) {
    private val resizeOwnPlayer = settings.resizeOwnPlayer
    private val ownSizeX = settings.ownSizeX
    private val ownSizeY = settings.ownSizeY
    private val ownSizeZ = settings.ownSizeZ
    private val useRemoteSizes = settings.useRemoteSizes

    private var diagnosticTick = 0L

    init {
        group(
            SettingGroup("Local Size", resizeOwnPlayer, ownSizeX, ownSizeY, ownSizeZ),
            SettingGroup("Hosted Profiles", useRemoteSizes, settings.manageLocalProfiles),
        )
    }

    override fun configName(): String = "Player Size"

    override fun isSettingVisible(setting: Setting): Boolean =
        setting !== ownSizeX && setting !== ownSizeY && setting !== ownSizeZ || resizeOwnPlayer.value()

    override fun onEnable() {
        diagnosticTick = 0
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), true)
        writeDiagnostic(status("enabled=true"))
        PlayerPresentationProfileStore.INSTANCE.refreshIfDue()
    }

    override fun onDisable() {
        writeDiagnostic(status("enabled=false"))
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), false)
    }

    fun tick() {
        // Hosted sizes of other players apply with the module off, so the profile must keep loading.
        if (useRemoteSizes.value()) PlayerPresentationProfileStore.INSTANCE.refreshIfDue()
        if (!isEnabled()) return
        diagnosticTick++
        if (diagnosticTick % DIAGNOSTIC_HEARTBEAT_TICKS == 0L) {
            writeDiagnostic(status("enabled=true"))
        }
    }

    fun sizeScale(playerName: String?): SizeScale {
        if (playerName == null) return SizeScale.UNIT

        val client = Minecraft.getInstance()
        val ownName = if (client == null || client.user == null) null else client.user.name
        val isOwn = ownName != null && ownName.equals(playerName, ignoreCase = true)
        // Module off: only other players' hosted sizes show; your own model and local overrides need the module.
        if (!isEnabled() && isOwn) return SizeScale.UNIT
        if (isEnabled() && resizeOwnPlayer.value() && isOwn) {
            return SizeScale(ownSizeX.value(), ownSizeY.value(), ownSizeZ.value())
        }

        val local = if (isEnabled()) PlayerSizeOverrideStore.INSTANCE.find(playerName) else null
        if (local != null && local.enabled) {
            return SizeScale(local.sizeX, local.sizeY, local.sizeZ)
        }
        if (!useRemoteSizes.value()) return SizeScale.UNIT

        val remote: PlayerPresentationProfiles.Entry? =
            PlayerPresentationProfileStore.INSTANCE.current().find(playerName)
        if (remote == null || !remote.hasSizeOverride()) return SizeScale.UNIT
        val sizeX = remote.sizeX() ?: return SizeScale.UNIT
        val sizeY = remote.sizeY() ?: return SizeScale.UNIT
        val sizeZ = remote.sizeZ() ?: return SizeScale.UNIT
        return SizeScale(sizeX, sizeY, sizeZ)
    }

    /** Compatibility accessor for existing uniform-scale callers. */
    fun sizeMultiplier(playerName: String?): Float = sizeScale(playerName).x

    fun useRemoteSizesSetting(): BooleanSetting = useRemoteSizes
    fun resizeOwnPlayerSetting(): BooleanSetting = resizeOwnPlayer
    fun ownSizeXSetting(): NumberSetting = ownSizeX
    fun ownSizeYSetting(): NumberSetting = ownSizeY
    fun ownSizeZSetting(): NumberSetting = ownSizeZ

    private fun status(state: String): String =
        "STATUS $state;resizeOwn=${resizeOwnPlayer.value()},ownSize=${ownSizeX.value()}x" +
            "${ownSizeY.value()}x${ownSizeZ.value()},useHosted=${useRemoteSizes.value()}"

    private fun writeDiagnostic(line: String) {
        GeilerAddonsLog.write(Category.VISUAL, name(), diagnosticTick, line.take(MAX_DIAGNOSTIC_LINE_LENGTH))
    }

    private class Settings {
        val resizeOwnPlayer = BooleanSetting("Resize Own Player", "Resize Own Player", false)
        val ownSizeX = NumberSetting("Own Size X", 0.1f, 4.0f, 1.0f)
        val ownSizeY = NumberSetting("Own Size Y", 0.1f, 4.0f, 1.0f)
        val ownSizeZ = NumberSetting("Own Size Z", 0.1f, 4.0f, 1.0f)
        val useRemoteSizes = BooleanSetting("Use Hosted Sizes", "Use Hosted Sizes", true)
        val manageLocalProfiles = ModuleAction(
            "Manage Local Profiles",
            "Edit per-player X, Y, and Z model-size overrides.",
            Runnable { openEditor() },
        )
    }

    /** Retains the Java record ABI consumed by the PlayerAvatar Mixin. */
    @JvmRecord
    data class SizeScale(val x: Float, val y: Float, val z: Float) {
        fun isUnit(): Boolean = x == 1.0f && y == 1.0f && z == 1.0f

        companion object {
            @JvmField
            val UNIT = SizeScale(1.0f, 1.0f, 1.0f)
        }
    }

    companion object {
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 256

        @JvmField
        val INSTANCE = PlayerSizeModule(Settings())

        private fun openEditor() {
            val client = Minecraft.getInstance()
            if (client != null) client.gui.setScreen(PlayerSizeEditorScreen(client.gui.screen()))
        }
    }
}
