package geiler.addons.client.module.impl

import geiler.addons.client.hunting.BeeBlock
import geiler.addons.client.hunting.BeeStatus
import geiler.addons.client.hunting.MarkerRules
import geiler.addons.client.hunting.SafariPresets
import geiler.addons.client.hunting.SafariVisibility
import geiler.addons.client.hunting.WallState
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.floor

/**
 * What looking at the fixed Safari positions has established this run: wall state, bee spots, Hideonwall perches,
 * and the Rockmite mounds found. Every read of the world goes through [SafariVisibility]; positions themselves
 * are free. Reset when a run starts, because every run places its own mounds and breaks its own walls.
 */
object CritterSafariWorld {
    class WallSet(positions: List<IntArray>) {
        val pos: List<BlockPos> = positions.map { BlockPos(it[0], it[1], it[2]) }
        val states: Array<WallState> = Array(pos.size) { WallState.UNKNOWN }
        fun allBroken(): Boolean = states.all { it == WallState.BROKEN }
        fun remaining(): Int = states.count { it != WallState.BROKEN }
    }

    val snooper = WallSet(SafariPresets.SNOOPER_WALLS)
    val troodon = WallSet(SafariPresets.TROODON_WALLS)
    val beePos: List<BlockPos> = SafariPresets.BEE_SPOTS.map { BlockPos(it[0], it[1], it[2]) }
    val beeStatus: Array<BeeStatus> = Array(beePos.size) { BeeStatus.UNCHECKED }
    val perchPos: List<BlockPos> = SafariPresets.HIDEONWALL_PERCHES.map { BlockPos(it[0], it[1], it[2]) }
    val perchOccupied: BooleanArray = BooleanArray(perchPos.size)

    /** A perch that has been looked at. Seen and not occupied means resolved empty, so its mark goes. */
    val perchSeen: BooleanArray = BooleanArray(perchPos.size)

    /** Mounds seen this run and not yet seen to be gone. Bounded; a run has a few dozen at most. */
    val mounds: MutableSet<BlockPos> = LinkedHashSet()

    private const val SCAN_INTERVAL_TICKS = 10
    private const val SCAN_RADIUS = 64.0
    private const val MAX_MOUNDS = 128
    private const val PUNCH_WINDOW_MILLIS = 3_000L
    private const val BEE_RADIUS = 6.0
    private const val PERCH_RADIUS = 3.0
    private const val INSIDE_RADIUS = 0.35
    private const val INSIDE_HEIGHT = 1.0
    private const val CENTRE_TOLERANCE = 0.05

    private var ticks = 0
    private var punched = -1
    private var punchedAt = 0L

    @JvmStatic
    fun reset() {
        snooper.states.fill(WallState.UNKNOWN)
        troodon.states.fill(WallState.UNKNOWN)
        beeStatus.fill(BeeStatus.UNCHECKED)
        perchOccupied.fill(false)
        perchSeen.fill(false)
        mounds.clear()
        punched = -1
        ticks = 0
    }

    /** `[count]` of mounds currently marked, for the HUD. */
    @JvmStatic
    fun moundCount(): Int = mounds.size

    /** Called each client tick in the Safari; does its work every [SCAN_INTERVAL_TICKS]. [fresh] are labels first seen this sweep. */
    fun tick(sightings: List<CritterSighting>, fresh: List<CritterSighting>, wallsAndPerches: Boolean, beesWanted: Boolean, moundsWanted: Boolean) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return

        if (beesWanted) watchPunch(mc)
        if (beesWanted && punched >= 0) resolvePunch(fresh)
        if (++ticks < SCAN_INTERVAL_TICKS) return
        ticks = 0

        if (wallsAndPerches) {
            for (set in listOf(snooper, troodon)) {
                for (i in set.pos.indices) {
                    if (set.states[i] == WallState.BROKEN) continue
                    val pos = set.pos[i]
                    val loaded = level.hasChunkAt(pos)
                    val solid = loaded && !level.getBlockState(pos).isAir
                    val visible = loaded && SafariVisibility.canSee(pos)
                    set.states[i] = MarkerRules.wallState(set.states[i], loaded, visible, solid)
                }
            }
            for (i in perchPos.indices) {
                if (!SafariVisibility.canSee(perchPos[i])) continue
                perchSeen[i] = true
                val centre = Vec3.atCenterOf(perchPos[i])
                perchOccupied[i] = sightings.any {
                    it.critter.name == "Hideonwall" && it.position().distanceToSqr(centre) <= PERCH_RADIUS * PERCH_RADIUS
                }
            }
        }

