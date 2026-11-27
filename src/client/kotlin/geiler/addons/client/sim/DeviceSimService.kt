package geiler.addons.client.sim

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.f7.F7Support
import geiler.addons.client.dungeon.f7.PersonalBestFile
import geiler.addons.client.dungeon.f7.PersonalBests
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.module.Category
import geiler.addons.client.module.impl.DeviceSimulatorModule
import geiler.addons.client.module.impl.I4HelperModule
import geiler.addons.client.module.impl.ModuleSounds
import geiler.addons.client.module.impl.SimonSaysModule
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.Random
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Runs one device simulator at a time on client-side blocks. Nothing here sends a packet: clicks are taken before the
 * game acts on them and [DeviceSimGuard] drops whatever would still leave. Client ticks stand in for server ticks.
 */
object DeviceSimService {
    enum class Kind(val id: String, val title: String) { SS("ss", "Simon Says"), I4("i4", "i4") }

    private class Delayed(val due: Long, val action: () -> Unit)

    private class Session(
        val kind: Kind,
        val level: ClientLevel,
        val spot: DeviceSimStore.Spot,
        val blocks: FakeBlocks,
        val pingMillis: Long,
    ) {
        val simon = if (kind == Kind.SS) SimonDevice(Random()) else null
        val target = if (kind == Kind.I4) TargetDevice(Random()) else null
        val delayed = ArrayList<Delayed>()
        var ticks = 0L
        val cooldown = ShotCooldown()
        /** The start button stays down until this tick. */
        var startDownUntil = 0L
        val frame = if (kind == Kind.SS) DeviceSimGeometry.ssFrame(spot.pos, spot.facing)
        else DeviceSimGeometry.i4Frame(spot.pos, spot.facing)
        var startNanos = 0L
        var shots = 0
        val pingTicks = ((pingMillis + 25) / 50).toInt()
        /** Last Simon Says use: a second use of the same block inside the button delay is one click, not two. */
        var lastUsePos: net.minecraft.core.BlockPos? = null
        var lastUseTick = -100L
    }

    private const val LOG = "Device_Simulator"
    private const val STATUS_TICKS = 100L
    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

    private var session: Session? = null
    private val random = Random()
    private val bests by lazy { PersonalBestFile("device-sim-personal-bests.json", emptyMap()) }

    private fun log(line: String) = GeilerAddonsLog.write(Category.F7, LOG, session?.ticks ?: 0L, line)

    private fun say(text: String) = F7Support.message(text)

    private fun singleplayer(): Boolean = Minecraft.getInstance().hasSingleplayerServer()

    private fun allowedHere(useOnHypixel: Boolean): Boolean = DeviceSimGeometry.allowed(
        singleplayer(), HypixelModApi.currentIsland(), HypixelModApi.hasLocation(), useOnHypixel,
    )

    private fun spotKey(): String {
        val server = Minecraft.getInstance().singleplayerServer
        return if (server != null) "world:${server.worldData.levelName}" else "hypixel:private_island"
    }

    private fun refusal(): String =
        "§cOnly in singleplayer, or on your own Private Island with Use On Hypixel on in Device Simulator."

    fun setPosition() {
        val player = Minecraft.getInstance().player ?: return
        if (!allowedHere(true)) {
            say("§cSet the spot in singleplayer or on your own Private Island.")
            return
        }
        stop("position changed")
        val spot = DeviceSimStore.Spot(player.blockPosition(), player.direction)
        DeviceSimStore.put(spotKey(), spot)
        say("§aSimulator spot saved: you stand here, facing ${player.direction.serializedName}.")
        log("SETPOS facing=${player.direction.serializedName}")
    }

