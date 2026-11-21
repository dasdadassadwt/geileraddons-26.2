package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonRoomTracker
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import java.util.ArrayList
import java.util.Comparator
import java.util.HashMap

/** Bounded, privacy-safe entity evidence for the automatic Dungeon Mob ESP trace. */
object DungeonMobEspEntityCensus {
    const val MAX_ENTITIES = 8192
    const val MAX_ENTITY_TYPES = 24
    const val MAX_CANDIDATE_ROWS = 32
    private const val MAX_ROW_LENGTH = 160

    @JvmStatic
    fun capture(
        level: ClientLevel?,
        tick: Long,
        associations: List<DungeonStarredMobTracker.Association?>?,
        publishedTargets: List<LivingEntity?>?,
        starredIdentities: Set<LivingEntity>?,
        profiles: DungeonMinibossProfileTracker?,
        identities: MinibossProfileMatchPolicy.IdentitySnapshot?,
        minibossEnabled: Boolean,
        onlyStarred: Boolean,
        onlyRoom: Boolean,
        footprint: DungeonRoomTracker.MapRoomFootprint?,
    ): Result {
        if (level == null) return Result(tick, 0, false, "{}", 0, 0, 0, 0, 0, emptyList())

        val entityTypes = HashMap<String, Int>()
        val rows = CandidateRows()
        appendAssociationRows(level, associations, publishedTargets, identities,
            minibossEnabled, onlyStarred, onlyRoom, footprint, rows)

        var visited = 0
        var capped = false
        for (entity in level.entitiesForRendering()) {
            if (visited >= MAX_ENTITIES) {
                capped = true
                break
            }
            visited++
            val id = typeId(entity)
            entityTypes.merge(id, 1, Int::plus)
            appendEntityRows(level, entity, id, publishedTargets, starredIdentities,
                profiles, identities, minibossEnabled, onlyStarred, onlyRoom, footprint, rows)
        }

        val types = entityTypes.entries
            .sortedWith(Comparator.comparingInt<Map.Entry<String, Int>> { it.value }.reversed()
                .thenComparing { it.key })
            .take(MAX_ENTITY_TYPES)
            .joinToString(separator = ",", prefix = "{", postfix = "}") { "${it.key}=${it.value}" }
        val omittedTypes = (entityTypes.size - MAX_ENTITY_TYPES).coerceAtLeast(0)
        return Result(tick, visited, capped, types, entityTypes.size, omittedTypes,
            rows.total(), rows.shadowAssassinTotal(), rows.omitted(), rows.values())
    }

    private fun appendAssociationRows(
        level: ClientLevel,
        associations: List<DungeonStarredMobTracker.Association?>?,
        publishedTargets: List<LivingEntity?>?,
        identities: MinibossProfileMatchPolicy.IdentitySnapshot?,
        minibossEnabled: Boolean,
        onlyStarred: Boolean,
        onlyRoom: Boolean,
        footprint: DungeonRoomTracker.MapRoomFootprint?,
        rows: CandidateRows,
    ) {
        if (associations == null) return
        for (association in associations) {
            if (association == null || !association.mobClass.isShadowAssassin()) continue
            val label = level.getEntity(association.nameplateId)
            val body = level.getEntity(association.bodyId)
            val bodyLiving = body is LivingEntity
            val bodyStand = body is ArmorStand
            val distanceSquared = if (label == null || body == null) Double.NaN else label.distanceToSqr(body)
            val associationReason = if (association.source == DungeonStarredMobTracker.ResolutionSource.SPATIAL_FALLBACK) {
                "unique-spatial-fallback"
            } else {
                DungeonMobEspSupport.shadowAssassinAssociationReason(
                    if (label == null) null else DungeonMobEspSupport.plainName(label),
                    association.nameplateId, association.bodyId, bodyLiving, bodyStand, distanceSquared,
                )
            }
            val identity = if (body is Player) {
                if (identifies(identities, body)) "excluded" else "allowed"
            } else {
                "not-player"
            }
            val shadowAssassin = association.mobClass == DungeonStarredMobTracker.MobClass.SHADOW_ASSASSIN
            val starredPass = shadowAssassin || !onlyStarred || association.starred
            val onlyStarredResult = if (!onlyStarred) "off"
                else if (shadowAssassin && !association.starred) "sa-exempt"
                else if (starredPass) "pass" else "reject"
            val published = body is LivingEntity && containsIdentity(publishedTargets, body)
            val row = "nameplate#${association.nameplateId}(${typeId(label)})->body#" +
                "${association.bodyId}(${typeId(body)}),via=${association.source}" +
                ",assoc=$associationReason,star=${association.starred}" +
                ",identity=$identity,onlyStarred=$onlyStarredResult" +
                ",room=${DungeonMobEspSupport.roomFilterRejection(onlyRoom, footprint, body?.blockPosition())}" +
                ",module=${if (minibossEnabled) "on" else "off"},published=$published"
            rows.addAssociation(row)
        }
    }

