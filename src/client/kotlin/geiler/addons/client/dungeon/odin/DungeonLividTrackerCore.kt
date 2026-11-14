package geiler.addons.client.dungeon.odin

import geiler.addons.client.tree.ChatText
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/** Kotlin-owned client-observed Livid state behind its stable Java record and method API. */
internal class DungeonLividTrackerCore {
    private var current: Livid? = null
    private var matchingPlayer: String? = null
    private var matchingPlayerUuid: UUID? = null
    private var matchingPlayerEntityId = -1
    private var invulnerabilityTicks = 0

    /** Set once the real Livid died or the run ended; stops the stale target from being re-observed. */
    private var released = false

    fun observeChat(plainMessage: String?, floorFive: Boolean): Boolean {
        if (!floorFive || plainMessage == null || !START_LINE.matcher(javaTrim(plainMessage)).matches()) return false
        released = false
        invulnerabilityTicks = INVULNERABILITY_TICKS
        return true
    }

    /** Whether [releaseTarget] has run since the last boss start line or reset. */
    fun released(): Boolean = released

    /** Drops the selected target and timer for good: the real Livid is gone or the run is over. */
    fun releaseTarget() {
        released = true
        current = null
        clearMatchingPlayer()
        invulnerabilityTicks = 0
    }

    /** The client entity id of the matched Livid, or -1 when none is matched. */
    fun matchedEntityId(): Int = matchingPlayerEntityId

    /** Supply only the dye color from the client-observed wool block in the Livid room. */
    fun observeMarkerWool(dyeName: String?, inFloorFiveBoss: Boolean): Boolean {
        if (released || !inFloorFiveBoss || dyeName == null) return false
        val resolved = Livid.fromDye(dyeName) ?: return false
        if (current != resolved) clearMatchingPlayer()
        current = resolved
        return true
    }

    /** Clears target identity when the caller has authoritatively read a non-Livid marker. */
    fun clearMarker() {
        current = null
        clearMatchingPlayer()
    }

    /** Matches the exact named Livid after stripping Minecraft formatting codes. */
    fun observePlayerName(formattedName: String?, inFloorFiveBoss: Boolean): Boolean {
        val selected = current ?: return false
        if (!inFloorFiveBoss || formattedName == null) return false
        val expected = "${selected.entityName} Livid"
        if (javaTrim(stripFormatting(formattedName)) != expected) return false
        matchingPlayer = expected
        matchingPlayerUuid = null
        matchingPlayerEntityId = -1
        return true
    }

    /** Records the precise entity identity used by the shared render snapshot. */
    fun observePlayerEntity(
        entityUuid: UUID?,
        entityId: Int,
        formattedName: String?,
        inFloorFiveBoss: Boolean,
    ): Boolean {
        val selected = current ?: return false
        if (!inFloorFiveBoss || entityUuid == null || entityId < 0 || formattedName == null) return false
        val expected = "${selected.entityName} Livid"
        if (javaTrim(stripFormatting(formattedName)) != expected) return false
        matchingPlayer = expected
        matchingPlayerUuid = entityUuid
        matchingPlayerEntityId = entityId
        return true
    }

    fun tick(floorFive: Boolean) {
        if (!floorFive) return
        if (invulnerabilityTicks >= 0) invulnerabilityTicks--
    }

    fun highlightTarget(inFloorFiveBoss: Boolean, blinded: Boolean): String? =
        if (inFloorFiveBoss && !blinded) matchingPlayer else null

    fun targetEntityName(): String? = current?.let { "${it.entityName} Livid" }
    fun targetColorName(): String? = current?.dyeName
    fun targetColorCode(): Char = current?.colorCode ?: '\u0000'
    fun targetColorArgb(): Int = current?.argb ?: 0

    /** Returns a snapshot only while the marker and exact entity identity are both eligible. */
    fun selectedTarget(inFloorFiveBoss: Boolean, blinded: Boolean): DungeonLividTracker.TargetSnapshot? {
        val selected = current ?: return null
        val uuid = matchingPlayerUuid ?: return null
        val name = matchingPlayer ?: return null
        if (!inFloorFiveBoss || blinded || matchingPlayerEntityId < 0) return null
        return DungeonLividTracker.TargetSnapshot(
            uuid,
            matchingPlayerEntityId,
            name,
            selected.dyeName,
            selected.argb,
        )
    }

    fun invulnerabilityTicks(): Int = maxOf(0, invulnerabilityTicks)

    fun timerColorCode(): Char = when {
        invulnerabilityTicks > 260 -> 'a'
        invulnerabilityTicks > 130 -> 'e'
        else -> 'c'
    }

    fun timerVisible(inFloorFiveBoss: Boolean): Boolean = inFloorFiveBoss && invulnerabilityTicks > 0

    fun reset() {
        released = false
        current = null
        clearMatchingPlayer()
        invulnerabilityTicks = 0
    }

    private fun clearMatchingPlayer() {
        matchingPlayer = null
        matchingPlayerUuid = null
        matchingPlayerEntityId = -1
    }

    private enum class Livid(
        val entityName: String,
        val dyeName: String,
        val colorCode: Char,
        val argb: Int,
    ) {
        VENDETTA("Vendetta", "white", 'f', 0xFFFFFFFF.toInt()),
        CROSSED("Crossed", "magenta", 'd', 0xFFFF55FF.toInt()),
        ARCADE("Arcade", "yellow", 'e', 0xFFFFFF55.toInt()),
        SMILE("Smile", "lime", 'a', 0xFF55FF55.toInt()),
        DOCTOR("Doctor", "gray", '7', 0xFFAAAAAA.toInt()),
        PURPLE("Purple", "purple", '5', 0xFFAA00AA.toInt()),
        SCREAM("Scream", "blue", '9', 0xFF5555FF.toInt()),
        FROG("Frog", "green", '2', 0xFF00AA00.toInt()),
        HOCKEY("Hockey", "red", 'c', 0xFFFF5555.toInt());

        companion object {
            fun fromDye(dyeName: String): Livid? {
                val normalized = dyeName.trim { it <= '\u0020' }
                    .lowercase(Locale.ROOT)
                    .replace("minecraft:", "")
                    .replace("_wool", "")
                return values().firstOrNull { it.dyeName == normalized }
            }
        }
    }

    companion object {
        private const val INVULNERABILITY_TICKS = 340
        private val START_LINE = Pattern.compile(
            "^\\[BOSS] Livid: Welcome, you've arrived right on time\\. I am Livid, the Master of Shadows\\.$",
        )

        /** Returns whether a stripped name exactly matches one of the nine named Livid players. */
        @JvmStatic
        fun isLividEntityName(formattedName: String?): Boolean {
            if (formattedName == null) return false
            val plainName = javaTrim(stripFormatting(formattedName))
            return Livid.values().any { "${it.entityName} Livid" == plainName }
        }

        private fun stripFormatting(value: String): String = ChatText.stripFormattingCodes(value)

        /** Matches Java String.trim's <= U+0020 rule rather than Kotlin's broader whitespace set. */
        private fun javaTrim(value: String): String = value.trim { it <= '\u0020' }
    }
}
