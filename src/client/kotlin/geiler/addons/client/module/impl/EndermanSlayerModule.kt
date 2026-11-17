package geiler.addons.client.module.impl

import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.slayer.BlockProbe
import geiler.addons.client.slayer.LaserPhaseTimer
import geiler.addons.client.slayer.SlayerBossTracker
import geiler.addons.client.slayer.SlayerRender
import geiler.addons.client.slayer.SlayerRules
import geiler.addons.client.slayer.YangGlyphTracker
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.Locale

/**
 * Voidgloom Seraph helpers after Skyblocker's Enderman slayer features (Yang Glyph notification, beacon
 * highlighting, Nukekubi head highlighting, laser phase timer): a title and sound when a glyph is thrown, a box and
 * countdown on each landed beacon, boxes on Nukekubi skulls, and a countdown over the boss during the laser phase.
 * Active only while your own Voidgloom Seraph is in range; a short grace keeps the laser countdown alive if the boss
 * label drops out of a scan. Landed beacons also get a tracer from your crosshair.
 */
class EndermanSlayerModule private constructor(private val s: Settings) : Module(
    "Enderman Slayer",
    "Yang Glyph alert, beacon and Nukekubi head highlights and a laser phase timer for Voidgloom Seraph.",
    Category.SLAYER,
    false,
    *s.spec.settings.toTypedArray(),
) {
    private val diagnostics = ModuleDiagnostics(Category.SLAYER, { name() }, 8, 100L, 300)
    private val glyphs = YangGlyphTracker(BlockProbe { x, y, z ->
        Minecraft.getInstance().level?.getBlockState(BlockPos(x, y, z))?.`is`(Blocks.BEACON) == true
    })
    private val laser = LaserPhaseTimer()

    @Volatile
    private var skulls: List<ArmorStand> = emptyList()
    @Volatile
    private var bossBox: Vec3? = null
    private var engaged = false
    private var missingTicks = 0
    private var glyphsThrown = 0
    private var beaconsLanded = 0
    private var lastBeaconCount = 0
    private var lastSkullCount = -1
    private var laserStarts = 0
    private var wasLaserActive = false

    init {
        group(*s.spec.groups.toTypedArray())
    }

    override fun isActive(): Boolean = isEnabled() && SlayerBossTracker.eligible()

    override fun inactiveReason(): String? =
        if (isEnabled() && !SlayerBossTracker.eligible()) "Off in dungeons, Kuudra and outside SkyBlock" else null

    override fun onEnable() {
        resetState()
        diagnostics.enable(
            "notify=${s.yangNotify.value()} sound=${s.yangSound.value()} beacons=${s.beacons.value()} " +
                "tracer=${s.beaconTracer.value()} nukekubi=${s.nukekubi.value()} laser=${s.laserTimer.value()} depth=${s.depthCheck.value()}",
        )
    }

    override fun onDisable() {
        resetState()
        diagnostics.disable()
    }

    private fun resetState() {
        glyphs.reset()
        laser.reset()
        skulls = emptyList()
        bossBox = null
        engaged = false
        missingTicks = 0
        lastBeaconCount = 0
        lastSkullCount = -1
        wasLaserActive = false
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val now = SlayerBossTracker.clock()
        val snapshot = SlayerBossTracker.snapshot
        val boss = snapshot.ownBoss?.takeIf { isActive() && it.family == SlayerRules.Family.VOIDGLOOM }
        if (boss == null) {
            if (engaged && ++missingTicks > GRACE_TICKS) {
                resetState()
                diagnostics.event("DISENGAGED ownVoidgloom=false graceTicks=$GRACE_TICKS")
            }
            diagnostics.statusOnChange { "idle engaged=false eligible=${SlayerBossTracker.eligible()}" }
            return
        }
        missingTicks = 0
        if (!engaged) {
            engaged = true
            diagnostics.event("ENGAGED ownerLabels=${snapshot.ownerLabelsSeen} ownerUnverified=${snapshot.ownerUnverified}")
        }
        bossBox = boss.body.position()
        val rangeSquared = BOSS_AREA * BOSS_AREA

        if (s.yangNotify.value() || s.beacons.value()) {
            val near = snapshot.glyphStands.filterValues { pos ->
                val dx = pos[0] - boss.body.x
                val dy = pos[1] - boss.body.y
                val dz = pos[2] - boss.body.z
                dx * dx + dy * dy + dz * dz <= rangeSquared
            }
            val fresh = glyphs.update(near, now)
            if (fresh > 0) {
                glyphsThrown += fresh
                diagnostics.event("GLYPH_THROWN fresh=$fresh stands=${near.size}")
                if (s.yangNotify.value()) {
                    TitleAlertStack.submit("enderman_slayer", "Yang Glyph!", ALERT_TICKS, RED)
                    if (s.yangSound.value()) ModuleSounds.play("minecraft:block.note_block.pling", 1f, 0.5f)
                }
            }
            val landed = glyphs.beacons(now).size
            if (landed != lastBeaconCount) {
                if (landed > lastBeaconCount) beaconsLanded += landed - lastBeaconCount
                diagnostics.event("BEACONS count=$landed totalLanded=$beaconsLanded pending=${glyphs.pendingCount()}")
                lastBeaconCount = landed
            }
        }

        if (s.nukekubi.value()) {
            skulls = snapshot.skulls.filter { it.distanceToSqr(boss.body) <= rangeSquared }
            if (skulls.size != lastSkullCount) {
                lastSkullCount = skulls.size
                diagnostics.event("NUKEKUBI_HEADS count=${skulls.size}")
            }
        } else {
            skulls = emptyList()
        }

        if (s.laserTimer.value()) {
            val mounted = boss.body.vehicle != null
            laser.update(mounted, now)
            val active = laser.active(now)
            if (active && !wasLaserActive) {
                laserStarts++
                diagnostics.event("LASER_START mounted=$mounted starts=$laserStarts")
            }
            wasLaserActive = active
        }
        diagnostics.statusOnChangeBy(20L, { "$engaged/$lastBeaconCount/${skulls.size}/$wasLaserActive" }) {
            "engaged=true beacons=$lastBeaconCount pending=${glyphs.pendingCount()} skulls=${skulls.size} " +
                "laserActive=$wasLaserActive glyphsThrown=$glyphsThrown beaconsLanded=$beaconsLanded " +
                "laserStarts=$laserStarts scans=${SlayerBossTracker.scans}"
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isActive() || !engaged) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera().position()
        val buffers = WorldRenderBuffer.current()
        val now = SlayerBossTracker.clock()
        var drew = false
        if (s.beacons.value()) {
            val fill = s.beaconColor.argb()
            val outline = fill or (0xFF shl 24)
            for (beacon in glyphs.beacons(now)) {
                EspRenderer.renderBox(
                    context.poseStack(), buffers, beacon.x - camera.x, beacon.y - camera.y, beacon.z - camera.z,
                    1.0, 1.0, 1.0, fill, outline, 2.0f, s.depthCheck.value(),
                )
                if (s.beaconTracer.value()) {
                    SlayerRender.tracer(
                        context.poseStack(), buffers, Vec3(beacon.x + 0.5, beacon.y + 0.5, beacon.z + 0.5), outline, false,
                    )
                }
                drew = true
            }
        }
        if (s.nukekubi.value()) {
            val color = s.nukekubiColor.argb()
            val partialTick = SlayerRender.partialTick()
            for (stand in skulls) {
                if (stand.isRemoved) continue
                val base = if (stand.isSmall) SMALL_HEAD_HEIGHT else HEAD_HEIGHT
                val shown = stand.getPosition(partialTick)
                EspRenderer.renderBox(
                    context.poseStack(), buffers, shown.x - SKULL_HALF - camera.x, shown.y + base - camera.y,
                    shown.z - SKULL_HALF - camera.z, SKULL_HALF * 2, SKULL_HALF * 2, SKULL_HALF * 2,
                    color and 0x00FFFFFF or (0x50 shl 24), color, 2.0f, s.depthCheck.value(),
                )
                drew = true
            }
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    /** Beacon and laser countdowns, drawn as projected labels. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isActive() || !engaged) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera()
        val now = SlayerBossTracker.clock()
        if (s.beacons.value()) {
            for (beacon in glyphs.beacons(now)) {
                val seconds = (YangGlyphTracker.BEACON_TICKS - (now - beacon.startTick) - SlayerRender.partialTick()) / 20.0
                val world = Vec3(beacon.x + 0.5, beacon.y + 1.6, beacon.z + 0.5)
                ProjectedLabelRenderer.draw(graphics, camera, client.font, world, format(seconds), AQUA, 1.0f)
            }
        }
        val boss = bossBox
        if (s.laserTimer.value() && boss != null && laser.active(now)) {
            val seconds = (laser.remainingTicks(now) - SlayerRender.partialTick()) / 20.0
            ProjectedLabelRenderer.draw(
                graphics, camera, client.font, Vec3(boss.x, boss.y + LASER_LABEL_HEIGHT, boss.z), format(seconds), AQUA, 1.4f,
            )
        }
    }

    private fun format(seconds: Double): String = String.format(Locale.ROOT, "%.1fs", Math.max(0.0, seconds))

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Enderman Slayer")
        val yangNotify = spec.toggle("yangNotify")
        val yangSound = spec.toggle("yangSound")
        val beacons = spec.toggle("beacons")
        val beaconColor = spec.color("beaconColor")
        val beaconTracer = spec.toggle("beaconTracer")
        val nukekubi = spec.toggle("nukekubi")
        val nukekubiColor = spec.color("nukekubiColor")
        val laserTimer = spec.toggle("laserTimer")
        val depthCheck = spec.toggle("depthCheck")
    }

    companion object {
        private const val BOSS_AREA = 32.0
        private const val GRACE_TICKS = 60
        private const val ALERT_TICKS = 25
        private const val RED = 0xFFFF5555.toInt()
        private const val AQUA = 0xFF55FFFF.toInt()
        private const val HEAD_HEIGHT = 1.45
        private const val SMALL_HEAD_HEIGHT = 0.7
        private const val SKULL_HALF = 0.3
        private const val LASER_LABEL_HEIGHT = 3.2

        @JvmField
        val INSTANCE = EndermanSlayerModule(Settings())
    }
}