    private fun appendEntityRows(
        level: ClientLevel,
        entity: Entity,
        typeId: String,
        publishedTargets: List<LivingEntity?>?,
        starredIdentities: Set<LivingEntity>?,
        profiles: DungeonMinibossProfileTracker?,
        identities: MinibossProfileMatchPolicy.IdentitySnapshot?,
        minibossEnabled: Boolean,
        onlyStarred: Boolean,
        onlyRoom: Boolean,
        footprint: DungeonRoomTracker.MapRoomFootprint?,
        rows: CandidateRows,
    ) {
        if (entity.hasCustomName()) {
            val name = DungeonMobEspSupport.plainName(entity)
            val mobClass = DungeonStarredMobTracker.classifyNameplate(name)
            val normalized = DungeonMobEspSupport.normalizedMobName(name)
            val shadowLike = normalized.contains("shadow") || normalized.contains("assassin")
            if (mobClass != null || shadowLike) {
                val label = mobClass?.label()?.replace(' ', '_') ?: "unmatched-shadow-related"
                val starred = DungeonStarredMobTracker.hasDevonianStarEvidence(name)
                var row = "entity#${entity.getId()}($typeId),label=$label,star=$starred"
                val shadowAssassin = mobClass?.isShadowAssassin() == true || shadowLike
                if (entity is ArmorStand && shadowAssassin) {
                    val bodyId = DungeonStarredMobTracker.bodyEntityId(
                        entity.getId(), DungeonStarredMobTracker.MobClass.SHADOW_ASSASSIN,
                    )
                    val body = level.getEntity(bodyId)
                    val distanceSquared = if (body == null) Double.NaN else body.distanceToSqr(entity)
                    val association = DungeonMobEspSupport.shadowAssassinAssociationReason(
                        name, entity.getId(), if (body == null) -1 else body.getId(),
                        body is LivingEntity, body is ArmorStand, distanceSquared,
                    )
                    row += ",bodyId=$bodyId(${typeId(body)}),assoc=$association"
                } else if (entity is ArmorStand && mobClass != null) {
                    row += ",bodyId=${DungeonStarredMobTracker.bodyEntityId(entity.getId(), mobClass)}"
                }
                rows.add(row, shadowAssassin, shadowAssassin)
            }
        }

        val player = entity as? Player ?: return
        val profileName = player.getGameProfile().name()
        val exactSaProfile = DungeonMobEspSupport.isShadowAssassinProfileName(profileName)
        val trackedSaProfile = profiles != null && "Shadow Assassin" == profiles.minibossLabel(player.getId())
        val saGear = DungeonMobEspSupport.hasShadowAssassinEquipment(player)
        val uuidV2 = player.getUUID() != null && player.getUUID().version() == 2
        if (!exactSaProfile && !trackedSaProfile && !saGear) {
            if (uuidV2) {
                val row = "player#${player.getId()}($typeId),source=unmatched-v2-uuid" +
                    ",identity=${if (identifies(identities, player)) "excluded" else "allowed"}" +
                    ",profileMatch=false,gearMatch=false,published=false"
                rows.add(row, false, false)
            }
            return
        }

        val identityRejected = identifies(identities, player)
        val starEvidence = starredIdentities?.contains(player) == true
        val shadowAssassin = exactSaProfile || trackedSaProfile || saGear
        val onlyStarredResult = if (!onlyStarred) "off"
            else if (shadowAssassin && !starEvidence) "sa-exempt"
            else if (starEvidence) "pass" else "reject"
        val published = containsIdentity(publishedTargets, player)
        val source = if (exactSaProfile || trackedSaProfile) "profile" else "equipment"
        val row = "player#${player.getId()},source=$source" +
            (if (saGear) "+gear" else "") + ",uuidV2=$uuidV2" +
            ",identity=${if (identityRejected) "excluded" else "allowed"}" +
            ",starEvidence=$starEvidence,onlyStarred=$onlyStarredResult" +
            ",room=${DungeonMobEspSupport.roomFilterRejection(onlyRoom, footprint, player.blockPosition())}" +
            ",module=${if (minibossEnabled) "on" else "off"},published=$published"
        rows.add(row, true, true)
    }

