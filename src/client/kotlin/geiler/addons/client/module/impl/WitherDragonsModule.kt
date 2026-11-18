package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.F7Support
import geiler.addons.client.dungeon.f7.PersonalBestFile
import geiler.addons.client.dungeon.f7.WitherDragon
import geiler.addons.client.dungeon.f7.WitherDragonState
import geiler.addons.client.dungeon.f7.WitherDragonTracker
import geiler.addons.client.hud.HudElement
import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.UUID

/**
 * M7 dragon timers, boxes, priority tracer, health and chat alerts (Odin's Wither Dragons, BSD 3-Clause;
 * settings and rules follow it, the code is written independently).
 */
class WitherDragonsModule private constructor(private val s: Settings) : Module(
    "Wither Dragons",
    "Tools for managing M7 dragons timers, boxes, priority, health and alerts.",
    Category.F7,
    false,
    s.dragonTimer, s.timerStyle, s.timerSymbol, s.timerHud,
    s.dragonBoxes, s.statueBoxes, s.targetTracer,
    s.sendConfirmation, s.dragonTitle, s.sendTime, s.sendSpawned, s.sendSpray,
    s.dragonHealth,
) {
    private val bests = PersonalBestFile("wither-dragon-personal-bests.json", mapOf(
        "Red" to 1.0f, "Green" to 1.05f, "Purple" to 0.95f, "Orange" to 1.4f,
    ))
    private val listener: WitherDragonTracker.Listener = object : WitherDragonTracker.Listener {
        override fun spawning(dragon: WitherDragon) {
            diagnostics.event("SPAWNING dragon=${dragon.name} order=${tracker.spawnOrder.map { it.name }}")
            if (s.dragonTitle.value()) alert("§${dragon.colorCode}${dragon.name} is spawning!")
        }

        override fun spawned(dragon: WitherDragon, timesSpawned: Int) {
            diagnostics.event("SPAWNED dragon=${dragon.name} times=$timesSpawned tick=${tracker.currentTick}")
            if (s.sendSpawned.value()) F7Support.message("§${dragon.colorCode}${dragon.name} §fdragon spawned §8(§7$timesSpawned§8)")
        }

        override fun died(dragon: WitherDragon, ticksAlive: Long, realTime: Boolean) {
            diagnostics.event("DIED dragon=${dragon.name} ticksAlive=$ticksAlive real=$realTime")
            if (s.sendTime.value() && realTime) {
                val seconds = ticksAlive / 20f
                val outcome = bests.record(dragon.name, seconds)
                val tail = if (outcome.newBest) "§7(§d§lNew PB§r§7) Old PB was §8${outcome.old}" else "§8(§7${outcome.old}§8)"
                F7Support.message("§${dragon.colorCode}${dragon.name} §7was alive for §6${seconds}s§7! $tail")
            }
        }

        override fun counts(dragon: WitherDragon) {
            diagnostics.event(
                "COUNTS dragon=${dragon.name} source=${tracker.lastCountSource} pending=${tracker.pendingCountList.map { it.name }}",
            )
            if (s.sendConfirmation.value()) F7Support.message("§${dragon.colorCode}${dragon.name} dragon counts.")
        }

        override fun sprayed(dragon: WitherDragon, ticksAfterSpawn: Long) {
            diagnostics.event("SPRAYED dragon=${dragon.name} ticks=$ticksAfterSpawn")
            if (s.sendSpray.value()) {
                val unit = "$ticksAfterSpawn §ftick${if (ticksAfterSpawn > 1) "s" else ""}"
                F7Support.message("§${dragon.colorCode}${dragon.name} §fdragon was sprayed in §c$unit.")
            }
        }
    }
    private val tracker: WitherDragonTracker = WitherDragonTracker(listener)
    private val health = HashMap<Int, Float>()
    private val idByUuid = HashMap<UUID, Int>()
    private var lastLevel: Any? = null
    private var lastServerTicks = -1L
    private var labelsDrawn = 0
    private var statuesDrawn = 0L
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 12, 100L, 320)

    init {
        group(
            SettingGroup.switched("Dragon Timer", s.dragonTimer, s.timerStyle, s.timerSymbol, s.timerHud),
            SettingGroup("Display", s.dragonBoxes, s.statueBoxes, s.targetTracer, s.dragonHealth),
            SettingGroup("Dragon Alerts", s.sendConfirmation, s.dragonTitle, s.sendTime, s.sendSpawned, s.sendSpray),
        )
    }

    override fun isSettingVisible(setting: Setting): Boolean =
        (setting !== s.timerStyle && setting !== s.timerSymbol && setting !== s.timerHud) || s.dragonTimer.value()

    override fun onEnable() {
        resetState()
        diagnostics.enable(
            "timer=${s.dragonTimer.value()} style=${s.timerStyle.value()} boxes=${s.dragonBoxes.value()} statues=${s.statueBoxes.value()} " +
                "tracer=${s.targetTracer.value()} health=${s.dragonHealth.value()} title=${s.dragonTitle.value()}",
        )
    }

    override fun onDisable() {
        resetState()
        diagnostics.disable()
    }

    private fun resetState() {
        tracker.reset()
        health.clear()
        idByUuid.clear()
        lastServerTicks = -1L
    }

    private fun active() = isEnabled() && F7Support.inPhase5()

    private fun alert(text: String) {
        TitleAlertStack.submit(name(), text, 40, 0xFFFFFFFF.toInt())
        ModuleSounds.play("minecraft:block.note_block.pling", 1f, 1f)
    }

    fun onParticle(packet: ClientboundLevelParticlesPacket) {
        if (!active()) return
        tracker.onSpawnParticle(
            packet.x, packet.y, packet.z, packet.count, packet.xDist, packet.yDist, packet.zDist, packet.maxSpeed,
            packet.particle.type == ParticleTypes.FLAME,
        )
    }

    fun onEntityAdded(packet: ClientboundAddEntityPacket) {
        if (!active() || packet.type != EntityTypes.ENDER_DRAGON) return
        idByUuid[packet.uuid] = packet.id
        tracker.onEntityAdded(packet.uuid, packet.x, packet.y, packet.z)
    }

    fun onEntityData(packet: ClientboundSetEntityDataPacket) {
        if (!active()) return
        val level = Minecraft.getInstance().level ?: return
        val dragon = level.getEntity(packet.id()) as? EnderDragon ?: return
        val value = packet.packedItems().firstOrNull { it.id() == HEALTH_DATA_ID }?.value() as? Float ?: return
        health[dragon.id] = value
        idByUuid[dragon.uuid] = dragon.id
        tracker.onHealth(dragon.uuid, value)
    }

    fun onEquipment(packet: ClientboundSetEquipmentPacket) {
        if (!active() || packet.slots.none { it.second.`is`(Items.PACKED_ICE) }) return
        val level = Minecraft.getInstance().level ?: return
        val stand = level.getEntity(packet.entity) as? ArmorStand ?: return
        tracker.onIceSpray { uuid ->
            val id = idByUuid[uuid] ?: return@onIceSpray null
            level.getEntity(id)?.let { stand.distanceTo(it).toDouble() }
        }
    }

    fun onBlockUpdate(x: Int, y: Int, z: Int, air: Boolean) {
        if (air && active()) tracker.onBlockAir(x, y, z)
    }

    fun onChatMessage(plain: String) {
        if (!active() || !WITHER_KING.matches(plain)) return
        tracker.onWitherKingLine()
        if (tracker.lastCountSource == "none") diagnostics.event("COUNT_LINE_UNMATCHED order=${tracker.spawnOrder.map { it.name }}")
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val level = Minecraft.getInstance().level
        if (level !== lastLevel) {
            lastLevel = level
            resetState()
            diagnostics.event("WORLD_CHANGED dragonsReset=true")
        }
        val now = DungeonOdinAssistModule.INSTANCE.serverTicks()
        if (lastServerTicks >= 0L && now < lastServerTicks) {
            diagnostics.event("TICK_CLOCK_RESTARTED was=$lastServerTicks now=$now tick=${tracker.currentTick}")
        }
        val delta = if (lastServerTicks < 0L || now < lastServerTicks) 0L else minOf(now - lastServerTicks, 40L)
        lastServerTicks = now
        for (i in 0 until delta) tracker.onServerTick()
        diagnostics.status {
            "phase5=${F7Support.inPhase5()} inDungeon=${DungeonContextTracker.inDungeon()} " +
                "floor=${DungeonContextTracker.currentFloor()?.displayName() ?: "unknown"} " +
                "${DungeonContextTracker.sidebarDiagnostics()} tick=${tracker.currentTick} order=${tracker.spawnOrder.map { it.name }} pendingCounts=${tracker.pendingCountList.map { it.name }} ignoredBursts=${tracker.ignoredBursts} " +
                "tracked=${health.size} labels=$labelsDrawn statueBoxFrames=$statuesDrawn intact=${WitherDragon.entries.count { d -> tracker.statueIntact(d) }} suppressedEvents=$it"
        }
    }

    private fun timerText(ticks: Int): String {
        val color = when {
            ticks <= 20 -> "§c"
            ticks <= 60 -> "§e"
            else -> "§a"
        }
        val symbol = s.timerSymbol.value()
        val body = when (s.timerStyle.value()) {
            "SECONDS" -> String.format(Locale.US, "%.1f", ticks / 20f) + if (symbol) "s" else ""
            "TICKS" -> "$ticks" + if (symbol) "t" else ""
            else -> "${ticks * 50}" + if (symbol) "ms" else ""
        }
        return color + body
    }

    private fun healthText(value: Float): String {
        val color = when {
            value >= 750_000_000 -> "§a"
            value >= 500_000_000 -> "§e"
            value >= 250_000_000 -> "§6"
            else -> "§c"
        }
        val text = when {
            value >= 1_000_000_000 -> String.format(Locale.US, "%.2f", value / 1_000_000_000) + "b"
            value >= 1_000_000 -> String.format(Locale.US, "%.2f", value / 1_000_000) + "m"
            value >= 1_000 -> String.format(Locale.US, "%.2f", value / 1_000) + "k"
            else -> "${value.toInt()}"
        }
        return color + text
    }

    fun render(context: LevelRenderContext) {
        if (!active()) return
        val wantBoxes = s.dragonBoxes.value()
        val wantTracer = s.targetTracer.value()
        val wantStatues = s.statueBoxes.value()
        if (!wantBoxes && !wantTracer && !wantStatues) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera().position()
        val pose: PoseStack = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        var drew = false
        for (dragon in WitherDragon.entries) {
            if (wantBoxes && tracker.entry(dragon).state != WitherDragonState.DEAD) {
                EspRenderer.renderBox(
                    pose, buffers, dragon.minX - camera.x, dragon.minY - camera.y, dragon.minZ - camera.z,
                    dragon.maxX - dragon.minX, dragon.maxY - dragon.minY, dragon.maxZ - dragon.minZ,
                    0, dragon.argb, 2f, true,
                )
                drew = true
            }
            if (wantStatues && tracker.statueIntact(dragon)) {
                // 3x3x3 around the statue block; the real statue size is not verified in game.
                EspRenderer.renderBox(
                    pose, buffers, dragon.statueX - 1 - camera.x, dragon.statueY - 1 - camera.y,
                    dragon.statueZ - 1 - camera.z, 3.0, 3.0, 3.0, 0, dragon.argb, 2f, true,
                )
                drew = true
                statuesDrawn++
            }
        }
        val priority = tracker.priority
        if (wantTracer && priority != null && tracker.entry(priority).state == WitherDragonState.SPAWNING) {
            val target = Vec3(priority.spawnX + 0.5, priority.spawnY + 0.5, priority.spawnZ + 0.5)
            val endpoints = BlockEspTracerGeometry.endpoints(camera, client.gameRenderer.mainCamera().forwardVector(), target, 0.5)
            EspRenderer.renderLine(
                pose, buffers, endpoints.start.x, endpoints.start.y, endpoints.start.z,
                endpoints.end.x, endpoints.end.y, endpoints.end.z, priority.argb, 2f, true,
            )
            drew = true
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    /** Spawn countdown and health labels, projected onto the HUD like the other world text. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!active()) return
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera()
        var drawn = 0
        if (s.dragonHealth.value()) {
            val partial = client.deltaTracker.getGameTimeDeltaPartialTick(true)
            for ((id, value) in health) {
                if (value <= 0f) continue
                val entity = level.getEntity(id) ?: continue
                if (ProjectedLabelRenderer.drawFloatingText(
                        graphics, camera, client.font, entity.getPosition(partial), healthText(value), WHITE, 1.4f,
                    )
                ) drawn++
            }
        }
        if (s.dragonTimer.value()) {
            for (dragon in WitherDragon.entries) {
                val ticks = tracker.entry(dragon).timeToSpawn
                if (ticks <= 0) continue
                val world = Vec3(dragon.spawnX + 0.5, dragon.spawnY + 0.5, dragon.spawnZ + 0.5)
                if (ProjectedLabelRenderer.drawFloatingText(
                        graphics, camera, client.font, world, "§${dragon.colorCode}${dragon.name.first()}: ${timerText(ticks)}",
                        WHITE, 1.4f,
                    )
                ) drawn++
            }
        }
        labelsDrawn = drawn
    }

    private fun hudLine(): String? {
        val dragon = tracker.nextToSpawn() ?: return null
        val ticks = tracker.entry(dragon).timeToSpawn
        return "§${dragon.colorCode}${dragon.name.first()}: ${timerText(ticks)}"
    }

    val timerHudElement: HudElement = object : HudElement {
        override fun hudIslands(): Set<Island> = setOf(Island.CATACOMBS)

        override fun id(): String = "wither_dragons_timer"
        override fun module(): Module = this@WitherDragonsModule
        override fun displayName(): String = "Dragon Timer HUD"
        override fun width(font: Font?): Int = font!!.width(hudLine() ?: SAMPLE)
        override fun height(font: Font?): Int = font!!.lineHeight
        override fun previewWidth(font: Font?): Int = font!!.width(SAMPLE)
        override fun visible(): Boolean = isEnabled() && s.dragonTimer.value() && s.timerHud.value() && hudLine() != null
        override fun previewMode(): String = if (visible()) "live" else "sample"
        override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
            graphics.text(font, Component.literal(hudLine() ?: return), x, y, WHITE)
        }

        override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
            graphics.text(font, Component.literal(hudLine() ?: SAMPLE), x, y, WHITE)
        }
    }

    class Settings {
        val dragonTimer = BooleanSetting("Dragon Timer", true)
        val timerStyle = ChoiceSetting(
            "Timer Style", "MILLISECONDS",
            mapOf("MILLISECONDS" to "Milliseconds", "SECONDS" to "Seconds", "TICKS" to "Ticks"),
            "MILLISECONDS", "SECONDS", "TICKS",
        )
        val timerSymbol = BooleanSetting("Timer Symbol", true)
        val timerHud = BooleanSetting("Dragon Timer HUD", true)
        val dragonBoxes = BooleanSetting("Dragon Boxes", true)
        val statueBoxes = BooleanSetting("Statue Boxes", false)
        val targetTracer = BooleanSetting("Target Tracer", true)
        val sendConfirmation = BooleanSetting("Send Dragon Confirmation", false)
        val dragonTitle = BooleanSetting("Dragon Title", true)
        val sendTime = BooleanSetting("Send Dragon Time Alive", false)
        val sendSpawned = BooleanSetting("Send Dragon Spawned", false)
        val sendSpray = BooleanSetting("Send Ice Sprayed", false)
        val dragonHealth = BooleanSetting("Dragon Health", true)
    }

    companion object {
        private const val HEALTH_DATA_ID = 9
        private const val SAMPLE = "§5P §a4.5s"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private val WITHER_KING =
            Regex("^\\[BOSS] Wither King: (Oh, this one hurts!|I have more of those\\.|My soul is disposable\\.)$")

        @JvmField
        val INSTANCE = WitherDragonsModule(Settings())
    }
}
