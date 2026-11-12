package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.tree.ChatText
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import java.util.ArrayList
import java.util.Arrays
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID
import java.util.function.IntFunction
import java.util.function.IntPredicate

/** Packet-fed starred-nameplate association using Devonian's nameplate classes and entity-ID offsets. */
class DungeonDevonianMobTracker {
    companion object {
        const val MAX_TRACKED_NAMEPLATES = 2048
        private const val LOADED_ENTITY_RECONCILE_TICKS = 5L
        private const val MAX_LOADED_ENTITIES_PER_RECONCILE = 8192
        private const val SPATIAL_SEARCH_RADIUS_SQUARED = 9.0
        private const val SPATIAL_AMBIGUITY_GAP = 0.75

        @JvmStatic
        fun resolveSpatialBodyId(label: LabelPosition?, candidates: List<BodyCandidate?>?): SpatialResolution {
            if (label == null || candidates.isNullOrEmpty()) return SpatialResolution(-1, false)
            var best: BodyCandidate? = null
            var bestDistance = Double.MAX_VALUE
            var secondDistance = Double.MAX_VALUE
            for (candidate in candidates) {
                if (candidate == null || candidate.entityId < 0 || candidate.y >= label.y) continue
                val dx = candidate.x - label.x
                val dy = candidate.y - label.y
                val dz = candidate.z - label.z
                val distance = dx * dx + dy * dy + dz * dz
                if (distance > SPATIAL_SEARCH_RADIUS_SQUARED) continue
                if (distance < bestDistance) {
                    secondDistance = bestDistance
                    bestDistance = distance
                    best = candidate
                } else if (distance < secondDistance) {
                    secondDistance = distance
                }
            }
            if (best == null) return SpatialResolution(-1, false)
            if (secondDistance != Double.MAX_VALUE && secondDistance - bestDistance < SPATIAL_AMBIGUITY_GAP) {
                return SpatialResolution(-1, true)
            }
            return SpatialResolution(best.entityId, false)
        }

        @JvmStatic
        fun classifyStarredNameplate(name: String?): MobClass? {
            if (!hasDevonianStarEvidence(name)) return null
            val normalized = DungeonMobEspSupport.normalizedMobName(name)
            if (normalized.contains("shadow assassin")) return MobClass.SHADOW_ASSASSIN
            if (normalized == "fel" || normalized.contains("fels")) return MobClass.FEL
            if (normalized.contains("skeleton master")) return MobClass.SKELETON_MASTER
            if (normalized.contains("withermancer")) return MobClass.WITHERMANCER
            if (containsWord(normalized, "lord")) return MobClass.LORD
            if (normalized.contains("zombie commander")) return MobClass.ZOMBIE_COMMANDER
            if (normalized.contains("super archer")) return MobClass.SUPER_ARCHER
            if (normalized.contains("adventurer")) return MobClass.ADVENTURER
            if (normalized.contains("angry archaeologist")) return MobClass.ANGRY_ARCHAEOLOGIST
            if (normalized.contains("king midas")) return MobClass.KING_MIDAS
            return MobClass.STARRED_MOB
        }

        /** Classifies exact Shadow Assassin labels independently from the separate star-marker evidence. */
        @JvmStatic
        fun classifyNameplate(name: String?): MobClass? {
            if (DungeonMobEspSupport.isShadowAssassin(name)) return MobClass.SHADOW_ASSASSIN
            return classifyStarredNameplate(name)
        }

        @JvmStatic
        fun hasDevonianStarEvidence(name: String?): Boolean {
            if (DungeonMobEspSupport.javaStringIsBlank(name)) return false
            val plain = DungeonMobEspSupport.javaStringTrim(ChatText.plain(name!!))
            return plain.indexOf('✯') >= 1
        }

        @JvmStatic
        fun bodyEntityId(nameplateEntityId: Int, mobClass: MobClass?): Int =
            if (mobClass == null) -1 else nameplateEntityId - mobClass.entityIdOffset()

        private fun containsWord(value: String?, word: String?): Boolean =
            value != null && word != null && (" $value ").contains(" $word ")
    }

