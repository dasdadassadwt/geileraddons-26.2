package geiler.addons.client.module.impl

import com.mojang.authlib.properties.Property
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonRoomTracker
import geiler.addons.client.entity.ClientEntitySnapshot
import geiler.addons.client.entity.Nameplates
import geiler.addons.client.macro.MacroItemCountRules
import geiler.addons.client.render.WorldToScreen
import geiler.addons.client.tree.ChatText
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.function.Predicate
import java.util.regex.Pattern

/** Shared client-thread scanning and projected 2D boxes for dungeon mob ESP modules. */
internal object DungeonMobEspSupport {
    // Skyblocker HeadTextures.FEL, verified in dc1176a HeadTextures.java.
    private const val FEL_HEAD_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTcyMDAyNTQ4Njg2MywKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMjg2ZGFjYjBmMjE0NGQ3YTQxODdiZTM2YmJhYmU4YTk4ODI4ZjdjNzlkZmY1Y2UwMTM2OGI2MzAwMTU1NjYzIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0="
    private val MINIBOSS_NAMES = listOf(
        "shadow assassin", "lost adventurer", "diamond guy", "angry archaeologist", "frozen adventurer", "king midas",
    )
    private val EARLY_MINIBOSS_PROFILE_NAMES = setOf(
        "Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas",
    )
    private val NON_LETTERS = Regex("[^A-Za-z]+")
    private val WHITESPACE_RUN = Regex("\\s+")
    private val DUNGEON_LEVEL_PREFIX = Pattern.compile("(?i)^\\[\\s*lv\\s*\\d+\\]\\s*")
    private val DUNGEON_ATTRIBUTE_PREFIX = Pattern.compile(
        "(?i)^(?:Flaming|Stormy|Speedy|Fortified|Healthy|Healing|Boomer|Golden|Stealth)\\s+",
    )
    private val DUNGEON_BRACKET_PREFIX = Pattern.compile("^\\[[\\w\\d]+\\]\\s*")
    private val DUNGEON_HEALTH_SUFFIX = Pattern.compile(
        "(?i)\\s+\\d[\\d,]*(?:\\.\\d+)?\\s*[kmbt]?(?:/\\d[\\d,]*(?:\\.\\d+)?\\s*[kmbt]?)?\\s*❤\\s*$",
    )

