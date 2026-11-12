package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonRoomTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.UUID

/** Correlates exact known dungeon miniboss profiles with their spawned player bodies. */
class DungeonMinibossProfileTracker {
    companion object {
        private const val MAX_RECOGNIZED_PROFILES = 128
        private const val MAX_PENDING_SPAWNS = 32
        private const val MAX_TRACKED_ENTITIES = 32
        private const val MAX_PENDING_PACKET_GAP = 512L

        /** Exact profile matches follow loaded entities at any distance; reviewed skins use a scan radius. */
        @JvmStatic
        fun isOutsideConfiguredRange(
            source: MinibossProfileMatchPolicy.MatchSource?,
            distanceSquared: Double,
            range: Int,
        ): Boolean {
            if (source != MinibossProfileMatchPolicy.MatchSource.REVIEWED_APPEARANCE) return false
            val boundedRange = range.coerceIn(1, 128)
            return !distanceSquared.isFinite() || distanceSquared > boundedRange.toDouble() * boundedRange
        }
    }

    private data class PendingSpawn(val entityId: Int, val packetSequence: Long)
    private data class ProfileMatch(
        val profileName: String?,
        val minibossLabel: String,
        val source: MinibossProfileMatchPolicy.MatchSource,
    )
    private data class TrackedEntity(
        val profileId: UUID,
        val profileName: String?,
        val minibossLabel: String,
        val matchSource: MinibossProfileMatchPolicy.MatchSource,
    )

