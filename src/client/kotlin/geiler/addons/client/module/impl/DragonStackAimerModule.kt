package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.f7.AimDragon
import geiler.addons.client.dungeon.f7.DragonAimerLogic
import geiler.addons.client.dungeon.f7.DragonSpawnQueue
import geiler.addons.client.dungeon.f7.F7Support
import geiler.addons.client.dungeon.f7.PingTracker
import geiler.addons.client.dungeon.f7.ProjectileSolver
import geiler.addons.client.dungeon.odin.OdinDungeonPlayerRoster
import geiler.addons.client.hud.HudElement
import geiler.addons.client.mixin.LocalPlayerLastPositionAccessor
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.Pose
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shows where to aim an arrow stack (or an archer or berserk run) for the next M7 dragon, with a box and tracer at
 * the aim direction and a timer for when to start running (Devonian's Dragon Stack Aimer, GPL-3.0). Behavior
 * and settings follow it; the code, solver and ping tracker are written independently.
 */
class DragonStackAimerModule private constructor(private val s: Settings) : Module(
    "Dragon Stack Aimer",
    "Shows where to aim for arrow stack or arch/bers.",
    Category.F7,
    false,
    s.accountForPing, s.hudDisplay, s.timerOffset, s.aimColor, s.tracerColor,
) {
    private val ping = PingTracker()
    private var lastPingSent = 0L
    private var spawned: DragonSpawnQueue.Spawn? = null
    private var done = false
    private var previousBest = 0
    private var data: ProjectileSolver.Data? = null
    private var hudText: String? = null
    private val alive = HashMap<Int, AimDragon>()
    private var lastLevel: Any? = null
    private var lastServerTicks = -1L
    private var solved = 0

    private val listener: DragonSpawnQueue.Listener = object : DragonSpawnQueue.Listener {
        override fun activeChanged(spawn: DragonSpawnQueue.Spawn) {
            diagnostics.event("ACTIVE dragon=${spawn.type.label} high=${spawn.high} spawnTick=${spawn.spawnTick}")
            if (!done) spawned = spawn
        }

        override fun dead(dragon: AimDragon) {
            diagnostics.event("DEAD dragon=${dragon.label}")
            if (spawned?.type == dragon) spawned = null
        }
    }
    private val queue: DragonSpawnQueue = DragonSpawnQueue(listener)
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 10, 100L, 320)

    override fun onEnable() {
        reset()
        diagnostics.enable(
            "ping=${s.accountForPing.value()} hud=${s.hudDisplay.value()} offset=${s.timerOffset.intValue()} " +
                "aim=${s.aimColor.hex()} tracer=${s.tracerColor.hex()}",
        )
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        queue.reset()
        alive.clear()
        spawned = null
        done = false
        previousBest = 0
        data = null
        hudText = null
        lastServerTicks = -1L
        ping.clear()
    }

    /** Pong response from the server; [echoedNanos] is the value we sent in the ping request. */
    fun onPong(echoedNanos: Long) {
        val rtt = (System.nanoTime() - echoedNanos) * 1e-6
        ping.add(rtt, System.currentTimeMillis())
    }

    private fun selfClassAllowed(): Boolean {
        val name = Minecraft.getInstance().player?.gameProfile?.name ?: return true
        val entry = OdinDungeonPlayerRoster.snapshot().firstOrNull { it.name().equals(name, ignoreCase = true) }
        val dungeonClass = entry?.let { DungeonClass.parse(it.dungeonClass()) } ?: return true
        return dungeonClass == DungeonClass.ARCHER || dungeonClass == DungeonClass.BERSERK
    }

    private fun active() = isEnabled() && F7Support.inPhase5() && selfClassAllowed()

    fun onChatMessage(plain: String) {
        if (isEnabled() && plain == WITHER_KING_DEAD) {
            done = true
            spawned = null
            data = null
        }
    }

    fun onParticle(packet: ClientboundLevelParticlesPacket) {
        if (!active()) return
        queue.onParticle(
            packet.x, packet.y, packet.z, packet.count, packet.xDist, packet.yDist, packet.zDist, packet.maxSpeed,
            packet.particle.type == ParticleTypes.FLAME, DungeonOdinAssistModule.INSTANCE.serverTicks().toInt(),
        )
    }

    fun onEntityAdded(packet: ClientboundAddEntityPacket) {
        if (!active() || packet.type != EntityTypes.ENDER_DRAGON) return
        val type = queue.nearestType(packet.x, packet.y, packet.z)
        if (queue.queued.any { it.type == type }) alive[packet.id] = type
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level !== lastLevel) {
            lastLevel = level
            reset()
            diagnostics.event("WORLD_CHANGED aimerReset=true")
        }
        val now = DungeonOdinAssistModule.INSTANCE.serverTicks()
        if (lastServerTicks >= 0L && now < lastServerTicks) {
            // The run clock restarted (dungeon context blip): stored spawn ticks belong to the old clock.
            diagnostics.event("TICK_CLOCK_RESTARTED was=$lastServerTicks now=$now queued=${queue.queued.size}")
            queue.reset()
            spawned = null
            data = null
        }
        val delta = if (lastServerTicks < 0L || now < lastServerTicks) 0L else now - lastServerTicks
        lastServerTicks = now
        val enabledHere = active()
        if (enabledHere && System.currentTimeMillis() - lastPingSent > PING_INTERVAL_MILLIS) {
            lastPingSent = System.currentTimeMillis()
            mc.connection?.send(ServerboundPingRequestPacket(System.nanoTime()))
        }
        val iterator = alive.entries.iterator()
        while (iterator.hasNext()) {
            val (id, type) = iterator.next()
            val entity = level?.getEntity(id) as? EnderDragon
            if (entity == null || entity.isRemoved || entity.dragonDeathTime > 0) {
                iterator.remove()
                queue.onDragonGone(type)
            }
        }
        if (!enabledHere) {
            hudText = null
            return
        }
        if (delta > 0L) solve(now)
        updateHud(now)
        diagnostics.status {
            "active=true spawned=${spawned?.type?.label} high=${spawned?.high} queued=${queue.queued.size} " +
                "ping=${"%.1f".format(Locale.ROOT, ping.median(System.currentTimeMillis()))} " +
                "flight=${data?.ticks?.let { "%.2f".format(Locale.ROOT, it) }} solved=$solved suppressedEvents=$it"
        }
    }

    private fun solve(serverTicks: Long) {
        val spawn = spawned ?: return
        var ttl = (spawn.spawnTick - serverTicks).toDouble()
        if (ttl < -30.0 || ttl > DragonAimerLogic.MAX_REMAINING_TICKS) {
            data = null
            return
        }
        if (s.accountForPing.value()) ttl -= ping.median(System.currentTimeMillis()) / 50.0
        val player = Minecraft.getInstance().player ?: return
        val last = player as LocalPlayerLastPositionAccessor
        val px = last.`geileraddons$getXLast`()
        val py = last.`geileraddons$getYLast`() + (if (player.pose == Pose.CROUCHING) 1.54 else player.eyeHeight.toDouble()) -
            if (spawn.high) 8.0 else 0.0
        val pz = last.`geileraddons$getZLast`()
        val aim = ProjectileSolver.aim(ttl, spawn.type.path, previousBest, 0.001, -0.05, 3.0, 0.99, false, px, py, pz)
        previousBest = aim.index
        data = aim.data
        solved++
    }

    private fun updateHud(serverTicks: Long) {
        hudText = null
        val spawn = spawned ?: return
        val aim = data ?: return
        if (!s.hudDisplay.value() || !aim.valid) return
        val remaining = (spawn.spawnTick - serverTicks).toInt()
        val pingMillis = if (s.accountForPing.value()) ping.median(System.currentTimeMillis()) else 0.0
        hudText = DragonAimerLogic.hudText(remaining, aim.ticks, pingMillis, s.timerOffset.intValue())
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || spawned == null) return
        val aim = data?.takeIf { it.valid } ?: return
        if (!active()) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera().position()
        val x = 50.0 * sin(aim.phi) * cos(aim.theta)
        val y = 50.0 * cos(aim.phi)
        val z = 50.0 * sin(aim.phi) * sin(aim.theta)
        val buffers = WorldRenderBuffer.current()
        val pose = context.poseStack()
        EspRenderer.renderBox(pose, buffers, x - 0.5, y - 0.5, z - 0.5, 1.0, 1.0, 1.0, s.aimColor.argb(), 0, 1f, false)
        val target = Vec3(camera.x + x, camera.y + y, camera.z + z)
        val endpoints = BlockEspTracerGeometry.endpoints(camera, client.gameRenderer.mainCamera().forwardVector(), target, 0.5)
        EspRenderer.renderLine(
            pose, buffers, endpoints.start.x, endpoints.start.y, endpoints.start.z,
            endpoints.end.x, endpoints.end.y, endpoints.end.z, s.tracerColor.argb(), 2.5f, false,
        )
        GeilerAddonsRenderTypes.endBatches(buffers)
    }

    val hudElement: HudElement = object : HudElement {
        override fun hudIslands(): Set<Island> = setOf(Island.CATACOMBS)

        override fun id(): String = "dragon_stack_aimer"
        override fun module(): Module = this@DragonStackAimerModule
        override fun displayName(): String = "Dragon Stack Aimer"
        override fun width(font: Font?): Int = font!!.width(hudText ?: SAMPLE)
        override fun height(font: Font?): Int = font!!.lineHeight
        override fun previewWidth(font: Font?): Int = font!!.width(SAMPLE)
        override fun visible(): Boolean = isEnabled() && hudText != null
        override fun previewMode(): String = if (visible()) "live" else "sample"
        override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
            graphics.text(font, Component.literal(hudText ?: return), x, y, WHITE)
        }

        override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
            graphics.text(font, Component.literal(hudText ?: SAMPLE), x, y, WHITE)
        }
    }

    class Settings {
        val accountForPing = BooleanSetting("Account for Ping", true)
        val hudDisplay = BooleanSetting("HUD Display", true)
        val timerOffset = NumberSetting("Run Timer Offset", -2000f, 2000f, 0f, true)
        val aimColor = ColorSetting("Aim Color", 0, 255, 255, 255)
        val tracerColor = ColorSetting("Aim Tracer Color", 0, 255, 255, 255)
    }

    companion object {
        private const val PING_INTERVAL_MILLIS = 2_000L
        private const val WITHER_KING_DEAD = "[BOSS] Wither King: Incredible. You did what I couldn't do myself."
        private const val SAMPLE = "§62500"
        private const val WHITE = 0xFFFFFFFF.toInt()

        @JvmField
        val INSTANCE = DragonStackAimerModule(Settings())
    }
}
