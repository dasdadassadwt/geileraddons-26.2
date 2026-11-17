package geiler.addons.client.module.impl

import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.slayer.SlayerBossTracker
import geiler.addons.client.slayer.SlayerRender
import geiler.addons.client.slayer.SlayerRules
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3

/**
 * Riftstalker Bloodfiend helpers after Skyblocker's Vampire slayer indicators and SkyHanni's Vampire slayer features:
 * an ice warning while Twinclaws is active (after a configurable delay), a Steak Stake warning while the stake marker
 * shows, a title when a Blood Ichor or Killer Spring appears, and boxes plus crosshair tracers on those and on the
 * boss. Active only for your own Bloodfiend.
 */
class VampireSlayerModule private constructor(private val s: Settings) : Module(
    "Vampire Slayer",
    "Warns about Twinclaws ice, Steak Stake, Blood Ichor and Killer Springs of the Riftstalker Bloodfiend and draws tracers to them.",
    Category.SLAYER,
    false,
    *s.spec.settings.toTypedArray(),
) {
    private val diagnostics = ModuleDiagnostics(Category.SLAYER, { name() }, 8, 100L, 300)
    private var iceSinceTick = Long.MIN_VALUE
    private var iceShown = false
    private var stakeShown = false
    private var engaged = false
    private var missingTicks = 0
    private var warnings = 0
    private var lastSignature = ""
    private val seenIchors = HashSet<Int>()
    private val seenSprings = HashSet<Int>()

    @Volatile
    private var ichors: List<ArmorStand> = emptyList()
    @Volatile
    private var springs: List<ArmorStand> = emptyList()
    @Volatile
    private var bossBody: net.minecraft.world.entity.Entity? = null

    init {
        group(*s.spec.groups.toTypedArray())
    }

    override fun isActive(): Boolean = isEnabled() && SlayerBossTracker.eligible()

    override fun inactiveReason(): String? =
        if (isEnabled() && !SlayerBossTracker.eligible()) "Off in dungeons, Kuudra and outside SkyBlock" else null

    override fun onEnable() {
        reset()
        diagnostics.enable(
            "twinclaws=${s.twinclaws.value()} delayTicks=${s.twinclawsDelay.intValue()} stake=${s.steakStake.value()} " +
                "ichor=${s.ichorWarning.value()} spring=${s.springWarning.value()} tracers=${s.standTracers.value()} " +
                "bossTracer=${s.bossTracer.value()} sound=${s.sound.value()}",
        )
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        iceSinceTick = Long.MIN_VALUE
        iceShown = false
        stakeShown = false
        engaged = false
        missingTicks = 0
        lastSignature = ""
        seenIchors.clear()
        seenSprings.clear()
        ichors = emptyList()
        springs = emptyList()
        bossBody = null
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val player = Minecraft.getInstance().player
        val now = SlayerBossTracker.clock()
        val snapshot = SlayerBossTracker.snapshot
        val boss = snapshot.ownBoss?.takeIf { isActive() && it.family == SlayerRules.Family.VAMPIRE }
        if (boss == null || player == null) {
            if (engaged && ++missingTicks > GRACE_TICKS) {
                reset()
                diagnostics.event("DISENGAGED ownBloodfiend=false graceTicks=$GRACE_TICKS")
            }
            diagnostics.statusOnChange { "idle engaged=false eligible=${SlayerBossTracker.eligible()}" }
            return
        }
        missingTicks = 0
        if (!engaged) {
            engaged = true
            diagnostics.event("ENGAGED ownerLabels=${snapshot.ownerLabelsSeen} ownerUnverified=${snapshot.ownerUnverified}")
        }
        bossBody = boss.body
        val texts = snapshot.labelTextsNear(boss.body, LABEL_RADIUS)
        val ice = s.twinclaws.value() && texts.any(SlayerRules::hasTwinclaws)
        val stake = s.steakStake.value() && texts.any(SlayerRules::hasStakeMarker)
        val refresh = now % REFRESH_TICKS == 0L

        if (ice) {
            if (iceSinceTick == Long.MIN_VALUE) iceSinceTick = now
        } else {
            iceSinceTick = Long.MIN_VALUE
        }
        val iceDue = ice && now - iceSinceTick >= s.twinclawsDelay.intValue()

        val limit = STAND_RADIUS * STAND_RADIUS
        ichors = snapshot.ichors.filter { it.distanceToSqr(player) <= limit }
        springs = snapshot.springs.filter { it.distanceToSqr(player) <= limit }
        val newIchor = ichors.count { seenIchors.add(it.id) }
        val newSpring = springs.count { seenSprings.add(it.id) }
        seenIchors.retainAll(ichors.map { it.id }.toSet())
        seenSprings.retainAll(springs.map { it.id }.toSet())

        // One title at a time; the most time-critical warning wins.
        when {
            stake -> showTitle("Stake Now!", RED, refresh || !stakeShown)
            newIchor > 0 && s.ichorWarning.value() -> showTitle("Blood Ichor!", DARK_RED, true)
            newSpring > 0 && s.springWarning.value() -> showTitle("Killer Spring!", GOLD, true)
            iceDue -> showTitle("Ice Now!", AQUA, refresh || !iceShown)
        }
        if (stake && !stakeShown) diagnostics.event("STAKE_MARKER")
        if (iceDue && !iceShown) diagnostics.event("TWINCLAWS_MARKER delayTicks=${now - iceSinceTick}")
        if (newIchor > 0) diagnostics.event("BLOOD_ICHOR new=$newIchor total=${ichors.size}")
        if (newSpring > 0) diagnostics.event("KILLER_SPRING new=$newSpring total=${springs.size}")
        stakeShown = stake
        iceShown = iceDue

        val signature = "$ice/$stake/${ichors.size}/${springs.size}"
        diagnostics.statusOnChangeBy(20L, { signature }) {
            "engaged=true ice=$ice stake=$stake ichors=${ichors.size} springs=${springs.size} warnings=$warnings " +
                "labelsNear=${texts.size} scans=${SlayerBossTracker.scans}"
        }
    }

    private fun showTitle(text: String, color: Int, show: Boolean) {
        if (!show) return
        warnings++
        TitleAlertStack.submit("vampire_slayer", text, TITLE_TICKS, color)
        if (s.sound.value() && (!stakeShown && !iceShown)) {
            ModuleSounds.play("minecraft:block.note_block.pling", 1f, 1f)
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isActive() || !engaged) return
        val boss = bossBody
        val ichorList = ichors
        val springList = springs
        val drawBoss = s.bossTracer.value() && boss != null && !boss.isRemoved
        if (!drawBoss && ichorList.isEmpty() && springList.isEmpty()) return
        val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
        val buffers = WorldRenderBuffer.current()
        val partialTick = SlayerRender.partialTick()
        var drew = false
        if (drawBoss) {
            val at = boss.getPosition(partialTick)
            SlayerRender.tracer(context.poseStack(), buffers, Vec3(at.x, at.y + boss.bbHeight / 2.0, at.z), s.bossTracerColor.argb(), false)
            drew = true
        }
        drew = drawStands(context, buffers, camera, partialTick, ichorList, s.ichorColor.argb()) || drew
        drew = drawStands(context, buffers, camera, partialTick, springList, s.springColor.argb()) || drew
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    private fun drawStands(
        context: LevelRenderContext,
        buffers: WorldRenderBuffer.BufferSource,
        camera: Vec3,
        partialTick: Float,
        stands: List<ArmorStand>,
        color: Int,
    ): Boolean {
        var drew = false
        val fill = color and 0x00FFFFFF or (0x50 shl 24)
        for (stand in stands) {
            if (stand.isRemoved) continue
            val shown = stand.getPosition(partialTick)
            val centerY = shown.y + (if (stand.isSmall) SMALL_HEAD_HEIGHT else HEAD_HEIGHT) + SKULL_HALF
            EspRenderer.renderBox(
                context.poseStack(), buffers, shown.x - SKULL_HALF - camera.x, centerY - SKULL_HALF - camera.y,
                shown.z - SKULL_HALF - camera.z, SKULL_HALF * 2, SKULL_HALF * 2, SKULL_HALF * 2, fill, color, 2.0f, false,
            )
            if (s.standTracers.value()) {
                SlayerRender.tracer(context.poseStack(), buffers, Vec3(shown.x, centerY, shown.z), color, false)
            }
            drew = true
        }
        return drew
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Vampire Slayer")
        val twinclaws = spec.toggle("twinclaws")
        val twinclawsDelay = spec.slider("twinclawsDelay")
        val steakStake = spec.toggle("steakStake")
        val ichorWarning = spec.toggle("ichorWarning")
        val springWarning = spec.toggle("springWarning")
        val ichorColor = spec.color("ichorColor")
        val springColor = spec.color("springColor")
        val standTracers = spec.toggle("standTracers")
        val bossTracer = spec.toggle("bossTracer")
        val bossTracerColor = spec.color("bossTracerColor")
        val sound = spec.toggle("sound")
    }

    companion object {
        private const val LABEL_RADIUS = 3.0
        private const val STAND_RADIUS = 32.0
        private const val GRACE_TICKS = 60
        private const val REFRESH_TICKS = 10L
        private const val TITLE_TICKS = 20
        private const val HEAD_HEIGHT = 1.45
        private const val SMALL_HEAD_HEIGHT = 0.7
        private const val SKULL_HALF = 0.3
        private const val RED = 0xFFFF5555.toInt()
        private const val DARK_RED = 0xFFAA0000.toInt()
        private const val GOLD = 0xFFFFAA00.toInt()
        private const val AQUA = 0xFF55FFFF.toInt()

        @JvmField
        val INSTANCE = VampireSlayerModule(Settings())
    }
}
