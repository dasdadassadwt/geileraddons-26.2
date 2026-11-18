package geiler.addons.client.slayer

import geiler.addons.client.entity.ClientEntitySnapshot
import geiler.addons.client.farming.SlayerScoreboardParser
import geiler.addons.client.entity.Nameplates
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.tree.ChatText
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile

/**
 * One bounded scan per two client ticks that every Slayer module reads: the live boss labels and the mob body each
 * belongs to, which boss is the local player's, minibosses, and the labelled or headed armor stands near them.
 * Runs only while a Slayer module asks for it ([tick] is called by the client tick hook when one is enabled).
 * A boss counts as the player's own only through its "Spawned by" label, or, when no label is in range, as the lone
 * boss while the sidebar says the player's own boss is active; otherwise other players' bosses stay `own = false`.
 *
 * Reference behaviour: Skyblocker's SlayerManager pairs a boss label stand with the nearest mob body and finds the
 * owner from a nearby stand that holds the player's name. All caps below bound the work and the log output.
 */
object SlayerBossTracker {
    const val MAX_RANGE = 64.0
    private const val SCAN_INTERVAL_TICKS = 2
    private const val BODY_PAIR_RADIUS_SQ = 3.5 * 3.5
    private const val OWNER_XZ_SQ = 1.2 * 1.2
    private const val OWNER_DY = 3.0
    private const val MAX_LABELS = 160
    private const val MAX_BODIES = 96
    private const val MAX_SKULLS = 32
    private const val MAX_GLYPHS = 16
    private const val MAX_BOSSES = 8
    private const val MAX_MINIBOSSES = 16
    private const val SIDEBAR_INTERVAL_TICKS = 10

    class Label(val entity: ArmorStand, val text: String)

    class Boss(val family: SlayerRules.Family, val body: LivingEntity, val label: ArmorStand, val own: Boolean)

    class Mini(val body: LivingEntity, val name: String)

    class Snapshot(
        val scanTick: Long,
        val ownBoss: Boss?,
        val bosses: List<Boss>,
        val minibosses: List<Mini>,
        val labels: List<Label>,
        val bodies: List<LivingEntity>,
        val glyphStands: Map<Int, DoubleArray>,
        val skulls: List<ArmorStand>,
        val ichors: List<ArmorStand>,
        val springs: List<ArmorStand>,
        /** True while the sidebar says the player's own Slayer boss is alive. */
        val bossActive: Boolean,
        /** True while the player has a Slayer quest but the boss has not spawned yet. */
        val questActive: Boolean,
        val ownerLabelsSeen: Int,
        /** True when no owner label was found at all and the only boss in range was taken as the player's. */
        val ownerUnverified: Boolean,
        /** True while the sidebar says "Boss slain!". */
        val bossSlain: Boolean = false,
    ) {
        fun hasFamily(family: SlayerRules.Family): Boolean = bosses.any { it.family == family }

        fun ownFamily(): SlayerRules.Family? = ownBoss?.family

        /** Plain texts of the labels within [radius] blocks of [around]. */
        fun labelTextsNear(around: Entity, radius: Double): List<String> {
            val limit = radius * radius
            val pos = around.position()
            return labels.filter { it.entity.position().distanceToSqr(pos) <= limit }.map { it.text }
        }
    }

    private val EMPTY = Snapshot(0L, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(), emptyList(), false, false, 0, false)

    @Volatile
    var snapshot: Snapshot = EMPTY
        private set

    private var sidebarState = SlayerScoreboardParser.State.INCOMPLETE
    private var sinceSidebar = SIDEBAR_INTERVAL_TICKS
    private var lastLevel: ClientLevel? = null
    private var tickCounter = 0L
    private var sinceScan = SCAN_INTERVAL_TICKS
    var scans = 0L
        private set