    private fun identifies(identities: MinibossProfileMatchPolicy.IdentitySnapshot?, player: Player): Boolean =
        identities != null && identities.identifies(player.getUUID(), player.getGameProfile().name())

    private fun containsIdentity(entities: List<LivingEntity?>?, target: LivingEntity?): Boolean {
        if (entities == null || target == null) return false
        for (entity in entities) if (entity === target) return true
        return false
    }

    private fun typeId(entity: Entity?): String {
        if (entity == null) return "missing"
        val id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
        return id?.toString() ?: "unknown"
    }

    @JvmRecord
    data class Result(
        val sampleTick: Long,
        val visited: Int,
        val capped: Boolean,
        val typeCounts: String,
        val typeCount: Int,
        val omittedTypes: Int,
        val candidateRows: Int,
        val shadowAssassinCandidates: Int,
        val omittedCandidates: Int,
        val samples: List<String>,
    ) {
        fun summary(): String = "entityCensus{tick=$sampleTick,source=entitiesForRendering,visited=$visited" +
            ",capped=$capped,typeCount=$typeCount,types=$typeCounts,omittedTypes=$omittedTypes" +
            ",candidateRows=$candidateRows,shadowAssassinRows=$shadowAssassinCandidates" +
            ",omittedCandidates=$omittedCandidates,samples=$samples}"
    }

    private class CandidateRows {
        private val associations = ArrayList<String>()
        private val priority = ArrayList<String>()
        private val other = ArrayList<String>()
        private var total = 0
        private var shadowAssassinTotal = 0

        fun addAssociation(value: String?) {
            total++
            shadowAssassinTotal++
            if (associations.size < MAX_ASSOCIATION_ROWS) associations.add(bound(value))
        }

        fun add(value: String?, highPriority: Boolean, shadowAssassin: Boolean) {
            total++
            if (shadowAssassin) shadowAssassinTotal++
            val target = if (highPriority) priority else other
            if (target.size >= MAX_CANDIDATE_ROWS) return
            target.add(bound(value))
        }

        fun total(): Int = total
        fun shadowAssassinTotal(): Int = shadowAssassinTotal

        fun values(): List<String> {
            val result = ArrayList<String>(MAX_CANDIDATE_ROWS)
            result.addAll(associations.subList(0, associations.size.coerceAtMost(MAX_CANDIDATE_ROWS)))
            for (row in priority) {
                if (result.size >= MAX_CANDIDATE_ROWS) break
                result.add(row)
            }
            for (row in other) {
                if (result.size >= MAX_CANDIDATE_ROWS) break
                result.add(row)
            }
            return java.util.List.copyOf(result)
        }

        fun omitted(): Int = (total - values().size).coerceAtLeast(0)

        private fun bound(value: String?): String {
            if (value == null) return "unknown"
            val safe = value.replace('\r', ' ').replace('\n', ' ')
            return if (safe.length <= MAX_ROW_LENGTH) safe else safe.substring(0, MAX_ROW_LENGTH - 3) + "..."
        }
    }

    private const val MAX_ASSOCIATION_ROWS = 8
}