    @JvmStatic
    @JvmOverloads
    fun scan(
        client: Minecraft?,
        range: Int,
        currentRoomOnly: Boolean,
        nameFilter: Predicate<String>?,
        starredLabel: Boolean,
        captureNameSamples: Boolean,
        roomFootprint: DungeonRoomTracker.MapRoomFootprint? = currentConfirmedRoomFootprint(),
        bodyFilter: Predicate<LivingEntity>? = null,
        collectConfirmedMinibossNameplates: Boolean = false,
    ): ScanResult {
        if (client == null) return ScanResult.empty("dungeon context unavailable")
        val level: ClientLevel = client.level ?: return ScanResult.empty("dungeon context unavailable")
        val player: LocalPlayer = client.player ?: return ScanResult.empty("dungeon context unavailable")
        if (nameFilter == null || !DungeonContextTracker.inDungeon()) return ScanResult.empty("dungeon context unavailable")
        val boundedRange = range.coerceIn(1, 128)
        val room = if (currentRoomOnly) resolveRoomFootprint(roomFootprint) else null
        if (currentRoomOnly && room == null) return ScanResult.empty("room occupancy unavailable")
        val nearby = ClientEntitySnapshot.nearby(level, player, boundedRange.toDouble())
        val bodies = nearby.mapNotNull { entity ->
            val living = entity as? LivingEntity
            if (living != null && living !is ArmorStand) living else null
        }
        val unique: MutableSet<Entity> = Collections.newSetFromMap(IdentityHashMap<Entity, Boolean>())
        val matches = ArrayList<LivingEntity>()
        val shadowAssassins = ArrayList<LivingEntity>()
        val confirmedMinibossNameplates = ArrayList<MinibossNameplateMatch>()
        var namedCandidates = 0
        var resolvedBodies = 0
        var unresolvedBodies = 0
        var rangeRejected = 0
        var roomRejected = 0
        var shadowAssassinLabels = 0
        var shadowAssassinLabelBodies = 0
        var shadowAssassinLabelRejects = 0
        var exactProfileBodies = 0
        var exactProfileAccepted = 0
        var exactProfileRangeRejects = 0
        var exactProfileRoomRejects = 0
        var exactProfileDeadRejects = 0
        var lastLabelRejection = "no-label-found"
        val shadowAssassinProfilePathEnabled = nameFilter.test("Shadow Assassin")
        var lastProfileRejection = if (shadowAssassinProfilePathEnabled) "no-exact-profile-found" else "profile-path-disabled"
        val nameSamples = ArrayList<String>(8)

        for (nameSource in nearby) {
            if (nameSource === player || !nameSource.isAlive()) continue
            val name = plainName(nameSource)
            val displayMatch = nameFilter.test(name)
            val confirmedMinibossLabel = if (collectConfirmedMinibossNameplates) recognizedMinibossLabel(name) else null
            if (captureNameSamples && nameSamples.size < 8 && !javaStringIsBlank(name)
                && (nameSource is ArmorStand || nameSource.hasCustomName())
            ) {
                val sample = "${nameSource.javaClass.simpleName}#${nameSource.getId()}=\"$name\""
                nameSamples.add(if (sample.length <= 96) sample else sample.substring(0, 93) + "...")
            }
            if (!displayMatch && confirmedMinibossLabel == null) continue
            namedCandidates++
            val shadowAssassinName = isShadowAssassin(name)
            val shadowAssassinLabel = nameSource is ArmorStand && shadowAssassinName
            if (shadowAssassinLabel) {
                shadowAssassinLabels++
                lastLabelRejection = "awaiting-body-association"
            }
            // Hypixel uses a separate armor-stand label. Reject unrelated nameplates at room scale and
            // associate Shadow Assassins only through Devonian's named-label ID - 1 relationship.
            if (nameSource !is ArmorStand && nameSource !is LivingEntity) continue
            var body: Entity? = null
            if ((starredLabel || shadowAssassinName) && nameSource is ArmorStand) {
                // Devonian's BoxStarMob associates a named armor-stand label with the body at ID - 1;
                // Withermancer labels use -3. Shadow Assassin labels must not fall back to proximity,
                // which could attach a nearby unrelated miniboss in a crowded room.
                val offset = if (name.lowercase(Locale.ROOT).contains("withermancer")) 3 else 1
                val byProtocolOrder = level.getEntity(nameSource.getId() - offset)
                if (shadowAssassinLabel) {
                    val association = shadowAssassinAssociationReason(
                        name, nameSource.getId(), byProtocolOrder?.getId() ?: -1,
                        byProtocolOrder is LivingEntity, byProtocolOrder is ArmorStand,
                        byProtocolOrder?.distanceToSqr(nameSource) ?: Double.NaN,
                    )
                    if (association == "associated") {
                        body = byProtocolOrder
                        shadowAssassinLabelBodies++
                        lastLabelRejection = "none"
                    } else {
                        shadowAssassinLabelRejects++
                        lastLabelRejection = association
                    }
                } else if (byProtocolOrder is LivingEntity && byProtocolOrder !is ArmorStand
                    && byProtocolOrder.distanceToSqr(nameSource) <= 9.0
                ) {
                    body = byProtocolOrder
                }
            }
            // Prefer the source's adjacent entity ID. If that association is unavailable, require a
            // clearly closer body so a crowded nameplate cannot paint a neighboring mob.
            if (body == null && !shadowAssassinName) body = Nameplates.resolveUnambiguousBody(nameSource, bodies, true)
            val living = body as? LivingEntity
            if (living == null || living === player || !living.isAlive()) {
                unresolvedBodies++
                if (shadowAssassinLabel && lastLabelRejection == "none") {
                    lastLabelRejection = when {
                        body == null -> "associated-body-unavailable"
                        body === player -> "body-is-local-player"
                        else -> "associated-body-not-alive"
                    }
                    shadowAssassinLabelRejects++
                }
                continue
            }
            if (bodyFilter != null && !bodyFilter.test(living)) continue
            resolvedBodies++
            if (living.distanceToSqr(player) > boundedRange.toDouble() * boundedRange) {
                rangeRejected++
                if (shadowAssassinLabel) {
                    shadowAssassinLabelRejects++
                    lastLabelRejection = "body-out-of-range"
                }
                continue
            }
            if (!isAllowedByRoomFilter(currentRoomOnly, room, living.blockPosition())) {
                roomRejected++
                if (shadowAssassinLabel) {
                    shadowAssassinLabelRejects++
                    lastLabelRejection = roomFilterRejection(currentRoomOnly, room, living.blockPosition())
                }
                continue
            }
            if (confirmedMinibossLabel != null) {
                confirmedMinibossNameplates.add(MinibossNameplateMatch(living, confirmedMinibossLabel))
            }
            if (!displayMatch) continue
            if (!unique.add(living)) {
                if (shadowAssassinLabel) lastLabelRejection = "already-added-by-another-shadow-assassin-source"
                continue
            }
            matches.add(living)
            if (shadowAssassinLabel) shadowAssassins.add(living)
        }

        // Hypixel's invisible Shadow Assassin can be represented by a player entity whose exact profile
        // name is the NPC name, even when its separate armor-stand label is absent. Resolve that body
        // directly; unrelated party members cannot match this exact profile identity.
        if (shadowAssassinProfilePathEnabled) for (entity in nearby) {
            val playerEntity = entity as? Player ?: continue
            if (playerEntity === player || !isShadowAssassinProfile(playerEntity)) continue
            exactProfileBodies++
            lastProfileRejection = "exact-profile-found"
            if (!playerEntity.isAlive()) {
                exactProfileDeadRejects++
                lastProfileRejection = "profile-body-not-alive"
                continue
            }
            if (playerEntity.distanceToSqr(player) > boundedRange.toDouble() * boundedRange) {
                rangeRejected++
                exactProfileRangeRejects++
                lastProfileRejection = "profile-body-out-of-range"
                continue
            }
            if (!isAllowedByRoomFilter(currentRoomOnly, room, playerEntity.blockPosition())) {
                roomRejected++
                exactProfileRoomRejects++
                lastProfileRejection = roomFilterRejection(currentRoomOnly, room, playerEntity.blockPosition())
                continue
            }
            exactProfileAccepted++
            namedCandidates++
            if (!unique.add(playerEntity)) {
                lastProfileRejection = "already-added-by-nameplate"
                continue
            }
            resolvedBodies++
            matches.add(playerEntity)
            shadowAssassins.add(playerEntity)
            lastProfileRejection = "matched"
        }
        return ScanResult(
            java.util.List.copyOf(matches), nearby.size, namedCandidates, resolvedBodies,
            unresolvedBodies, rangeRejected, roomRejected, java.util.List.copyOf(nameSamples),
            java.util.List.copyOf(shadowAssassins), java.util.List.copyOf(confirmedMinibossNameplates), "",
            ShadowAssassinScanDiagnostics(
                shadowAssassinProfilePathEnabled, shadowAssassinLabels, shadowAssassinLabelBodies,
                shadowAssassinLabelRejects, exactProfileBodies, exactProfileAccepted,
                exactProfileRangeRejects, exactProfileRoomRejects, exactProfileDeadRejects,
                lastLabelRejection, lastProfileRejection, "",
            ),
        )
    }

