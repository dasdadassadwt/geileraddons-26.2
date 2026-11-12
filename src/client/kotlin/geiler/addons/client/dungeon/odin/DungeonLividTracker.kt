package geiler.addons.client.dungeon.odin

import java.util.UUID

/** Java ABI facade for Livid's client-observed wool identity and invulnerability timer. */
class DungeonLividTracker {
    private val core = DungeonLividTrackerCore()

    fun observeChat(plainMessage: String?, floorFive: Boolean): Boolean =
        core.observeChat(plainMessage, floorFive)

    /** Supply only the dye color from the client-observed wool block in the Livid room. */
    fun observeMarkerWool(dyeName: String?, inFloorFiveBoss: Boolean): Boolean =
        core.observeMarkerWool(dyeName, inFloorFiveBoss)

    /** Clears the selected color when an authoritative marker read is not one of the known wool colors. */
    fun clearMarker() = core.clearMarker()

    /** Matches the observed player custom name emitted by the world renderer. */
    fun observePlayerName(formattedName: String?, inFloorFiveBoss: Boolean): Boolean =
        core.observePlayerName(formattedName, inFloorFiveBoss)

    /** Records the exact named entity identity used by the box and tracer render snapshot. */
    fun observePlayerEntity(
        entityUuid: UUID?,
        entityId: Int,
        formattedName: String?,
        inFloorFiveBoss: Boolean,
    ): Boolean = core.observePlayerEntity(entityUuid, entityId, formattedName, inFloorFiveBoss)

    fun tick(floorFive: Boolean) = core.tick(floorFive)

    /** Returns the exact target name only while the marker has been observed in this boss phase. */
    fun highlightTarget(inFloorFiveBoss: Boolean, blinded: Boolean): String? =
        core.highlightTarget(inFloorFiveBoss, blinded)

    fun targetEntityName(): String? = core.targetEntityName()
    fun targetColorName(): String? = core.targetColorName()
    fun targetColorCode(): Char = core.targetColorCode()
    fun targetColorArgb(): Int = core.targetColorArgb()

    /** Returns null unless the marker and a matching entity identity are both current and eligible. */
    fun selectedTarget(inFloorFiveBoss: Boolean, blinded: Boolean): TargetSnapshot? =
        core.selectedTarget(inFloorFiveBoss, blinded)

    fun invulnerabilityTicks(): Int = core.invulnerabilityTicks()
    fun timerColorCode(): Char = core.timerColorCode()
    fun timerVisible(inFloorFiveBoss: Boolean): Boolean = core.timerVisible(inFloorFiveBoss)

    fun reset() = core.reset()

    companion object {
        /** Returns whether a stripped name exactly matches one of the nine named Livid players. */
        @JvmStatic
        fun isLividEntityName(formattedName: String?): Boolean =
            DungeonLividTrackerCore.isLividEntityName(formattedName)
    }

    /** Stable native record ABI consumed by Java and Kotlin callers. */
    @JvmRecord
    data class TargetSnapshot(
        val uuid: UUID?,
        val entityId: Int,
        val entityName: String?,
        val colorName: String?,
        val colorArgb: Int,
    )
}
