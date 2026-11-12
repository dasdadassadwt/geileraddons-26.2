package geiler.addons.client.module.impl

import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import net.minecraft.world.entity.Entity
import java.util.Collections
import java.util.EnumMap

/**
 * One user-created mob highlight: what to match, how to draw it, and how often to look.
 *
 * Owns real [Setting] objects rather than plain fields so the settings panel can render it with
 * the rows it already has. They are deliberately not in the module's flat setting lists: those
 * are keyed by name for the config file, and every highlight would collide on "Match Text".
 * Highlights are persisted as their own section instead.
 */
class MobHighlight internal constructor(private val highlightId: Int) {
    private var storedFolderId: String? = null
    private val enabledSetting = BooleanSetting("Enabled", true)
    private val matchNameSetting = BooleanSetting("Match Name", true)
    private val matchTextSetting = TextSetting("Match Text", "", MAX_TEXT_LENGTH)
    private val outlineColorSetting = ColorSetting("Outline Color", 255, 0, 0, 255)
    private val fillColorSetting = ColorSetting("Fill Color", 255, 0, 0, 60)

    /**
     * Off by default, unlike the floor-drop boxes.
     *
     * A box wrapping a mob is fighting that mob's own model for the same pixels, and Minecraft
     * models routinely reach past the hitbox they are given - so occlusion here hides the
     * highlight behind the very thing it is pointing at. Seeing the mob through terrain is the
     * point.
     *
     * The depth check is an independent user choice, so a player can keep it enabled for visible
     * targets or turn it off to draw through terrain.
     */
    private val depthCheckSetting = BooleanSetting("Depth Check", true)
    private val scanIntervalSetting = NumberSetting("Scan Interval", 1f, 200f, 20f, true)
    private val displayNameSetting = TextSetting("Display Name", DEFAULT_DISPLAY_NAME, MAX_TEXT_LENGTH)
    private val deleteAction = ModuleAction("Delete Highlight", Runnable {
        MobHighlightModule.INSTANCE.remove(this)
    })

    /** One switch per island, in enum order so the sections stay grouped as SkyBlock groups them. */
    private val islandSettings = EnumMap<Island, BooleanSetting>(Island::class.java).apply {
        for (island in Island.values()) {
            if (island.selectable()) put(island, BooleanSetting(island.label(), false))
        }
    }

    /** Whatever the last scan matched; rebuilt wholesale rather than diffed. */
    private val matchedEntities = ArrayList<Entity>()
    private var ticksSinceScan = 0

    fun id(): Int = highlightId

    fun folderId(): String? = storedFolderId

    fun setFolderId(value: String?) {
        storedFolderId = if (value.isNullOrBlank()) null else value
    }

    fun enabled(): BooleanSetting = enabledSetting

    fun matchName(): BooleanSetting = matchNameSetting

    fun matchText(): TextSetting = matchTextSetting

    fun outlineColor(): ColorSetting = outlineColorSetting

    fun fillColor(): ColorSetting = fillColorSetting

    fun depthCheck(): BooleanSetting = depthCheckSetting

    fun scanInterval(): NumberSetting = scanIntervalSetting

    fun displayName(): TextSetting = displayNameSetting

    fun islands(): Map<Island, BooleanSetting> = Collections.unmodifiableMap(islandSettings)

    /**
     * Whether this highlight is wanted on the island the player is currently on.
     *
     * Islands start switched off, so a new highlight does nothing until it is told where it
     * belongs. The filter also fails closed until the shared API has supplied a location: an
     * unknown or non-SkyBlock connection must not accidentally activate an island-specific
     * highlight while a join or location transition is still settling.
     */
    fun appliesOn(current: Island?): Boolean {
        if (!HypixelModApi.hasLocation() || !isKnownSkyBlockIsland(current)) return false
        val setting = islandSettings[current] ?: return false
        return setting.value()
    }

    /** The panel section for this highlight, titled by its display name. */
    fun group(): SettingGroup {
        val name = displayNameSetting.value().ifBlank { DEFAULT_DISPLAY_NAME }
        return SettingGroup.switched(
            "#$highlightId $name",
            enabledSetting,
            matchNameSetting,
            matchTextSetting,
            outlineColorSetting,
            fillColorSetting,
            depthCheckSetting,
            scanIntervalSetting,
            displayNameSetting,
            deleteAction,
        ).containing(islandGroup()).keyed("mob-highlight-entry:$highlightId")
    }

    /** The island filter is folded by default so its rows do not bury the matching controls. */
    private fun islandGroup(): SettingGroup = SettingGroup.folded(
        "#$highlightId Islands",
        *islandSettings.values.toTypedArray<Setting>(),
    ).keyed("mob-highlight-islands:$highlightId")

    fun matches(): MutableList<Entity> = matchedEntities

    /** @return true when the interval has elapsed, resetting the count */
    fun dueForScan(): Boolean {
        if (++ticksSinceScan < scanIntervalSetting.intValue()) return false
        ticksSinceScan = 0
        return true
    }

    fun setMatches(found: List<Entity>) {
        matchedEntities.clear()
        matchedEntities.addAll(found)
    }

    fun clearMatches() {
        matchedEntities.clear()
    }

    companion object {
        const val DEFAULT_DISPLAY_NAME = "New Highlight"
        private const val MAX_TEXT_LENGTH = 48

        /** Keeps the island boundary independently testable from the client-only location handshake. */
        @JvmStatic
        fun isKnownSkyBlockIsland(current: Island?): Boolean =
            current != null && current != Island.NONE && current != Island.OTHER
    }
}