    /** Finds Fel marker armor stands by the exact player-head texture used by Skyblocker. */
    @JvmStatic
    fun findFelSkullMarkers(
        client: Minecraft?, range: Int, currentRoomOnly: Boolean,
        roomFootprint: DungeonRoomTracker.MapRoomFootprint?,
    ): List<ArmorStand> {
        if (client == null) return emptyList()
        val level = client.level ?: return emptyList()
        val player = client.player ?: return emptyList()
        if (!DungeonContextTracker.inDungeon()) return emptyList()
        val boundedRange = range.coerceIn(1, 128)
        val maxDistanceSquared = boundedRange.toDouble() * boundedRange
        val room = if (currentRoomOnly) resolveRoomFootprint(roomFootprint) else null
        if (currentRoomOnly && room == null) return emptyList()
        val result = ArrayList<ArmorStand>()
        for (entity in ClientEntitySnapshot.nearby(level, player, boundedRange.toDouble())) {
            val marker = entity as? ArmorStand ?: continue
            if (!isFelSkullMarker(marker)
                || marker.distanceToSqr(player) > maxDistanceSquared
                || (currentRoomOnly && room?.contains(marker.blockPosition()) != true)
            ) continue
            result.add(marker)
        }
        return java.util.List.copyOf(result)
    }

    @JvmStatic
    fun isFelSkullMarker(marker: ArmorStand?): Boolean = marker != null && isFelSkullMarker(
        marker.isMarker(), marker.hasItemInSlot(EquipmentSlot.HEAD), headTexture(marker.getItemBySlot(EquipmentSlot.HEAD)),
    )

    @JvmStatic
    fun isFelSkullMarker(marker: Boolean, hasHead: Boolean, texture: String?): Boolean =
        marker && hasHead && isFelHeadTexture(texture)

    @JvmStatic
    fun isFelHeadTexture(texture: String?): Boolean = FEL_HEAD_TEXTURE == texture

    private fun headTexture(stack: ItemStack?): String {
        if (stack == null || !stack.`is`(Items.PLAYER_HEAD)) return ""
        val profile: ResolvableProfile = stack.get(DataComponents.PROFILE) ?: return ""
        return profile.partialProfile().properties().get("textures").asSequence()
            .filterNotNull()
            .map { it.value() }
            .filterNotNull()
            .firstOrNull() ?: ""
    }