    enum class MobClass(private val labelText: String, private val offset: Int) {
        SHADOW_ASSASSIN("Shadow Assassin", 1),
        FEL("Fels", 1),
        SKELETON_MASTER("Skeleton Master", 1),
        WITHERMANCER("Withermancer", 3),
        LORD("Lord", 1),
        ZOMBIE_COMMANDER("Zombie Commander", 1),
        SUPER_ARCHER("Super Archer", 1),
        ADVENTURER("Adventurer", 1),
        ANGRY_ARCHAEOLOGIST("Angry Archaeologist", 1),
        KING_MIDAS("King Midas", 1),
        STARRED_MOB("Starred Mob", 1);

        fun label(): String = labelText
        fun entityIdOffset(): Int = offset
        fun isFel(): Boolean = this == FEL
        fun isShadowAssassin(): Boolean = this == SHADOW_ASSASSIN
        fun isMinibossNameplate(): Boolean = this == SHADOW_ASSASSIN || this == ADVENTURER
            || this == ANGRY_ARCHAEOLOGIST || this == KING_MIDAS
        fun belongsToStarredGroup(): Boolean = this != FEL && this != SHADOW_ASSASSIN
    }

    enum class ResolutionSource { ENTITY_ID_OFFSET, SPATIAL_FALLBACK }

    @JvmRecord
    data class Association(
        val nameplateId: Int,
        val bodyId: Int,
        val mobClass: MobClass,
        val starred: Boolean,
        val source: ResolutionSource,
    )

    @JvmRecord
    data class LabelPosition(val x: Double, val y: Double, val z: Double)

    @JvmRecord
    data class BodyCandidate(val entityId: Int, val x: Double, val y: Double, val z: Double)

    @JvmRecord
    data class SpatialResolution(val bodyId: Int, val ambiguous: Boolean)

    private data class NameplateState(val bodyId: Int, val mobClass: MobClass?, val starred: Boolean)

    private val nameplates = LinkedHashMap<Int, NameplateState>()
    private val classCounts = LongArray(MobClass.values().size)
    private var world: Any? = null
    private var lastLoadedReconcileTick = Long.MIN_VALUE
    private var loadedEntityReconciles = 0L
    private var loadedEntitiesVisited = 0L
    private var reconciledNameplates = 0L
    private var spatialFallbacks = 0L
    private var ambiguousSpatialFallbacks = 0L
    private var pendingBodies = 0L
    private var resolvedBodies = 0L
    private var evictedNameplates = 0L
    private var duplicateBodies = 0L
    private var removedEntities = 0L
    private var staleNameplatesRemoved = 0L
    private var armorStandSpawns = 0L
    private var nameMetadataUpdates = 0L
    private var starredLabels = 0L
    private var unstarredShadowAssassinLabels = 0L
    private var labelsWithoutStar = 0L
    private var lastEvent = "none"

    fun onWorld(currentWorld: Any?) {
        if (world === currentWorld) return
        clear()
        world = currentWorld
    }

    fun onArmorStandSpawn(currentWorld: Any?, entityId: Int) {
        onWorld(currentWorld)
        if (entityId < 0) return
        nameplates.putIfAbsent(entityId, NameplateState(-1, null, false))
        armorStandSpawns++
        lastEvent = "armor-stand-spawn"
        trimOldest()
    }

    fun onNameplateMetadata(currentWorld: Any?, entityId: Int, plainName: String?) {
        onWorld(currentWorld)
        if (entityId < 0) return
        recordNameplate(entityId, plainName, true)
    }