    /** Slayer bosses are fought on SkyBlock islands; dungeons and Kuudra reuse boss-like labels and are excluded. */
    fun eligible(): Boolean {
        if (!SkyblockItems.inSkyblock()) return false
        val island = HypixelModApi.currentIsland()
        return island != Island.CATACOMBS && island != Island.KUUDRA
    }

    fun reset() {
        snapshot = EMPTY
        sinceScan = SCAN_INTERVAL_TICKS
        sidebarState = SlayerScoreboardParser.State.INCOMPLETE
        sinceSidebar = SIDEBAR_INTERVAL_TICKS
    }

    /** The client tick count the tracker has seen; modules use it as their shared clock. */
    fun clock(): Long = tickCounter

    fun tick(range: Double) {
        tickCounter++
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        if (level == null || player == null) {
            if (snapshot !== EMPTY) reset()
            lastLevel = null
            return
        }
        if (level !== lastLevel) {
            lastLevel = level
            reset()
        }
        if (++sinceSidebar >= SIDEBAR_INTERVAL_TICKS) {
            sinceSidebar = 0
            val state = SlayerScoreboardParser.parse(SlayerSidebar.read(level.scoreboard))
            // A half-drawn sidebar says nothing; keep the last real state instead of flickering.
            if (state != SlayerScoreboardParser.State.INCOMPLETE) sidebarState = state
        }
        if (++sinceScan < SCAN_INTERVAL_TICKS) return
        sinceScan = 0
        scans++
        val bounded = range.coerceIn(8.0, MAX_RANGE)
        val nearby = ClientEntitySnapshot.nearby(level, player, bounded)
        val limit = bounded * bounded
        val labels = ArrayList<Label>()
        val bodies = ArrayList<LivingEntity>()
        val skulls = ArrayList<ArmorStand>()
        val ichors = ArrayList<ArmorStand>()
        val springs = ArrayList<ArmorStand>()
        val glyphs = HashMap<Int, DoubleArray>()
        for (entity in nearby) {
            if (entity === player || entity.isRemoved || entity.distanceToSqr(player) > limit) continue
            if (entity is ArmorStand) {
                collectStand(entity, labels, skulls, ichors, springs, glyphs)
            } else if (entity is LivingEntity && bodies.size < MAX_BODIES && Nameplates.hasBody(entity)) {
                bodies.add(entity)
            }
        }
        snapshot = resolve(tickCounter, player.gameProfile.name, labels, bodies, skulls, ichors, springs, glyphs)
    }

    private fun collectStand(
        stand: ArmorStand,
        labels: MutableList<Label>,
        skulls: MutableList<ArmorStand>,
        ichors: MutableList<ArmorStand>,
        springs: MutableList<ArmorStand>,
        glyphs: MutableMap<Int, DoubleArray>,
    ) {
        val name = stand.customName
        if (name != null && labels.size < MAX_LABELS) {
            val text = ChatText.plain(name.string)
            if (text.isNotBlank()) labels.add(Label(stand, text))
        }
        if (!stand.hasItemInSlot(EquipmentSlot.HEAD)) return
        val head = stand.getItemBySlot(EquipmentSlot.HEAD)
        if (head.`is`(Items.BEACON) && glyphs.size < MAX_GLYPHS) {
            glyphs[stand.id] = doubleArrayOf(stand.x, stand.y, stand.z)
        } else if (head.`is`(Items.PLAYER_HEAD)) {
            val texture = headTexture(head)
            if (skulls.size < MAX_SKULLS && SlayerRules.isNukekubiTexture(texture)) {
                skulls.add(stand)
            } else if (ichors.size < MAX_SKULLS && SlayerRules.isBloodIchorTexture(texture)) {
                ichors.add(stand)
            } else if (springs.size < MAX_SKULLS && SlayerRules.isKillerSpringTexture(texture)) {
                springs.add(stand)
            }
        }
    }