    fun start(kind: Kind, pingMillis: Long) {
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        client.player ?: return
        stop("restart")
        if (!allowedHere(DeviceSimulatorModule.INSTANCE.useOnHypixel())) {
            say(refusal())
            return
        }
        val spot = DeviceSimStore.get(spotKey())
        if (spot == null) {
            say("§cNo spot yet. Stand where you want to practice, face the wall and run §e/ga sim setpos§c.")
            return
        }
        val blocks = FakeBlocks(object : FakeBlocks.World {
            override fun get(pos: BlockPos): BlockState = level.getBlockState(pos)
            override fun set(pos: BlockPos, state: BlockState) {
                level.setBlock(pos, state, FLAGS)
            }
        })
        val reserved = if (kind == Kind.SS) DeviceSimGeometry.ssReserved(spot.pos, spot.facing)
        else DeviceSimGeometry.targetReserved(spot.pos, spot.facing)
        if (reserved.any { !level.hasChunkAt(it) }) {
            say("§cThe saved spot is not loaded. Move closer or set a new one.")
            return
        }
        // The i4 wall may cover the ground (its bottom row is below your feet), but nothing that holds data.
        val free = if (kind == Kind.SS) blocks.free(reserved)
        else blocks.free(listOf(spot.pos)) && reserved.none { level.getBlockState(it).hasBlockEntity() }
        if (!free) {
            say(
                if (kind == Kind.SS) "§cNot enough room: the simulator only replaces air. Clear the space or set a new spot."
                else "§cThe plate spot must be air, and no chest or other block with contents may be where the wall goes.",
            )
            log("REFUSED reason=space_not_free kind=${kind.id}")
            return
        }
        val created = Session(kind, level, spot, blocks, pingMillis.coerceIn(0L, MAX_PING))
        session = created
        DeviceSimGuard.arm(true)
        val solverOn = if (kind == Kind.SS) {
            SimonSaysModule.INSTANCE.onSimStart()
            SimonSaysModule.INSTANCE.isEnabled()
        } else {
            I4HelperModule.INSTANCE.onSimStart()
            I4HelperModule.INSTANCE.isEnabled()
        }
        sync(created)
        say(
            "§a${kind.title} sim started (ping ${created.pingMillis} ms). Your clicks are not sent to the server while " +
                "it runs. Do not stand on the sim blocks. §e/ga sim stop§a ends it.",
        )
        if (kind == Kind.I4) say("§7Stand on the gold plate. The bow is simulated: left or right click shoots.")
        if (!solverOn) say("§7Turn on ${if (kind == Kind.SS) "Simon Says" else "i4 helper"} to see its solver here.")
        log("START kind=${kind.id} ping=${created.pingMillis} singleplayer=${singleplayer()} solver=$solverOn")
    }

    /** How Floor 7 coordinates map onto the running [kind] sim, or null when that sim is not running. */
    fun frame(kind: Kind): DeviceSimGeometry.Frame? = session?.takeIf { it.kind == kind }?.frame

    /** Takes the blocks out and lets clicks through again. Safe to call at any time. */
    fun stop(reason: String, announce: Boolean = false) {
        val current = session ?: return
        log("STOP reason=$reason shots=${current.shots} blockedPackets=${DeviceSimGuard.blockedCount}")
        // A world that was left takes its blocks with it.
        if (Minecraft.getInstance().level === current.level) current.blocks.restore() else current.blocks.forget()
        session = null
        DeviceSimGuard.arm(false)
        if (announce) say("§7${current.kind.title} sim stopped: $reason.")
    }

    fun running(): Boolean = session != null

    fun tick() {
        val current = session ?: return
        val client = Minecraft.getInstance()
        val player = client.player
        if (client.level !== current.level || player == null) {
            stop("world changed")
            return
        }
        if (!allowedHere(DeviceSimulatorModule.INSTANCE.useOnHypixel())) {
            stop("you left the allowed area", announce = true)
            return
        }
        if (standsOnSimBlock(current, player)) {
            stop("you stood on a sim block, which the server does not have", announce = true)
            return
        }
        current.ticks++
        // Presses and arrows arriving this tick come before the device's own tick, as on the server.
        val due = current.delayed.filter { it.due <= current.ticks }
        current.delayed.removeAll(due.toSet())
        for (event in due) {
            if (session !== current) return
            event.action()
        }
        if (session !== current) return
        if (current.target != null && current.cooldown.tick(current.ticks, DeviceSimulatorModule.INSTANCE.cooldownTicks())) {
            fire(current, held = true)
        }
        current.simon?.tick()
        current.target?.let { device ->
            val before = device.lit
            device.tick(onPlate(current, player))
            if (before < 0 && device.lit >= 0 && device.hits == 0) current.startNanos = System.nanoTime()
        }
        sync(current)
        if (current.ticks % STATUS_TICKS == 0L) {
            log(
                "STATUS kind=${current.kind.id} round=${current.simon?.round} hits=${current.target?.hits} " +
                    "shots=${current.shots} blockedPackets=${DeviceSimGuard.blockedCount}",
            )
        }
    }

    private fun standsOnSimBlock(current: Session, player: LocalPlayer): Boolean {
        val below = BlockPos(floor(player.x).toInt(), floor(player.y - 0.2).toInt(), floor(player.z).toInt())
        return current.blocks.owns(below)
    }

