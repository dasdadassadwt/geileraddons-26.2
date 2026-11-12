package geiler.addons.client.config

import java.nio.file.Path
import java.util.EnumSet
import java.util.function.Consumer

/** Stable Kotlin API for config transfer and selected reset operations. */
class ConfigTransferService private constructor() {
    enum class ResetArea(private val description: String) {
        MODULES("Module settings, enable switches, keybinds, Click GUI and theme"),
        MACROS("Macros, reusable functions, variables and folders"),
        MOB_ESP("Dungeon Mob, Boss and Class ESP settings, Mob Highlight rules and Block ESP entries"),
        DUNGEON_GUIDES("Dungeon Guide routes, route settings and custom segments"),
        INVENTORY_BUTTONS("Inventory Button layout and custom PNG icons"),
        TIKI_DATA("Tiki coordinates and learned block fingerprints"),
        HUD_AND_TREE("HUD positions and Tree Tracker totals"),
        SCOREBOARD("Scoreboard layout (local fonts are preserved)");

        fun label(): String = description
    }

    class ResetSelection(areas: Set<ResetArea>?) {
        private val selectedAreas: Set<ResetArea> = if (areas == null) emptySet() else java.util.Set.copyOf(areas)

        fun areas(): Set<ResetArea> = selectedAreas
        fun includes(area: ResetArea?): Boolean = selectedAreas.contains(area)

        override fun equals(other: Any?): Boolean = other is ResetSelection && selectedAreas == other.selectedAreas
        override fun hashCode(): Int = selectedAreas.hashCode()
        override fun toString(): String = "ResetSelection[areas=$selectedAreas]"

        companion object {
            @JvmStatic fun all(): ResetSelection = ResetSelection(EnumSet.allOf(ResetArea::class.java))
        }
    }

    data class Result(
        val success: Boolean,
        val message: String?,
        val path: Path?,
        val recoveryPath: Path?,
    ) {
        fun success(): Boolean = success
        fun message(): String? = message
        fun path(): Path? = path
        fun recoveryPath(): Path? = recoveryPath
    }

    data class Profile(
        val path: Path,
        val name: String,
        val entries: Int,
        val bytes: Long,
        val createdAt: String,
    ) {
        fun path(): Path = path
        fun name(): String = name
        fun entries(): Int = entries
        fun bytes(): Long = bytes
        fun createdAt(): String = createdAt
    }

    data class PreviewResult(
        val success: Boolean,
        val message: String?,
        val profile: Profile?,
    ) {
        fun success(): Boolean = success
        fun message(): String? = message
        fun profile(): Profile? = profile
    }

    companion object {
        @JvmStatic fun isBusy(): Boolean = ConfigTransferServiceCore.isBusy()
        @JvmStatic fun configDirectory(): Path = ConfigTransferServiceCore.configDirectory()
        @JvmStatic fun exportDirectory(): Path = ConfigTransferServiceCore.exportDirectory()
        @JvmStatic fun openFolder(path: Path?) = ConfigTransferServiceCore.openFolder(path)
        @JvmStatic fun saveCurrent(callback: Consumer<Result>?) = ConfigTransferServiceCore.saveCurrent(callback)
        @JvmStatic fun exportProfile(requestedName: String?, callback: Consumer<Result>?) =
            ConfigTransferServiceCore.exportProfile(requestedName, callback)

        @JvmStatic fun listProfiles(callback: Consumer<List<Profile>>?) = ConfigTransferServiceCore.listProfiles(callback)
        @JvmStatic fun preview(path: Path?, callback: Consumer<PreviewResult>?) =
            ConfigTransferServiceCore.preview(path, callback)

        @JvmStatic fun importProfile(path: Path?, callback: Consumer<Result>?) =
            ConfigTransferServiceCore.importProfile(path, callback)

        @JvmStatic fun reset(selection: ResetSelection?, callback: Consumer<Result>?) =
            ConfigTransferServiceCore.reset(selection, callback)
    }
}