    /** Resolves the Fels nameplate to a nearby living body without guessing beyond the normal nameplate radius. */
    @JvmStatic
    fun findFels(client: Minecraft?, range: Int, currentRoomOnly: Boolean): List<FelCandidate> =
        findFels(client, range, currentRoomOnly, currentConfirmedRoomFootprint())

    @JvmStatic
    fun findFels(
        client: Minecraft?, range: Int, currentRoomOnly: Boolean,
        roomFootprint: DungeonRoomTracker.MapRoomFootprint?,
    ): List<FelCandidate> {
        if (client == null) return emptyList()
        val level = client.level ?: return emptyList()
        val player = client.player ?: return emptyList()
        if (!DungeonContextTracker.inDungeon()) return emptyList()
        val boundedRange = range.coerceIn(1, 128)
        val maxDistanceSquared = boundedRange.toDouble() * boundedRange
        val room = if (currentRoomOnly) resolveRoomFootprint(roomFootprint) else null
        if (currentRoomOnly && room == null) return emptyList()
        val nearby = ClientEntitySnapshot.nearby(level, player, boundedRange.toDouble())
        val bodies = nearby.mapNotNull { entity ->
            val living = entity as? LivingEntity
            if (living != null && living !is ArmorStand) living else null
        }
        val candidates = IdentityHashMap<LivingEntity, Boolean>()
        for (source in nearby) {
            if (source === player || !isFelName(plainName(source))) continue
            var body: Entity = source
            if (source is ArmorStand) {
                val byProtocolOrder = level.getEntity(source.getId() - 1)
                if (byProtocolOrder is LivingEntity && byProtocolOrder !is ArmorStand
                    && byProtocolOrder.distanceToSqr(source) <= 9.0
                ) body = byProtocolOrder
                else body = Nameplates.resolveUnambiguousBody(source, bodies, true) ?: continue
            }
            val living = body as? LivingEntity ?: continue
            if (living === player || !living.isAlive()
                || living.distanceToSqr(player) > maxDistanceSquared
                || (currentRoomOnly && room?.contains(living.blockPosition()) != true)
            ) continue
            candidates[living] = (candidates[living] ?: false) || isStarred(plainName(source))
        }
        return java.util.List.copyOf(candidates.entries.map { entry -> FelCandidate(entry.key, entry.value) })
    }

    private fun resolveRoomFootprint(footprint: DungeonRoomTracker.MapRoomFootprint?): DungeonRoomTracker.MapRoomFootprint? =
        footprint ?: currentConfirmedRoomFootprint()

    @JvmStatic
    fun confirmedRoomFootprint(
        calibratedMapGeometry: Boolean,
        cells: List<DungeonRoomTracker.CellKey?>?,
    ): DungeonRoomTracker.MapRoomFootprint? {
        if (!calibratedMapGeometry || cells.isNullOrEmpty()) return null
        return DungeonRoomTracker.MapRoomFootprint(null, cells)
    }

    @JvmStatic
    fun currentConfirmedRoomFootprint(): DungeonRoomTracker.MapRoomFootprint? =
        if (DungeonContextTracker.inDungeon()) DungeonRoomTracker.currentFootprint() else null

    @JvmStatic fun shouldHighlightFel(enabled: Boolean, starred: Boolean): Boolean = enabled && starred

    @JvmStatic
    fun isFelName(name: String?): Boolean {
        val normalized = normalizedMobName(name)
        return normalized == "fel" || normalized == "fels"
    }

    @JvmStatic fun shouldRetainRoomFootprint(sameLevel: Boolean, playerInside: Boolean): Boolean = sameLevel && playerInside

    @JvmStatic
    fun isEligibleMinibossName(name: String?, onlyStarred: Boolean): Boolean =
        isNamedMiniboss(name) && (isShadowAssassin(name) || !onlyStarred || isStarred(name))

    @JvmStatic fun shouldClassifyFelsForStarFilter(starredEnabled: Boolean, felsEnabled: Boolean): Boolean =
        starredEnabled || felsEnabled

    @JvmStatic fun isStarredGroupTarget(felBody: Boolean, shadowAssassinBody: Boolean): Boolean =
        !felBody && !shadowAssassinBody