    private fun resolve(
        tick: Long,
        playerName: String,
        labels: List<Label>,
        bodies: List<LivingEntity>,
        skulls: List<ArmorStand>,
        ichors: List<ArmorStand>,
        springs: List<ArmorStand>,
        glyphs: Map<Int, DoubleArray>,
    ): Snapshot {
        val bossActive = sidebarState == SlayerScoreboardParser.State.BOSS_ACTIVE
        val owners = labels.filter { SlayerRules.isSpawnedByLabel(it.text, playerName) }
        val found = ArrayList<Boss>()
        for (label in labels) {
            if (found.size >= MAX_BOSSES) break
            if (!SlayerRules.isLiveBossLabel(label.text)) continue
            val family = SlayerRules.bossFamily(label.text) ?: continue
            val body = nearestBody(label.entity, bodies) { matchesFamily(family, it.type) } ?: continue
            val own = owners.any { isOwnerOf(it.entity, label.entity) }
            found.add(Boss(family, body, label.entity, own))
        }
        var unverified = false
        if (bossActive && owners.isEmpty() && found.size == 1) {
            // No owner label exists in range, but the sidebar says my boss is alive: a lone boss is mine.
            found[0] = Boss(found[0].family, found[0].body, found[0].label, true)
            unverified = true
        }
        val minis = ArrayList<Mini>()
        for (label in labels) {
            if (minis.size >= MAX_MINIBOSSES) break
            if (!SlayerRules.isMinibossName(label.text, null)) continue
            val body = nearestBody(label.entity, bodies) { it !is net.minecraft.world.entity.player.Player } ?: continue
            if (found.any { it.body === body }) continue
            minis.add(Mini(body, label.text))
        }
        return Snapshot(
            tick, found.firstOrNull { it.own }, java.util.List.copyOf(found), java.util.List.copyOf(minis),
            java.util.List.copyOf(labels), java.util.List.copyOf(bodies), java.util.Map.copyOf(glyphs),
            java.util.List.copyOf(skulls), java.util.List.copyOf(ichors), java.util.List.copyOf(springs), bossActive,
            sidebarState == SlayerScoreboardParser.State.QUEST_ACTIVE, owners.size, unverified,
            sidebarState == SlayerScoreboardParser.State.BOSS_SLAIN,
        )
    }

    private fun isOwnerOf(owner: ArmorStand, bossLabel: ArmorStand): Boolean {
        val dx = owner.x - bossLabel.x
        val dz = owner.z - bossLabel.z
        return dx * dx + dz * dz <= OWNER_XZ_SQ && Math.abs(owner.y - bossLabel.y) <= OWNER_DY
    }

    private fun nearestBody(label: Entity, bodies: List<LivingEntity>, accept: (LivingEntity) -> Boolean): LivingEntity? {
        var best: LivingEntity? = null
        var bestDistance = BODY_PAIR_RADIUS_SQ
        val at = label.position()
        for (body in bodies) {
            if (!accept(body)) continue
            val distance = body.position().distanceToSqr(at)
            if (distance < bestDistance) {
                bestDistance = distance
                best = body
            }
        }
        return best
    }

    private fun matchesFamily(family: SlayerRules.Family, type: EntityType<*>): Boolean = when (family) {
        SlayerRules.Family.REVENANT -> type === EntityTypes.ZOMBIE
        SlayerRules.Family.TARANTULA -> type === EntityTypes.SPIDER
        SlayerRules.Family.SVEN -> type === EntityTypes.WOLF
        SlayerRules.Family.VOIDGLOOM -> type === EntityTypes.ENDERMAN
        SlayerRules.Family.DEMONLORD -> type === EntityTypes.BLAZE
        SlayerRules.Family.VAMPIRE -> type === EntityTypes.PLAYER
    }

    private fun headTexture(stack: ItemStack): String {
        if (!stack.`is`(Items.PLAYER_HEAD)) return ""
        val profile: ResolvableProfile = stack.get(DataComponents.PROFILE) ?: return ""
        return profile.partialProfile().properties().get("textures").asSequence()
            .mapNotNull { it?.value() }
            .firstOrNull() ?: ""
    }
}
