package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.LeverAimRules
import geiler.addons.client.dungeon.f7.TerminalEspRules
import geiler.addons.client.macros.input.SimulatedMouse
import geiler.addons.client.macros.rotation.FrameRotations
import geiler.addons.client.macros.rotation.FrameSteered
import geiler.addons.client.macros.rotation.LookControl
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom

/**
 * Helps with the two levers of each F7 and M7 Phase 3 section (not the device): a pull that turns the view part of the
 * way to a lever and stops, and a trigger that has the Rotation Engine turn onto a lever in a narrow cone and clicks
 * after a delay drawn between two values. A lever is the block next to a `Not Activated` armor stand (Odin's Terminals
 * Status reads the same stand; behavior only).
 */
class LeverAimAssistModule private constructor() : Module(
    "Lever Aim Assist",
    "Pulls the view toward the terminal levers in F7 and M7, and clicks them after a delay.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val pullOn = spec.toggle("pull")
    private val pullStrength = spec.slider("pullStrength")
    private val pullSpeed = spec.slider("pullSpeed")
    private val pullFov = spec.slider("pullFov")
    private val triggerOn = spec.toggle("trigger")
    private val triggerFov = spec.slider("triggerFov")
    private val delayMin = spec.slider("delayMin")
    private val delayMax = spec.slider("delayMax")
    private val reach = spec.slider("reach")

    private enum class Mode { NONE, PULL, TRIGGER }

    private class Candidate(val pos: BlockPos, val point: Vec3, val error: Double, val distance: Double)

    private val look = LookControl()
    private val pull = LeverAimRules.Pull()
    private val trigger = LeverAimRules.Trigger(ThreadLocalRandom.current())
    private var mode = Mode.NONE
    private var targetKey = LeverAimRules.NO_KEY
    private var pullPoint: Vec3? = null
    private var releasePending = false
    private var lastLevel: Any? = null
    private var lastState = ""
    private var stands = 0
    private var candidates = 0
    private var clicks = 0
    private var lastSkip = ""
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    init {
        group(*spec.groups.toTypedArray())
        FrameRotations.register(object : FrameSteered {
            override fun advance(player: LocalPlayer, deltaSeconds: Float) = pullFrame(player, deltaSeconds)
        })
    }

    override fun onEnable() {
        idle()
        clicks = 0
        diagnostics.enable(
            "pull=${pullOn.value()} strength=${pullStrength.value().toInt()}% speed=${pullSpeed.value().toInt()} " +
                "pullFov=${pullFov.value().toInt()} trigger=${triggerOn.value()} triggerFov=${triggerFov.value().toInt()} " +
                "delay=${delayMin.value().toInt()}..${delayMax.value().toInt()}ms reach=${reach.value()}",
        )
    }

    override fun onDisable() {
        releaseClick(Minecraft.getInstance())
        idle()
        diagnostics.event("DISABLE clicks=$clicks")
        diagnostics.disable()
    }

    /** The world `/ga testlever` placed test levers in; while it is the current one the F7 requirement is waived. */
    private var testLevel: ClientLevel? = null
    private val testBlocks = ArrayList<BlockPos>()
    private val testStands = ArrayList<ArmorStand>()

    private fun active(): Boolean =
        (testLevel != null && testLevel === Minecraft.getInstance().level) ||
            (DungeonContextTracker.inDungeon() && DungeonContextTracker.currentFloor()?.number() == 7)

    /**
     * Test aid for singleplayer: puts three client-side levers, each with a `Not Activated` stand, a few blocks in front
     * of the player and lets the module run outside F7. The server never sees them, so a pull only exercises the aim.
     * Returns how many levers were placed, or -1 without a world.
     */
    fun placeTestLevers(): Int {
        val client = Minecraft.getInstance()
        val level = client.level ?: return -1
        val player = client.player ?: return -1
        clearTestLevers()
        val ahead = player.blockPosition().relative(player.direction, 4)
        for (k in -1..1) {
            val base = ahead.relative(player.direction.clockWise, k * 2)
            val lever = base.above()
            level.setBlock(base, Blocks.STONE.defaultBlockState(), 3)
            level.setBlock(lever, Blocks.LEVER.defaultBlockState(), 3)
            testBlocks.add(base)
            testBlocks.add(lever)
            val stand = ArmorStand(level, lever.x + 0.5, lever.y + 0.2, lever.z + 0.5)
            stand.customName = Component.literal(STAND_NAME)
            stand.isCustomNameVisible = true
            stand.isInvisible = true
            level.addEntity(stand)
            testStands.add(stand)
        }
        testLevel = level
        diagnostics.event("TEST_LEVERS placed=${testStands.size} at=${ahead.x},${ahead.y},${ahead.z} enabled=${isEnabled()}")
        return testStands.size
    }

    /** Takes the test levers and stands out again and ends the F7 waiver. */
    fun clearTestLevers() {
        val level = testLevel
        if (level != null && level === Minecraft.getInstance().level) {
            for (pos in testBlocks) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
            for (stand in testStands) level.removeEntity(stand.id, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED)
        }
        if (testLevel != null) diagnostics.event("TEST_LEVERS cleared")
        testBlocks.clear()
        testStands.clear()
        testLevel = null
    }

    /** Nothing is helped: the view is left alone and the trigger forgets what it had armed. */
    private fun idle() {
        mode = Mode.NONE
        targetKey = LeverAimRules.NO_KEY
        pullPoint = null
        look.release()
        pull.reset()
        trigger.clear()
    }

    private fun releaseClick(client: Minecraft) {
        if (!releasePending) return
        releasePending = false
        SimulatedMouse.release(client, SimulatedMouse.Button.RIGHT)
    }

    fun tick() {
        if (!isEnabled()) return
        val client = Minecraft.getInstance()
        releaseClick(client)
        val player = client.player
        val level = client.level
        val screenOpen = client.gui.screen() != null
        if (player == null || level == null || !active() || screenOpen) {
            if (mode != Mode.NONE) diagnostics.event("IDLE reason=${if (screenOpen) "screen" else "not-in-f7"}")
            idle()
            return
        }
        diagnostics.advance()
        if (lastLevel !== level) {
            lastLevel = level
            idle()
        }
        // Another automation (Auto Mask) owns the view and the mouse right now.
        if (geiler.addons.client.dungeon.f7.AutomationLock.blocks(LOCK_NAME, System.currentTimeMillis())) {
            if (mode != Mode.NONE) diagnostics.event("IDLE reason=automation-lock")
            idle()
            return
        }
        val found = candidates(player, level)
        val pullFovValue = if (pullOn.value()) pullFov.value().toDouble() else 0.0
        val triggerFovValue = if (triggerOn.value()) triggerFov.value().toDouble() else 0.0
        val index = LeverAimRules.pick(DoubleArray(found.size) { found[it].error }, maxOf(pullFovValue, triggerFovValue))
        if (index < 0) {
            noteState("NONE", null)
            idle()
            return
        }
        val target = found[index]
        val key = target.pos.asLong()
        targetKey = key
        val now = System.currentTimeMillis()
        if (triggerOn.value() && target.error <= triggerFovValue) {
            mode = Mode.TRIGGER
            pullPoint = null
            pull.reset()
            look.lookAtExact(target.point)
            val hit = client.hitResult
            val aimed = hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && hit.blockPos == target.pos
            noteState("TRIGGER", target)
            if (trigger.update(key, true, aimed, now, delayMin.value().toLong(), delayMax.value().toLong())) {
                SimulatedMouse.press(client, SimulatedMouse.Button.RIGHT)
                releasePending = true
                clicks++
                diagnostics.event(
                    "CLICK pos=${target.pos.toShortString()} delayMs=${trigger.lastDelayMs()} error=${fmt(target.error)} " +
                        "distance=${fmt(target.distance)} clicks=$clicks",
                )
            }
        } else {
            mode = Mode.PULL
            look.release()
            trigger.update(key, false, false, now, 0L, 0L)
            pullPoint = target.point
            noteState("PULL", target)
        }
        diagnostics.status {
            "enabled=true mode=$mode stands=$stands candidates=$candidates target=${target.pos.toShortString()} " +
                "error=${fmt(target.error)} distance=${fmt(target.distance)} clicks=$clicks skip=$lastSkip suppressedEvents=$it"
        }
    }

    /** One line when the mode or the lever changes, so the log shows what was helped and when. */
    private fun noteState(state: String, target: Candidate?) {
        val line = if (target == null) state else "$state ${target.pos.toShortString()}"
        if (line == lastState) return
        lastState = line
        diagnostics.event(
            "STATE $line" + (target?.let { " error=${fmt(it.error)} distance=${fmt(it.distance)}" } ?: "") +
                " section=${currentSection() + 1}",
        )
    }

    private fun currentSection(): Int {
        val player = Minecraft.getInstance().player ?: return -1
        return TerminalEspRules.playerSection(player.x, player.y, player.z)
    }

    /** Every unclicked lever in reach, with how far off the crosshair it is. */
    private fun candidates(player: LocalPlayer, level: ClientLevel): List<Candidate> {
        val eye = player.eyePosition
        val yaw = player.yRot.toDouble()
        val pitch = player.xRot.toDouble()
        val maxReach = reach.value().toDouble()
        val found = ArrayList<Candidate>()
        var seen = 0
        var skip = ""
        for (entity in level.entitiesForRendering()) {
            val stand = entity as? ArmorStand ?: continue
            if (stand.customName?.string != STAND_NAME) continue
            seen++
            if (stand.distanceToSqr(eye) > (maxReach + 2.0) * (maxReach + 2.0)) continue
            val pos = leverBlock(level, stand)
            if (pos == null) {
                skip = "no-lever-block"
                continue
            }
            val state = level.getBlockState(pos)
            if (state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED)) {
                skip = "already-pulled"
                continue
            }
            val shape = state.getShape(level, pos)
            val center = if (shape.isEmpty) Vec3.atCenterOf(pos) else shape.bounds().center.add(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
            val distance = eye.distanceTo(center)
            if (distance > maxReach) {
                skip = "out-of-reach"
                continue
            }
            val dx = center.x - eye.x
            val dy = center.y - eye.y
            val dz = center.z - eye.z
            val error = LeverAimRules.angularDistance(yaw, pitch, LeverAimRules.yawTo(dx, dz), LeverAimRules.pitchTo(dx, dy, dz))
            found.add(Candidate(pos, center, error, distance))
        }
        stands = seen
        candidates = found.size
        if (skip != lastSkip) {
            lastSkip = skip
            if (skip.isNotEmpty()) diagnostics.event("SKIP reason=$skip stands=$seen")
        }
        return found
    }

    /** The lever block of a stand: the nearest lever in the 3x3x3 blocks around it. */
    private fun leverBlock(level: ClientLevel, stand: ArmorStand): BlockPos? {
        val base = BlockPos.containing(stand.x, stand.y, stand.z)
        val levers = ArrayList<IntArray>()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val pos = base.offset(dx, dy, dz)
            if (level.getBlockState(pos).block === Blocks.LEVER) levers.add(intArrayOf(pos.x, pos.y, pos.z))
        }
        val nearest = LeverAimRules.nearestBlock(stand.x, stand.y, stand.z, levers.toTypedArray()) ?: return null
        return BlockPos(nearest[0], nearest[1], nearest[2])
    }

    /** Once per frame: the pull's share of the turn, where the mouse is read. */
    private fun pullFrame(player: LocalPlayer, deltaSeconds: Float) {
        val point = pullPoint ?: return
        if (!isEnabled() || mode != Mode.PULL) return
        val eye = player.eyePosition
        val dx = point.x - eye.x
        val dy = point.y - eye.y
        val dz = point.z - eye.z
        val errYaw = LeverAimRules.wrap(LeverAimRules.yawTo(dx, dz) - player.yRot)
        val errPitch = LeverAimRules.pitchTo(dx, dy, dz) - player.xRot
        val step = pull.step(
            targetKey, errYaw, errPitch, deltaSeconds.toDouble(), pullStrength.value() / 100.0, pullSpeed.value().toDouble(),
        )
        if (step[0] != 0.0 || step[1] != 0.0) player.turn(step[0] / TURN_SCALE, step[1] / TURN_SCALE)
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)

    companion object {
        /** The floating name Hypixel gives the armor stand over a lever that still has to be pulled. */
        private const val STAND_NAME = "Not Activated"
        private const val LOCK_NAME = "lever-aim"

        /** `Entity.turn` multiplies what it is given by this before applying it. */
        private const val TURN_SCALE = 0.15
        private val spec = ModuleSettingsSpec.of("Lever Aim Assist")

        @JvmField
        val INSTANCE = LeverAimAssistModule()
    }
}