    @JvmStatic
    fun nearAnyStarredPosition(
        x: Double, y: Double, z: Double, positions: List<WorldPosition?>?, radius: Double,
    ): Boolean {
        if (positions.isNullOrEmpty() || !x.isFinite() || !y.isFinite() || !z.isFinite()
            || !radius.isFinite() || radius < 0
        ) return false
        val radiusSquared = radius * radius
        for (position in positions) {
            if (position == null || !position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite()) continue
            val dx = position.x - x
            val dy = position.y - y
            val dz = position.z - z
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) return true
        }
        return false
    }

    @JvmStatic
    fun isShadowAssassinProfile(player: Player?): Boolean =
        player != null && isShadowAssassinProfileName(player.getGameProfile().name())

    @JvmStatic fun isShadowAssassinProfileName(name: String?): Boolean = name == "Shadow Assassin"

    /** Matches the reference Shadow Assassin held-item or dyed-boots signature. */
    @JvmStatic
    fun hasShadowAssassinEquipment(player: Player?): Boolean {
        if (player == null) return false
        val held = player.getItemBySlot(EquipmentSlot.MAINHAND)
        val boots = player.getItemBySlot(EquipmentSlot.FEET)
        return hasShadowAssassinEquipment(skyBlockItemId(held), dyedRgb(boots))
    }

    @JvmStatic
    fun hasShadowAssassinEquipment(mainHandSkyBlockId: String?, bootsDyeRgb: Int): Boolean =
        mainHandSkyBlockId == "SILENT_DEATH" || bootsDyeRgb == 6_029_470

    private fun skyBlockItemId(stack: ItemStack?): String {
        if (stack == null || stack.isEmpty) return ""
        val customData: CustomData? = stack.get(DataComponents.CUSTOM_DATA)
        return customData?.let { MacroItemCountRules.skyBlockIdFromCustomData(it.copyTag().toString()) } ?: ""
    }

    private fun dyedRgb(stack: ItemStack?): Int {
        if (stack == null || stack.isEmpty) return -1
        val dyedColor: DyedItemColor? = stack.get(DataComponents.DYED_COLOR)
        return dyedColor?.rgb() ?: -1
    }

    /** Exact player-profile names confirmed by reference source; this intentionally excludes label-only targets. */
    @JvmStatic fun isExactDungeonMinibossProfileName(name: String?): Boolean = EARLY_MINIBOSS_PROFILE_NAMES.contains(name)

    @JvmStatic
    fun shouldAcceptMinibossProfile(
        onlyStarred: Boolean, hasAssociatedStarredLabel: Boolean, shadowAssassin: Boolean,
    ): Boolean = shadowAssassin || !onlyStarred || hasAssociatedStarredLabel

    /** Starred and miniboss snapshots remain renderable at any distance while retained in memory. */
    @JvmStatic
    fun isOutsideRememberedHudRange(
        group: DungeonMobTargetMemory.Group?, distanceSquared: Double, configuredRange: Double,
    ): Boolean {
        if (group == DungeonMobTargetMemory.Group.STARRED || group == DungeonMobTargetMemory.Group.MINIBOSS) return false
        return distanceSquared > configuredRange * configuredRange
    }

    @JvmStatic
    fun isAllowedByRoomFilter(
        currentRoomOnly: Boolean, footprint: DungeonRoomTracker.MapRoomFootprint?, position: BlockPos?,
    ): Boolean = !currentRoomOnly || footprint != null && position != null && footprint.contains(position)

    @JvmStatic
    fun roomFilterRejection(
        currentRoomOnly: Boolean, footprint: DungeonRoomTracker.MapRoomFootprint?, position: BlockPos?,
    ): String {
        if (!currentRoomOnly) return "room-filter-disabled"
        if (footprint == null) return "confirmed-room-footprint-unavailable"
        if (position == null) return "body-position-unavailable"
        return if (footprint.contains(position)) "inside-confirmed-room" else "outside-confirmed-room"
    }

    @JvmStatic
    fun shadowAssassinAssociationReason(
        name: String?, armorStandId: Int, bodyId: Int, bodyLiving: Boolean, bodyArmorStand: Boolean,
        distanceSquared: Double,
    ): String {
        if (!isShadowAssassin(name)) return "label-name-mismatch"
        if (armorStandId <= 0) return "invalid-label-entity-id"
        if (bodyId < 0) return "body-not-found"
        if (bodyId != armorStandId - 1) return "body-id-not-label-minus-one"
        if (!bodyLiving) return "associated-body-not-living"
        if (bodyArmorStand) return "associated-body-is-armor-stand"
        // Entity ordering is the association; distance is not a second eligibility gate.
        return "associated"
    }

    /** Devonian correlates the exact named armor-stand label with the living entity at ID - 1. */
    @JvmStatic
    fun isNamedShadowAssassinArmorStandAssociation(
        name: String?, armorStandId: Int, bodyId: Int, bodyLiving: Boolean, bodyArmorStand: Boolean,
        distanceSquared: Double,
    ): Boolean = shadowAssassinAssociationReason(
        name, armorStandId, bodyId, bodyLiving, bodyArmorStand, distanceSquared,
    ) == "associated"

    @JvmStatic
    fun <T> addShadowAssassinCandidateOnce(
        minibosses: MutableList<T>?, shadowAssassins: MutableList<T>?, seenByIdentity: MutableSet<T>?, candidate: T?,
    ): Boolean {
        if (minibosses == null || shadowAssassins == null || seenByIdentity == null || candidate == null
            || !seenByIdentity.add(candidate)
        ) return false
        minibosses.add(candidate)
        shadowAssassins.add(candidate)
        return true
    }

    @JvmRecord data class WorldPosition(val x: Double, val y: Double, val z: Double)
    @JvmRecord data class FelCandidate(val entity: LivingEntity, val starred: Boolean)
    @JvmRecord data class MinibossNameplateMatch(val body: LivingEntity, val label: String)

    @JvmRecord
    data class ScanResult(
        val matches: List<LivingEntity>,
        val nearbyEntities: Int,
        val namedCandidates: Int,
        val resolvedBodies: Int,
        val unresolvedBodies: Int,
        val rangeRejected: Int,
        val roomRejected: Int,
        val nameSamples: List<String>,
        val shadowAssassins: List<LivingEntity>,
        val confirmedMinibossNameplates: List<MinibossNameplateMatch>,
        val reason: String?,
        val shadowAssassinDiagnostics: ShadowAssassinScanDiagnostics,
    ) {
        companion object {
            @JvmStatic
            fun empty(reason: String?): ScanResult = ScanResult(
                emptyList(), 0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList(), reason,
                ShadowAssassinScanDiagnostics.empty(reason),
            )
        }

        fun summary(): String = if (!javaStringIsBlank(reason!!)) reason else "entities=$nearbyEntities, names=$namedCandidates" +
            ", bodies=$resolvedBodies, unresolved=$unresolvedBodies, range=$rangeRejected" +
            ", room=$roomRejected, rendered=${matches.size}, named entities=$nameSamples"
    }

    @JvmRecord
    data class ShadowAssassinScanDiagnostics(
        val profilePathEnabled: Boolean,
        val labels: Int,
        val labelBodyAssociations: Int,
        val labelRejections: Int,
        val exactProfiles: Int,
        val exactProfileAccepted: Int,
        val exactProfileRangeRejections: Int,
        val exactProfileRoomRejections: Int,
        val exactProfileDeadRejections: Int,
        val lastLabelRejection: String?,
        val lastProfileRejection: String?,
        val earlyExitReason: String?,
    ) {
        companion object {
            @JvmStatic
            fun empty(reason: String?): ShadowAssassinScanDiagnostics = ShadowAssassinScanDiagnostics(
                false, 0, 0, 0, 0, 0, 0, 0, 0,
                "not-scanned", "not-scanned", reason ?: "",
            )
        }

        fun summary(): String = "profilePath=$profilePathEnabled, labels=$labels" +
            ", labelBodies=$labelBodyAssociations, labelRejected=$labelRejections (last=$lastLabelRejection)" +
            ", exactProfiles=$exactProfiles, profileAccepted=$exactProfileAccepted" +
            ", profileRangeRejected=$exactProfileRangeRejections, profileRoomRejected=$exactProfileRoomRejections" +
            ", profileDeadRejected=$exactProfileDeadRejections (last=$lastProfileRejection)" +
            (if (javaStringIsBlank(earlyExitReason)) "" else ", scanExit=$earlyExitReason")
    }

    @JvmStatic
    fun plainName(entity: Entity?): String {
        if (entity == null) return ""
        val custom = entity.getCustomName()
        return javaStringTrim(ChatText.plain((custom ?: entity.getName()).getString()))
    }

    /** Preserve java.lang.String.trim semantics while Kotlin ports share the same source names. */
    @JvmStatic
    fun javaStringTrim(value: String): String = value.trim { it <= '\u0020' }

    /** Preserve java.lang.String.isBlank semantics, including Character.isWhitespace's NBSP rule. */
    @JvmStatic
    fun javaStringIsBlank(value: String?): Boolean =
        value == null || value.all { Character.isWhitespace(it) }

    @JvmStatic
    fun isStarred(name: String?): Boolean {
        if (javaStringIsBlank(name)) return false
        val plain = javaStringTrim(ChatText.plain(name!!))
        // Require at least one visible character before the star, matching the packet nameplate rule.
        return plain.indexOf('✯') >= 1
    }

    @JvmStatic fun isNamedMiniboss(name: String?): Boolean = MINIBOSS_NAMES.contains(normalizedMobName(name))

    /** Returns the canonical miniboss label after applying the same exact roster normalization as ESP. */
    @JvmStatic
    fun recognizedMinibossLabel(name: String?): String? = when (normalizedMobName(name)) {
        "shadow assassin" -> "Shadow Assassin"
        "lost adventurer" -> "Lost Adventurer"
        "diamond guy" -> "Diamond Guy"
        "angry archaeologist" -> "Angry Archaeologist"
        "frozen adventurer" -> "Frozen Adventurer"
        "king midas" -> "King Midas"
        else -> null
    }

    @JvmStatic fun isShadowAssassin(name: String?): Boolean = normalizedMobName(name) == "shadow assassin"

    /** Expands the Shadow Assassin target to the full body used by the pinned reference behavior. */
    @JvmStatic
    fun fullShadowAssassinBounds(entity: LivingEntity?): AABB? {
        if (entity == null) return null
        return fullShadowAssassinBounds(entity.getX(), entity.getY(), entity.getZ())
    }

    @JvmStatic
    fun fullShadowAssassinBounds(entity: LivingEntity?, partialTick: Float): AABB? {
        if (entity == null) return null
        return fullShadowAssassinBounds(
            entity.xo, entity.yo, entity.zo, entity.getX(), entity.getY(), entity.getZ(), partialTick,
        )
    }

    @JvmStatic
    fun fullShadowAssassinBounds(
        previousX: Double, previousY: Double, previousZ: Double,
        currentX: Double, currentY: Double, currentZ: Double, partialTick: Float,
    ): AABB = fullShadowAssassinBounds(
        Mth.lerp(partialTick.toDouble(), previousX, currentX),
        Mth.lerp(partialTick.toDouble(), previousY, currentY),
        Mth.lerp(partialTick.toDouble(), previousZ, currentZ),
    )

    @JvmStatic
    fun fullShadowAssassinBounds(x: Double, y: Double, z: Double): AABB = AABB(
        x - 0.4, y, z - 0.4, x + 0.4, y + 2.0, z + 0.4,
    )

    @JvmStatic
    fun normalizedMobName(value: String?): String {
        var plain = javaStringTrim(ChatText.plain(value ?: "")).replace("✯", " ")
        plain = javaStringTrim(plain)
        // Hypixel prefixes some dungeon attributes with private-use glyphs. Remove those glyphs
        // before the anchored attribute matcher, otherwise "Healthy Fels" becomes an unmatched name.
        plain = stripPrivateUseGlyphs(plain)
        // Port the explicit dungeon-name affixes used before the captured name: optional level,
        // optional dungeon attribute, and the optional bracket token. Keep roster matching exact.
        plain = DUNGEON_LEVEL_PREFIX.matcher(plain).replaceFirst("")
        plain = DUNGEON_ATTRIBUTE_PREFIX.matcher(plain).replaceFirst("")
        plain = DUNGEON_BRACKET_PREFIX.matcher(plain).replaceFirst("")
        // SkyHanni's dungeon grammar treats the final health value as one token, including current/max.
        plain = DUNGEON_HEALTH_SUFFIX.matcher(plain).replaceFirst("")
        plain = plain.replace("ᛤ", " ")
        return javaStringTrim(plain.replace(NON_LETTERS, " "))
            .lowercase(Locale.ROOT).replace(WHITESPACE_RUN, " ")
    }

    private fun stripPrivateUseGlyphs(value: String): String {
        if (value.isEmpty()) return ""
        val result = StringBuilder(value.length)
        val iterator = value.codePoints().iterator()
        while (iterator.hasNext()) {
            val codePoint = iterator.nextInt()
            if (Character.getType(codePoint) != Character.PRIVATE_USE.toInt()) result.appendCodePoint(codePoint)
        }
        return javaStringTrim(result.toString())
    }

    enum class HudBoxSubmission {
        SUBMITTED, INPUT_UNAVAILABLE, PROJECTION_REJECTED, OUTSIDE_VIEWPORT, TRANSPARENT, NO_DRAWABLE_AREA,
    }

    @JvmStatic
    fun draw2dBox(
        graphics: GuiGraphicsExtractor?, camera: Camera?, entity: LivingEntity?, bounds: AABB?,
        fillColor: Int, outlineColor: Int, outlineWidth: Int,
    ): HudBoxSubmission {
        if (graphics == null || camera == null || bounds == null) return HudBoxSubmission.INPUT_UNAVAILABLE
        val projected = FloatArray(16)
        val out = FloatArray(2)
        var point = 0
        for (y in 0..1) for (z in 0..1) for (x in 0..1) {
            val corner = Vec3(
                if (x == 0) bounds.minX else bounds.maxX,
                if (y == 0) bounds.minY else bounds.maxY,
                if (z == 0) bounds.minZ else bounds.maxZ,
            )
            if (!WorldToScreen.projectInto(camera, corner, out)) return HudBoxSubmission.PROJECTION_REJECTED
            projected[point++] = out[0]
            projected[point++] = out[1]
        }
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        var index = 0
        while (index < projected.size) {
            left = minOf(left, projected[index])
            right = maxOf(right, projected[index])
            top = minOf(top, projected[index + 1])
            bottom = maxOf(bottom, projected[index + 1])
            index += 2
        }
        val window = Minecraft.getInstance().window
        val screenWidth = window.guiScaledWidth
        val screenHeight = window.guiScaledHeight
        val x0 = Math.clamp(kotlin.math.floor(left.toDouble()).toInt().toLong(), 0, screenWidth)
        val y0 = Math.clamp(kotlin.math.floor(top.toDouble()).toInt().toLong(), 0, screenHeight)
        val x1 = Math.clamp(kotlin.math.ceil(right.toDouble()).toInt().toLong(), 0, screenWidth)
        val y1 = Math.clamp(kotlin.math.ceil(bottom.toDouble()).toInt().toLong(), 0, screenHeight)
        if (x1 <= x0 || y1 <= y0) return HudBoxSubmission.OUTSIDE_VIEWPORT
        val fillVisible = (fillColor ushr 24) != 0
        val outlineVisible = (outlineColor ushr 24) != 0
        if (!fillVisible && !outlineVisible) return HudBoxSubmission.TRANSPARENT
        var submitted = false
        if (fillVisible) {
            val fillX1 = maxOf(x0 + 1, x1 - 1)
            val fillY1 = maxOf(y0 + 1, y1 - 1)
            if (fillX1 > x0 + 1 && fillY1 > y0 + 1) {
                graphics.fill(x0 + 1, y0 + 1, fillX1, fillY1, fillColor)
                submitted = true
            }
        }
        val line = outlineWidth.coerceIn(1, 6)
        if (outlineVisible) {
            graphics.fill(x0, y0, x1, minOf(y1, y0 + line), outlineColor)
            graphics.fill(x0, maxOf(y0, y1 - line), x1, y1, outlineColor)
            graphics.fill(x0, y0 + line, minOf(x1, x0 + line), maxOf(y0 + line, y1 - line), outlineColor)
            graphics.fill(maxOf(x0, x1 - line), y0 + line, x1, maxOf(y0 + line, y1 - line), outlineColor)
            submitted = true
        }
        return if (submitted) HudBoxSubmission.SUBMITTED else HudBoxSubmission.NO_DRAWABLE_AREA
    }

    @JvmStatic
    fun visibleFromPlayer(client: Minecraft?, entity: LivingEntity?): Boolean {
        if (client == null) return false
        val player = client.player ?: return false
        if (entity == null) return false
        return player.hasLineOfSight(entity)
    }

    @JvmStatic
    fun visibleFromPlayer(client: Minecraft?, target: Vec3?): Boolean {
        if (client == null) return false
        val player = client.player ?: return false
        val level = client.level ?: return false
        if (target == null) return false
        val eye = player.getEyePosition()
        return level.clip(
            ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player),
        ).type == HitResult.Type.MISS
    }
}