    private data class ResolveDiagnostics(
        val trackedCandidates: Int,
        val accepted: Int,
        val entityMissing: Int,
        val notPlayer: Int,
        val removed: Int,
        val uuidMismatch: Int,
        val notAlive: Int,
        val outOfRange: Int,
        val footprintUnavailable: Int,
        val outsideRoom: Int,
        val identityExcluded: Int,
        val reason: String?,
    ) {
        fun summary(): String = "trackedCandidates=$trackedCandidates, accepted=$accepted" +
            ", entityMissing=$entityMissing, notPlayer=$notPlayer" +
            ", removed=$removed, uuidMismatch=$uuidMismatch" +
            ", notAlive=$notAlive, rangeRejected=$outOfRange" +
            ", noRoomFootprint=$footprintUnavailable, outsideRoom=$outsideRoom" +
            ", identityExcluded=$identityExcluded" +
            (if (reason.isNullOrBlank()) "" else ", resolveExit=$reason")

        companion object {
            fun empty(reason: String): ResolveDiagnostics =
                ResolveDiagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, reason)
        }
    }

    private val recognizedProfiles = LinkedHashMap<UUID, ProfileMatch>()
    private val pendingSpawns = LinkedHashMap<UUID, PendingSpawn>()
    private val trackedEntities = LinkedHashMap<Int, TrackedEntity>()
    private var packetSequence = 0L
    private var level: ClientLevel? = null
    private var playerInfoPackets = 0L
    private var addPlayerInfoPackets = 0L
    private var playerInfoEntries = 0L
    private var addPlayerInfoEntries = 0L
    private var exactProfileMatches = 0L
    private var reviewedAppearanceMatches = 0L
    private var appearanceIdentityExclusions = 0L
    private var unrelatedProfiles = 0L
    private var missingProfileNames = 0L
    private var playerSpawnPackets = 0L
    private var nonPlayerSpawnPackets = 0L
    private var matchedAtSpawn = 0L
    private var matchedAtProfile = 0L
    private var pendingSpawnsQueued = 0L
    private var pendingSpawnsExpired = 0L
    private var despawnedEntities = 0L
    private var lastPacketStage = "none"
    private var lastResolveDiagnostics = ResolveDiagnostics.empty("not-resolved")

    fun onPlayerInfoUpdate(addPlayerAction: Boolean, entries: Int) {
        playerInfoPackets++
        playerInfoEntries += entries.coerceAtLeast(0)
        if (!addPlayerAction) {
            lastPacketStage = "player-info-without-add-player-action"
            return
        }
        addPlayerInfoPackets++
        addPlayerInfoEntries += entries.coerceAtLeast(0)
        lastPacketStage = if (entries > 0) "add-player-entries-awaiting-profile-match" else "add-player-action-empty"
    }

    fun onPlayerInfo(profileId: UUID?, profileName: String?) {
        onPlayerInfo(
            profileId, profileName, null, MinibossAppearanceCatalog.empty(),
            MinibossProfileMatchPolicy.IdentitySnapshot.empty(),
        )
    }

    fun onPlayerInfo(
        profileId: UUID?,
        profileName: String?,
        appearance: MinibossAppearanceCapture.AppearanceIdentity?,
        catalog: MinibossAppearanceCatalog?,
        identities: MinibossProfileMatchPolicy.IdentitySnapshot?,
    ) {
        packetSequence++
        expirePendingSpawns()
        if (profileId == null) {
            missingProfileNames++
            lastPacketStage = "player-info-entry-missing-profile-id"
            return
        }
        val exactProfileName = DungeonMobEspSupport.isExactDungeonMinibossProfileName(profileName)
        val label = if (exactProfileName) profileName else catalog?.labelFor(appearance)
        if (label != null && !MinibossProfileMatchPolicy.allows(
                if (exactProfileName) MinibossProfileMatchPolicy.MatchSource.EXACT_PROFILE_NAME
                else MinibossProfileMatchPolicy.MatchSource.REVIEWED_APPEARANCE,
                profileId, profileName, identities,
            )
        ) {
            appearanceIdentityExclusions++
            recognizedProfiles.remove(profileId)
            pendingSpawns.remove(profileId)
            trackedEntities.entries.removeIf { entry -> entry.value.profileId == profileId }
            lastPacketStage = "reviewed-appearance-local-or-party-identity-excluded"
            return
        }
        if (label == null) {
            if (profileName.isNullOrBlank()) missingProfileNames++ else unrelatedProfiles++
            recognizedProfiles.remove(profileId)
            pendingSpawns.remove(profileId)
            trackedEntities.entries.removeIf { entry -> entry.value.profileId == profileId }
            lastPacketStage = if (profileName.isNullOrBlank()) "profile-name-missing" else "profile-name-mismatch"
            return
        }

        val source = if (exactProfileName) MinibossProfileMatchPolicy.MatchSource.EXACT_PROFILE_NAME
            else MinibossProfileMatchPolicy.MatchSource.REVIEWED_APPEARANCE
        if (exactProfileName) exactProfileMatches++ else reviewedAppearanceMatches++
        val match = ProfileMatch(profileName, label, source)
        recognizedProfiles[profileId] = match
        val pending = pendingSpawns.remove(profileId)
        if (pending != null) {
            rememberEntity(pending.entityId, profileId, match)
            matchedAtProfile++
            lastPacketStage = if (exactProfileName) "profile-match-associated-pending-spawn"
                else "reviewed-appearance-associated-pending-spawn"
        } else {
            lastPacketStage = if (exactProfileName) "exact-profile-match-awaiting-spawn"
                else "reviewed-appearance-match-awaiting-spawn"
        }
        trimOldest(recognizedProfiles, MAX_RECOGNIZED_PROFILES)
    }

    fun onPlayerSpawn(entityUuid: UUID?, entityId: Int, playerEntity: Boolean) {
        packetSequence++
        expirePendingSpawns()
        if (!playerEntity) {
            nonPlayerSpawnPackets++
            lastPacketStage = "add-entity-not-player"
            return
        }
        playerSpawnPackets++
        if (entityUuid == null || entityId < 0) {
            lastPacketStage = if (entityUuid == null) "player-spawn-missing-uuid" else "player-spawn-invalid-entity-id"
            return
        }
        val match = recognizedProfiles[entityUuid]
        if (match != null) {
            rememberEntity(entityId, entityUuid, match)
            matchedAtSpawn++
            lastPacketStage = "spawn-associated-existing-profile"
        } else {
            pendingSpawns[entityUuid] = PendingSpawn(entityId, packetSequence)
            pendingSpawnsQueued++
            trimOldest(pendingSpawns, MAX_PENDING_SPAWNS)
            lastPacketStage = "spawn-pending-profile-match"
        }
    }

    fun onEntityRemoved(entityId: Int) {
        despawnedEntities++
        lastPacketStage = "entity-removed"
        trackedEntities.remove(entityId)
        pendingSpawns.entries.removeIf { entry -> entry.value.entityId == entityId }
    }

    fun resolve(
        client: Minecraft?,
        range: Int,
        footprint: DungeonRoomTracker.MapRoomFootprint?,
        currentRoomOnly: Boolean,
        identities: MinibossProfileMatchPolicy.IdentitySnapshot?,
    ): List<LivingEntity> {
        if (client == null) {
            lastResolveDiagnostics = ResolveDiagnostics.empty("client-level-or-player-unavailable")
            return emptyList()
        }
        val currentLevel = client.level
        val localPlayer = client.player
        if (currentLevel == null || localPlayer == null) {
            lastResolveDiagnostics = ResolveDiagnostics.empty("client-level-or-player-unavailable")
            return emptyList()
        }
        onWorld(currentLevel)
        val result = ArrayList<LivingEntity>()
        var considered = 0
        var entityMissing = 0
        var notPlayer = 0
        var removed = 0
        var uuidMismatch = 0
        var notAlive = 0
        var outOfRange = 0
        var footprintUnavailable = 0
        var outsideRoom = 0
        var identityExcluded = 0
        val iterator = trackedEntities.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            considered++
            val resolved: Entity? = currentLevel.getEntity(entry.key)
            val tracked = entry.value
            if (resolved == null) {
                entityMissing++
                iterator.remove()
                continue
            }
            val body = resolved as? Player
            if (body == null) {
                notPlayer++
                iterator.remove()
                continue
            }
            if (body.isRemoved()) {
                removed++
                iterator.remove()
                continue
            }
            if (tracked.profileId != body.getUUID()) {
                uuidMismatch++
                iterator.remove()
                continue
            }
            if (!MinibossProfileMatchPolicy.allows(tracked.matchSource, tracked.profileId,
                    tracked.profileName, identities)
                || !MinibossProfileMatchPolicy.allows(tracked.matchSource, tracked.profileId,
                    body.getGameProfile().name(), identities)
            ) {
                identityExcluded++
                recognizedProfiles.remove(tracked.profileId)
                pendingSpawns.remove(tracked.profileId)
                iterator.remove()
                continue
            }
            if (!body.isAlive()) {
                notAlive++
                iterator.remove()
                continue
            }
            if (isOutsideConfiguredRange(tracked.matchSource, body.distanceToSqr(localPlayer), range)) {
                outOfRange++
                continue
            }
            if (!DungeonMobEspSupport.isAllowedByRoomFilter(currentRoomOnly, footprint, body.blockPosition())) {
                if (DungeonMobEspSupport.roomFilterRejection(currentRoomOnly, footprint, body.blockPosition())
                    == "confirmed-room-footprint-unavailable"
                ) {
                    footprintUnavailable++
                } else {
                    outsideRoom++
                }
                continue
            }
            result.add(body)
        }
        lastResolveDiagnostics = ResolveDiagnostics(
            considered, result.size, entityMissing, notPlayer, removed, uuidMismatch, notAlive,
            outOfRange, footprintUnavailable, outsideRoom, identityExcluded, "",
        )
        return java.util.List.copyOf(result)
    }

    fun isTrackedEntity(entityId: Int): Boolean = trackedEntities.containsKey(entityId)
    fun profileName(entityId: Int): String? = trackedEntities[entityId]?.profileName
    fun minibossLabel(entityId: Int): String? = trackedEntities[entityId]?.minibossLabel
    fun shouldCaptureKnownAppearance(entityId: Int): Boolean =
        trackedEntities[entityId]?.let { MinibossProfileMatchPolicy.shouldCaptureAutomatically(it.matchSource) } == true
    fun trackedCount(): Int = trackedEntities.size

    fun packetDiagnostics(): String = "playerInfoPackets=$playerInfoPackets, addPlayerPackets=$addPlayerInfoPackets" +
        ", entries=$playerInfoEntries/add=$addPlayerInfoEntries" +
        ", exactProfiles=$exactProfileMatches, reviewedAppearanceProfiles=$reviewedAppearanceMatches" +
        ", appearanceIdentityExclusions=$appearanceIdentityExclusions" +
        ", otherProfiles=$unrelatedProfiles, missingProfile=$missingProfileNames" +
        ", playerSpawns=$playerSpawnPackets, nonPlayerSpawns=$nonPlayerSpawnPackets" +
        ", matchedAtSpawn=$matchedAtSpawn, matchedAtProfile=$matchedAtProfile" +
        ", pendingQueued=$pendingSpawnsQueued, pendingExpired=$pendingSpawnsExpired" +
        ", despawned=$despawnedEntities, tracked=${trackedEntities.size}, pending=${pendingSpawns.size}" +
        ", last=$lastPacketStage"

    fun resolveDiagnostics(): String = lastResolveDiagnostics.summary()

    fun onWorld(currentLevel: ClientLevel?) {
        if (level === currentLevel) return
        clear()
        level = currentLevel
    }

    fun clear() {
        recognizedProfiles.clear()
        pendingSpawns.clear()
        trackedEntities.clear()
        packetSequence = 0L
        playerInfoPackets = 0L
        addPlayerInfoPackets = 0L
        playerInfoEntries = 0L
        addPlayerInfoEntries = 0L
        exactProfileMatches = 0L
        reviewedAppearanceMatches = 0L
        appearanceIdentityExclusions = 0L
        unrelatedProfiles = 0L
        missingProfileNames = 0L
        playerSpawnPackets = 0L
        nonPlayerSpawnPackets = 0L
        matchedAtSpawn = 0L
        matchedAtProfile = 0L
        pendingSpawnsQueued = 0L
        pendingSpawnsExpired = 0L
        despawnedEntities = 0L
        lastPacketStage = "none"
        lastResolveDiagnostics = ResolveDiagnostics.empty("not-resolved")
        level = null
    }

    private fun rememberEntity(entityId: Int, profileId: UUID, match: ProfileMatch) {
        // One profile UUID identifies one live NPC body. Repeated add/spawn events can arrive while
        // the client is reconciling a respawn; replace both stale IDs and duplicate IDs atomically.
        trackedEntities.entries.removeIf { entry -> entry.key == entityId || entry.value.profileId == profileId }
        trackedEntities[entityId] = TrackedEntity(profileId, match.profileName, match.minibossLabel, match.source)
        trimOldest(trackedEntities, MAX_TRACKED_ENTITIES)
    }

    private fun expirePendingSpawns() {
        val iterator = pendingSpawns.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (packetSequence - entry.value.packetSequence > MAX_PENDING_PACKET_GAP) {
                pendingSpawnsExpired++
                iterator.remove()
            }
        }
    }

    private fun <K, V> trimOldest(values: LinkedHashMap<K, V>, maximum: Int) {
        while (values.size > maximum) {
            val iterator = values.keys.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }
}