    /** Re-reads loaded nameplates periodically so labels already present or missed by a packet hook recover. */
    fun reconcileLoadedNameplates(currentLevel: ClientLevel?) {
        onWorld(currentLevel)
        if (currentLevel == null) return
        val gameTick = currentLevel.getGameTime()
        if (lastLoadedReconcileTick != Long.MIN_VALUE && gameTick >= lastLoadedReconcileTick
            && gameTick - lastLoadedReconcileTick < LOADED_ENTITY_RECONCILE_TICKS
        ) return
        lastLoadedReconcileTick = gameTick
        loadedEntityReconciles++
        val observedNameplates = HashSet<Int>()
        var visited = 0
        for (entity in currentLevel.entitiesForRendering()) {
            visited++
            if (visited > MAX_LOADED_ENTITIES_PER_RECONCILE) break
            val stand = entity as? ArmorStand ?: continue
            val plainName = if (stand.hasCustomName()) DungeonMobEspSupport.plainName(stand) else ""
            val mobClass = classifyNameplate(plainName)
            if (mobClass != null) {
                recordNameplate(stand.getId(), plainName, false)
                observedNameplates.add(stand.getId())
            } else if (nameplates.containsKey(stand.getId())) {
                recordNameplate(stand.getId(), plainName, false)
            }
        }
        loadedEntitiesVisited += visited
        reconciledNameplates += observedNameplates.size
        val iterator = nameplates.keys.iterator()
        while (iterator.hasNext()) {
            if (observedNameplates.contains(iterator.next())) continue
            iterator.remove()
            staleNameplatesRemoved++
        }
        lastEvent = if (visited > MAX_LOADED_ENTITIES_PER_RECONCILE) {
            "loaded-nameplate-reconcile-capped"
        } else {
            "loaded-nameplates-reconciled"
        }
    }

    /** Returns resolved target IDs; unresolved associations remain queued until the body entity loads. */
    fun resolveLoadedBodies(isEligibleLivingBody: IntPredicate?): List<Association> =
        resolveLoadedBodies(isEligibleLivingBody, IntFunction { null }, emptyList())

    /** Uses Devonian's entity-ID offset first and only falls back to a unique body below that label. */
    fun resolveLoadedBodies(
        isEligibleLivingBody: IntPredicate?,
        labelPosition: IntFunction<LabelPosition?>?,
        spatialBodies: List<BodyCandidate?>?,
    ): List<Association> {
        if (isEligibleLivingBody == null || nameplates.isEmpty()) return emptyList()
        val resolved = ArrayList<Association>()
        val seenBodies = LinkedHashSet<Int>()
        for ((nameplateId, state) in nameplates) {
            val mobClass = state.mobClass ?: continue
            var bodyId = state.bodyId
            var source = ResolutionSource.ENTITY_ID_OFFSET
            if (bodyId < 0 || !isEligibleLivingBody.test(bodyId)) {
                val label = labelPosition?.apply(nameplateId)
                val fallback = if (label == null) SpatialResolution(-1, false)
                    else resolveSpatialBodyId(label, spatialBodies)
                if (fallback.ambiguous) ambiguousSpatialFallbacks++
                if (fallback.bodyId < 0 || !isEligibleLivingBody.test(fallback.bodyId)) {
                    pendingBodies++
                    continue
                }
                bodyId = fallback.bodyId
                source = ResolutionSource.SPATIAL_FALLBACK
                spatialFallbacks++
            }
            if (!seenBodies.add(bodyId)) {
                duplicateBodies++
                continue
            }
            resolved.add(Association(nameplateId, bodyId, mobClass, state.starred, source))
            resolvedBodies++
        }
        return java.util.List.copyOf(resolved)
    }