// Keep the module's existing Java-record-style calls source-compatible after these records move to Kotlin.
internal fun DungeonStarredMobTracker.Association.nameplateId() = nameplateId
internal fun DungeonStarredMobTracker.Association.bodyId() = bodyId
internal fun DungeonStarredMobTracker.Association.mobClass() = mobClass
internal fun DungeonStarredMobTracker.Association.starred() = starred
internal fun DungeonStarredMobTracker.Association.source() = source

internal fun DungeonMobEspSupport.FelCandidate.entity() = entity
internal fun DungeonMobEspSupport.FelCandidate.starred() = starred
internal fun DungeonMobEspSupport.MinibossNameplateMatch.body() = body
internal fun DungeonMobEspSupport.MinibossNameplateMatch.label() = label
internal fun DungeonMobEspSupport.ScanResult.matches() = matches
internal fun DungeonMobEspSupport.ScanResult.confirmedMinibossNameplates() = confirmedMinibossNameplates

internal fun MinibossAppearanceCapture.AppearanceIdentity.textureSignature() = textureSignature
internal fun MinibossAppearanceCapture.AppearanceIdentity.model() = model
internal fun MinibossAppearanceCatalogStore.LoadResult.catalog() = catalog
internal fun MinibossAppearanceCatalogStore.LoadResult.status() = status