        if (beesWanted) {
            for (i in beePos.indices) {
                val pos = beePos[i]
                if (beeStatus[i] == BeeStatus.CLEARED || !level.hasChunkAt(pos)) continue
                val visible = SafariVisibility.canSee(pos)
                if (!visible) continue
                val state = level.getBlockState(pos)
                val block = when {
                    state.`is`(Blocks.BEE_NEST) -> BeeBlock.NEST
                    state.`is`(Blocks.BEEHIVE) -> BeeBlock.HIVE
                    else -> BeeBlock.OTHER
                }
                beeStatus[i] = MarkerRules.beeStatus(beeStatus[i], true, block)
            }
        }

        if (moundsWanted) scanMounds(level.entitiesForRendering(), player.position())
    }

    /** A punch arms the spot; the mark only goes when a *new* Honeybug turns up beside it (see [resolvePunch]). */
    private fun watchPunch(mc: Minecraft) {
        if (!mc.options.keyAttack.isDown) return
        val hit = mc.hitResult as? BlockHitResult ?: return
        val index = MarkerRules.nearestPreset(SafariPresets.BEE_SPOTS, hit.blockPos.x, hit.blockPos.y, hit.blockPos.z, MarkerRules.BEE_PUNCH_RADIUS)
        if (index < 0 || beeStatus[index] != BeeStatus.NEST) return
        punched = index
        punchedAt = System.currentTimeMillis()
    }

    /** Nothing is said in chat when a Safari nest is punched, so the bee arriving is the only evidence. */
    private fun resolvePunch(fresh: List<CritterSighting>) {
        if (System.currentTimeMillis() - punchedAt > PUNCH_WINDOW_MILLIS) {
            punched = -1
            return
        }
        val centre = Vec3.atCenterOf(beePos[punched])
        if (fresh.any { it.critter.name == "Honeybug" && it.position().distanceToSqr(centre) <= BEE_RADIUS * BEE_RADIUS }) {
            beeStatus[punched] = BeeStatus.CLEARED
            punched = -1
        }
    }

    /** Interaction entities that look like mounds, taken up only on sight and dropped only when seen to be gone. */
    private fun scanMounds(entities: Iterable<Entity>, playerPos: Vec3) {
        val candidates = ArrayList<Entity>()
        val creatures = ArrayList<Entity>()
        val limit = SCAN_RADIUS * SCAN_RADIUS
        for (entity in entities) {
            if (entity.position().distanceToSqr(playerPos) > limit) continue
            if (entity.type != EntityTypes.INTERACTION) {
                if (isCreature(entity)) creatures.add(entity)
                continue
            }
            if (entity.y > MarkerRules.MOUND_MAX_Y) continue
            candidates.add(entity)
        }
        val live = HashSet<BlockPos>()
        for (candidate in candidates) {
            val box = candidate.boundingBox
            val occupied = creatures.any {
                abs(it.x - candidate.x) <= INSIDE_RADIUS && abs(it.z - candidate.z) <= INSIDE_RADIUS && abs(it.y - candidate.y) <= INSIDE_HEIGHT
            }
            if (!MarkerRules.isMound(box.xsize, box.ysize, candidate.y, centred(candidate.x) && centred(candidate.z), occupied)) continue
            val pos = candidate.blockPosition()
            live.add(pos)
            if (pos in mounds || mounds.size >= MAX_MOUNDS) continue
            if (SafariVisibility.canSee(candidate)) mounds.add(pos)
        }
        // Gone only when seen to be gone: in sweep range (outside it a mound is absent whether or not it stands) and in view.
        mounds.removeIf { pos ->
            pos !in live &&
                Vec3.atCenterOf(pos).distanceToSqr(playerPos) <= limit &&
                SafariVisibility.canSee(pos)
        }
    }

    private fun centred(coordinate: Double): Boolean = abs(coordinate - (floor(coordinate) + 0.5)) < CENTRE_TOLERANCE

    /** Excludes the scaffolding entities Hypixel builds its props out of. */
    private fun isCreature(entity: Entity): Boolean {
        val type = entity.type
        return type !== EntityTypes.ARMOR_STAND && type !== EntityTypes.ITEM_DISPLAY && type !== EntityTypes.BLOCK_DISPLAY &&
            type !== EntityTypes.TEXT_DISPLAY && type !== EntityTypes.PLAYER && type !== EntityTypes.ITEM
    }
}