    private fun onPlate(current: Session, player: LocalPlayer): Boolean {
        val plate = AABB(current.spot.pos)
        val box = player.boundingBox
        return box.maxX > plate.minX && box.minX < plate.maxX && box.maxZ > plate.minZ && box.minZ < plate.maxZ &&
            player.y >= plate.minY - 0.01 && player.y < plate.minY + 0.25
    }

    private fun button(facing: Direction, pressed: Boolean): BlockState =
        Blocks.STONE_BUTTON.defaultBlockState()
            .setValue(ButtonBlock.FACE, AttachFace.WALL)
            .setValue(ButtonBlock.FACING, facing.opposite)
            .setValue(ButtonBlock.POWERED, pressed)

    private fun sync(current: Session) {
        // A press that just finished the run has already taken the blocks out; drawing again would leave them behind.
        if (session !== current) return
        val spot = current.spot
        val air = Blocks.AIR.defaultBlockState()
        current.simon?.let { device ->
            current.blocks.set(DeviceSimGeometry.ssStartBacking(spot.pos, spot.facing), Blocks.OBSIDIAN.defaultBlockState())
            current.blocks.set(
                DeviceSimGeometry.ssStart(spot.pos, spot.facing),
                button(spot.facing, current.ticks < current.startDownUntil),
            )
            for (cell in 0 until SimonDevice.CELLS) {
                current.blocks.set(
                    DeviceSimGeometry.ssLamp(spot.pos, spot.facing, cell),
                    if (device.lit(cell)) Blocks.SEA_LANTERN.defaultBlockState() else Blocks.OBSIDIAN.defaultBlockState(),
                )
                current.blocks.set(
                    DeviceSimGeometry.ssButton(spot.pos, spot.facing, cell),
                    if (device.buttonUp(cell)) button(spot.facing, device.buttonPressed(cell)) else air,
                )
            }
        }
        current.target?.let { device ->
            current.blocks.set(spot.pos, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE.defaultBlockState())
            val blue = Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE).defaultBlockState()
            for (cell in 0 until TargetDevice.CELLS) {
                val pos = DeviceSimGeometry.targetPos(spot.pos, spot.facing, cell)
                val before = current.level.getBlockState(pos)
                current.blocks.replace(pos, if (device.lit == cell) Blocks.EMERALD_BLOCK.defaultBlockState() else blue)
                val after = current.level.getBlockState(pos)
                // No server packet announces these blocks, so the solver is told here.
                if (before != after) I4HelperModule.INSTANCE.onBlockChange(pos, after)
            }
        }
    }

    private fun later(current: Session, ticks: Int, action: () -> Unit) {
        current.delayed.add(Delayed(current.ticks + ticks.coerceAtLeast(1), action))
    }

    /** A right click while a sim runs. Returns true when the game must not act on it. */
    @JvmStatic
    fun onUse(): Boolean {
        val current = session ?: return false
        if (current.kind == Kind.I4) {
            shoot(current)
            return true
        }
        val hit = Minecraft.getInstance().hitResult
        if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) return true
        // Simulated presses (Auto Simon Says) could reach startUseItem twice for one click; vanilla's 4-tick button delay
        // means a real second press of the same block never comes this fast.
        if (hit.blockPos == current.lastUsePos && current.ticks - current.lastUseTick < 4) {
            log("USE_DEBOUNCED gapTicks=${current.ticks - current.lastUseTick}")
            return true
        }
        current.lastUsePos = hit.blockPos.immutable()
        current.lastUseTick = current.ticks
        // The solver's own click blocking: this mixin runs before the callback it normally uses.
        if (SimonSaysModule.INSTANCE.onBlockUse(hit.blockPos)) return true
        val spot = current.spot
        val sentAt = System.currentTimeMillis()
        val start = DeviceSimGeometry.ssStart(spot.pos, spot.facing)
        if (hit.blockPos == start) {
            ModuleSounds.play(CLICK_SOUND, 0.6f, 1f)
            // The client shows a press at once; the off-to-on change is what the solver reads as start or reset.
            current.startDownUntil = current.ticks + START_BUTTON_TICKS
            current.blocks.set(start, button(spot.facing, false))
            current.blocks.set(start, button(spot.facing, true))
            afterPing(current) { pressStart(current, sentAt + current.pingMillis) }
            return true
        }
        val cell = (0 until SimonDevice.CELLS).firstOrNull {
            hit.blockPos == DeviceSimGeometry.ssButton(spot.pos, spot.facing, it)
        } ?: return true
        afterPing(current) { pressCell(current, cell) }
        return true
    }

    /** A left click while a sim runs: it shoots in i4 and does nothing in Simon Says. */
    @JvmStatic
    fun onAttack(): Boolean {
        val current = session ?: return false
        if (current.kind == Kind.I4) shoot(current)
        return true
    }

    @JvmStatic
    fun blocksMining(): Boolean = session != null

    /** Ticks the held right button waits: vanilla's 4 for buttons; none for the bow, which has its own cooldown. */
    @JvmStatic
    fun useDelay(): Int = if (session?.kind == Kind.SS) 4 else 0

    private fun afterPing(current: Session, action: () -> Unit) {
        // Without ping the press lands at once, like a click handled in the current tick.
        if (current.pingTicks <= 0) {
            action()
            sync(current)
        } else {
            later(current, current.pingTicks, action)
        }
    }

    private fun pressStart(current: Session, arrivalMillis: Long) {
        val device = current.simon ?: return
        if (device.startPress(arrivalMillis) && current.startNanos == 0L) current.startNanos = System.nanoTime()
    }

    private fun pressCell(current: Session, cell: Int) {
        val device = current.simon ?: return
        when (device.press(cell)) {
            SimonDevice.Press.NONE -> Unit
            SimonDevice.Press.DONE -> finish(current)
            SimonDevice.Press.WRONG -> {
                ModuleSounds.play("minecraft:entity.villager.no", 1f, 1f)
                say("§cWrong button.")
                // The run restarts from the new two lamp round the device shows by itself.
                current.startNanos = System.nanoTime()
                log("SS_WRONG cell=$cell")
            }
            else -> ModuleSounds.play("minecraft:block.note_block.pling", 1f, 2f)
        }
    }

    private fun shoot(current: Session) {
        if (current.cooldown.click(current.ticks, DeviceSimulatorModule.INSTANCE.cooldownTicks())) fire(current, held = false)
    }

    private fun fire(current: Session, held: Boolean) {
        val player = Minecraft.getInstance().player ?: return
        current.shots++
        val spot = current.spot
        val boxes = List(TargetDevice.CELLS) { AABB(DeviceSimGeometry.targetPos(spot.pos, spot.facing, it)) }
        val noise = Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).scale(FakeArrow.AIM_NOISE)
        ModuleSounds.play("minecraft:entity.arrow.shoot", 0.4f, 1.2f)
        val landed = ArrayList<String>()
        val volley = FakeArrow.volley(
            player.eyePosition, player.yRot, player.xRot, noise, DeviceSimulatorModule.INSTANCE.terminator(),
        )
        for (arrow in volley) {
            val flight = FakeArrow.fly(arrow, boxes)
            // One particle per move, on the tick the arrow makes it, so you see its speed.
            flight.path.forEachIndexed { move, point ->
                val wait = arrow.delay + move
                if (wait == 0) trail(current, point) else later(current, wait) { trail(current, point) }
            }
            if (flight.target < 0) continue
            landed.add("${flight.target}@${flight.ticks}")
            later(current, current.pingTicks + flight.ticks) { arrowHit(current, flight.target) }
        }
        log("SHOT n=${current.shots} held=$held arrows=${volley.size} cellAtTick=$landed")
    }

    private fun trail(current: Session, point: Vec3) =
        current.level.addParticle(ParticleTypes.CRIT, point.x, point.y, point.z, 0.0, 0.0, 0.0)

    private fun arrowHit(current: Session, cell: Int) {
        val device = current.target ?: return
        if (!device.hit(cell)) return
        ModuleSounds.play("minecraft:entity.arrow.hit_player", 0.6f, 1.4f)
        if (device.done) finish(current)
    }

    private fun finish(current: Session) {
        val seconds = if (current.startNanos == 0L) 0f
        else ((System.nanoTime() - current.startNanos) / 1_000_000L) / 1000f
        sync(current)
        if (current.kind == Kind.I4) I4HelperModule.INSTANCE.onSimDone()
        val outcome = bests.record(current.kind.name, seconds)
        val old = if (outcome.old >= PersonalBests.NONE) "none" else String.format(Locale.ROOT, "%.2fs", outcome.old)
        val tail = if (outcome.newBest) "§7(§d§lNew PB§7, old: $old)" else "§8(PB $old)"
        say("§a${current.kind.title} sim done in §6${String.format(Locale.ROOT, "%.2f", seconds)}s $tail")
        log("DONE kind=${current.kind.id} seconds=${(seconds * 1000).roundToInt()}ms newBest=${outcome.newBest}")
        stop("finished")
    }

    private const val MAX_PING = 1000L
    private const val START_BUTTON_TICKS = 20
    private const val CLICK_SOUND = "minecraft:block.stone_button.click_on"
}
