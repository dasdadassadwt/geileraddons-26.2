package geiler.addons.client.module.impl

import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID

/** Pure policy for keeping reviewed skin matches separate from exact NPC profile names. */
object MinibossProfileMatchPolicy {
    private val RECOGNIZED_MINIBOSS_LABELS = setOf(
        "Shadow Assassin", "Lost Adventurer", "Diamond Guy", "Angry Archaeologist", "Frozen Adventurer", "King Midas",
    )

    enum class MatchSource { EXACT_PROFILE_NAME, REVIEWED_APPEARANCE }

    enum class AutomaticCaptureEvidence {
        EXACT_PROFILE_NAME,
        UNIQUE_RECOGNIZED_NAMEPLATE,
        REVIEWED_APPEARANCE,
        MANUAL_CONFIRMATION,
    }

    /** A short-lived snapshot made from the current client and verified party roster. */
    class IdentitySnapshot(
        localPlayerId: UUID?,
        localPlayerName: String?,
        partyPlayerIds: Set<UUID>?,
        partyPlayerNames: Set<String>?,
    ) {
        private val localId = localPlayerId
        private val normalizedLocalPlayerName = normalize(localPlayerName)
        private val normalizedPartyPlayerIds = copyIds(partyPlayerIds)
        private val normalizedPartyPlayerNames = copyNames(partyPlayerNames)

        fun localPlayerId(): UUID? = localId
        fun localPlayerName(): String = normalizedLocalPlayerName
        fun partyPlayerIds(): Set<UUID> = normalizedPartyPlayerIds
        fun partyPlayerNames(): Set<String> = normalizedPartyPlayerNames

        fun identifies(playerId: UUID?, playerName: String?): Boolean {
            val normalizedName = normalize(playerName)
            return (playerId != null && (playerId == localId || normalizedPartyPlayerIds.contains(playerId)))
                || (normalizedName.isNotEmpty()
                    && (normalizedName == normalizedLocalPlayerName || normalizedPartyPlayerNames.contains(normalizedName)))
        }

        override fun equals(other: Any?): Boolean = other is IdentitySnapshot
            && localId == other.localId && normalizedLocalPlayerName == other.normalizedLocalPlayerName
            && normalizedPartyPlayerIds == other.normalizedPartyPlayerIds
            && normalizedPartyPlayerNames == other.normalizedPartyPlayerNames

        override fun hashCode(): Int = java.util.Objects.hash(
            localId, normalizedLocalPlayerName, normalizedPartyPlayerIds, normalizedPartyPlayerNames,
        )

        override fun toString(): String = "IdentitySnapshot[localPlayerId=$localId, " +
            "localPlayerName=$normalizedLocalPlayerName, partyPlayerIds=$normalizedPartyPlayerIds, " +
            "partyPlayerNames=$normalizedPartyPlayerNames]"

        companion object {
            @JvmStatic
            fun empty(): IdentitySnapshot = IdentitySnapshot(null, "", emptySet(), emptySet())
        }
    }

    @JvmStatic
    fun allows(
        source: MatchSource?, profileId: UUID?, profileName: String?, identities: IdentitySnapshot?,
    ): Boolean {
        if (source == MatchSource.EXACT_PROFILE_NAME) return true
        return source == MatchSource.REVIEWED_APPEARANCE
            && (identities == null || !identities.identifies(profileId, profileName))
    }

    /** Equipment signatures identify Shadow Assassins independently of UUID version. */
    @JvmStatic
    fun allowsShadowAssassinEquipmentFallback(
        profileId: UUID?, profileName: String?, identities: IdentitySnapshot?,
    ): Boolean = identities == null || !identities.identifies(profileId, profileName)

    /** Only an exact recognized dungeon profile is independent evidence for automatic capture. */
    @JvmStatic
    fun shouldCaptureAutomatically(source: MatchSource?): Boolean = source == MatchSource.EXACT_PROFILE_NAME

    @JvmStatic
    fun shouldCaptureAppearanceAutomatically(
        evidence: AutomaticCaptureEvidence?, label: String?, profileId: UUID?, profileName: String?,
        identities: IdentitySnapshot?, alreadyInspected: Boolean,
    ): Boolean = (evidence == AutomaticCaptureEvidence.EXACT_PROFILE_NAME
        || evidence == AutomaticCaptureEvidence.UNIQUE_RECOGNIZED_NAMEPLATE)
        && label != null && RECOGNIZED_MINIBOSS_LABELS.contains(label)
        && profileId != null && !alreadyInspected
        && allowsAutomaticCapture(profileId, profileName, identities)

    @JvmStatic
    fun allowsAutomaticCapture(profileId: UUID?, profileName: String?, identities: IdentitySnapshot?): Boolean =
        identities == null || !identities.identifies(profileId, profileName)

    /** Returns a label only when all confirmed sources agree on one recognized miniboss. */
    @JvmStatic
    fun uniqueRecognizedMinibossLabel(observedLabels: Iterable<String?>?): String? {
        if (observedLabels == null) return null
        var unique: String? = null
        for (label in observedLabels) {
            if (label == null || !RECOGNIZED_MINIBOSS_LABELS.contains(label)) return null
            if (unique != null && unique != label) return null
            unique = label
        }
        return unique
    }

    private fun copyIds(values: Set<UUID?>?): Set<UUID> {
        if (values.isNullOrEmpty()) return emptySet()
        val copy = LinkedHashSet<UUID>()
        for (value in values) if (value != null) copy.add(value)
        return java.util.Set.copyOf(copy)
    }

    private fun copyNames(values: Set<String?>?): Set<String> {
        if (values.isNullOrEmpty()) return emptySet()
        val copy = LinkedHashSet<String>()
        for (value in values) {
            val normalized = normalize(value)
            if (normalized.isNotEmpty()) copy.add(normalized)
        }
        return java.util.Set.copyOf(copy)
    }

    private fun normalize(value: String?): String = value?.trim()?.lowercase(Locale.ROOT) ?: ""
}