    fun onEntityRemoved(entityId: Int): List<Int> {
        removedEntities++
        val affectedBodies = LinkedHashSet<Int>()
        val removedNameplate = nameplates.remove(entityId)
        if (removedNameplate != null && removedNameplate.bodyId >= 0) affectedBodies.add(removedNameplate.bodyId)
        val iterator = nameplates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.bodyId == entityId) {
                affectedBodies.add(entry.value.bodyId)
                iterator.remove()
            }
        }
        lastEvent = if (affectedBodies.isEmpty()) "untracked-entity-removed" else "tracked-entity-removed"
        return java.util.List.copyOf(affectedBodies)
    }

    fun clear() {
        nameplates.clear()
        Arrays.fill(classCounts, 0L)
        lastLoadedReconcileTick = Long.MIN_VALUE
        loadedEntityReconciles = 0L
        loadedEntitiesVisited = 0L
        reconciledNameplates = 0L
        spatialFallbacks = 0L
        ambiguousSpatialFallbacks = 0L
        pendingBodies = 0L
        resolvedBodies = 0L
        evictedNameplates = 0L
        duplicateBodies = 0L
        removedEntities = 0L
        staleNameplatesRemoved = 0L
        armorStandSpawns = 0L
        nameMetadataUpdates = 0L
        starredLabels = 0L
        unstarredShadowAssassinLabels = 0L
        labelsWithoutStar = 0L
        lastEvent = "none"
        world = null
    }

    fun trackedNameplates(): Int = nameplates.size

    fun packetDiagnostics(): String {
        val classes = StringBuilder("{")
        for (mobClass in MobClass.values()) {
            if (classes.length > 1) classes.append(',')
            classes.append(mobClass.name).append('=').append(classCounts[mobClass.ordinal])
        }
        classes.append('}')
        return "armorStandSpawns=$armorStandSpawns, nameUpdates=$nameMetadataUpdates" +
            ", loadedReconciles=$loadedEntityReconciles, entitiesVisited=$loadedEntitiesVisited" +
            ", loadedLabels=$reconciledNameplates, classifiedStarLabels=$starredLabels" +
            ", unstarredShadowAssassinLabels=$unstarredShadowAssassinLabels" +
            ", classes=$classes, noStarOrClass=$labelsWithoutStar" +
            ", nameplates=${nameplates.size}, pendingBodies=$pendingBodies" +
            ", resolvedBodies=$resolvedBodies, spatialFallbacks=$spatialFallbacks" +
            ", ambiguousSpatialFallbacks=$ambiguousSpatialFallbacks" +
            ", evictedNameplates=$evictedNameplates, staleNameplatesRemoved=$staleNameplatesRemoved" +
            ", duplicateBodies=$duplicateBodies, removals=$removedEntities, last=$lastEvent"
    }

    private fun recordNameplate(entityId: Int, plainName: String?, packetUpdate: Boolean) {
        if (packetUpdate) nameMetadataUpdates++
        val mobClass = classifyNameplate(plainName)
        val starred = hasDevonianStarEvidence(plainName)
        val previous = nameplates[entityId]
        if (mobClass == null) {
            if (previous != null) nameplates.remove(entityId)
            if (packetUpdate || previous != null) {
                labelsWithoutStar++
                lastEvent = "nameplate-without-star-or-known-label"
            }
            return
        }
        val next = NameplateState(bodyEntityId(entityId, mobClass), mobClass, starred)
        if (!packetUpdate && next == previous) return
        nameplates[entityId] = next
        if (starred) starredLabels++ else if (mobClass.isShadowAssassin()) unstarredShadowAssassinLabels++
        classCounts[mobClass.ordinal]++
        lastEvent = if (packetUpdate) {
            if (starred) "starred-nameplate-classified" else "unstarred-shadow-assassin-nameplate-classified"
        } else {
            if (starred) "loaded-starred-nameplate-reconciled" else "loaded-unstarred-shadow-assassin-reconciled"
        }
        trimOldest()
    }

    private fun trimOldest() {
        while (nameplates.size > MAX_TRACKED_NAMEPLATES) {
            val oldest = nameplates.keys.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
            evictedNameplates++
        }
    }
}
