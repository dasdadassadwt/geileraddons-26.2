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
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity

/**
 * Inferno Demonlord helpers after Skyblocker's Blaze slayer features: the boss and its demons are boxed in the colour
 * of their current shield attunement, and the fire pillar countdown is shown as a title for its last five seconds.
 * While Slayer Boss Highlight boxes the boss it takes the attunement colour itself, so this module draws only the
 * demons then; with Slayer Boss Highlight off, the boss gets a box from here.
 */
class BlazeSlayerModule private constructor(private val s: Settings) : Module(
    "Blaze Slayer",
    "Colours the Inferno Demonlord and its demons by shield attunement and counts down the fire pillar.",
    Category.SLAYER,
    false,
    *s.spec.settings.toTypedArray(),
) {
    private class Target(val body: LivingEntity, val attunement: SlayerRules.Attunement)

    private val diagnostics = ModuleDiagnostics(Category.SLAYER, { name() }, 8, 100L, 300)

    @Volatile
    private var targets: List<Target> = emptyList()
    private var lastScanTick = -1L
    private var lastDemonlordTick = Long.MIN_VALUE
    private var lastPillarKey = ""
    private var pillarAnnouncements = 0
    private var lastSignature = ""

    init {
        group(*s.spec.groups.toTypedArray())
    }

    override fun isActive(): Boolean = isEnabled() && SlayerBossTracker.eligible()

    override fun inactiveReason(): String? =
        if (isEnabled() && !SlayerBossTracker.eligible()) "Off in dungeons, Kuudra and outside SkyBlock" else null

    override fun onEnable() {
        reset()
        diagnostics.enable(
            "attunement=${s.attunement.value()} pillar=${s.firePillar.value()} sound=${s.pillarSound.value()} " +
                "depth=${s.depthCheck.value()}",
        )
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        targets = emptyList()
        lastScanTick = -1L
        lastDemonlordTick = Long.MIN_VALUE
        lastPillarKey = ""
        lastSignature = ""
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val player = client.player
        val now = SlayerBossTracker.clock()
        val snapshot = SlayerBossTracker.snapshot
        if (!isActive() || player == null) {
            targets = emptyList()
            diagnostics.statusOnChange { "idle active=false eligible=${SlayerBossTracker.eligible()}" }
            return
        }
        val boss = snapshot.ownBoss?.takeIf { it.family == SlayerRules.Family.DEMONLORD }
        if (boss != null) lastDemonlordTick = now
        val inFight = lastDemonlordTick != Long.MIN_VALUE && now - lastDemonlordTick <= FIGHT_MEMORY_TICKS

        if (s.attunement.value() && inFight && snapshot.scanTick != lastScanTick) {
            lastScanTick = snapshot.scanTick
            val next = ArrayList<Target>()
            for (body in snapshot.bodies) {
                if (!isDemonBody(body.type) || body.distanceToSqr(player) > DEMON_RADIUS * DEMON_RADIUS) continue
                val attunement = snapshot.labelTextsNear(body, LABEL_RADIUS).firstNotNullOfOrNull(SlayerRules::attunement)
                    ?: continue
                next.add(Target(body, attunement))
                if (next.size >= MAX_TARGETS) break
            }
            targets = java.util.List.copyOf(next)
        } else if (!s.attunement.value() || !inFight) {
            targets = emptyList()
        }

        if (s.firePillar.value() && inFight) announcePillar(snapshot, boss?.body ?: player)

        val signature = "${boss != null}/${targets.size}/${targets.map { it.attunement }.distinct()}"
        if (signature != lastSignature) {
            lastSignature = signature
            diagnostics.event(
                "TARGETS ownBoss=${boss != null} demons=${targets.size} attunements=${targets.map { it.attunement }.distinct()}",
            )
        }
        diagnostics.statusOnChangeBy(20L, { signature }) {
            "inFight=$inFight ownBoss=${boss != null} targets=${targets.size} pillarAnnouncements=$pillarAnnouncements " +
                "labels=${snapshot.labels.size} scans=${SlayerBossTracker.scans}"
        }
    }

    private fun announcePillar(snapshot: SlayerBossTracker.Snapshot, reference: net.minecraft.world.entity.Entity) {
        val limit = PILLAR_RADIUS * PILLAR_RADIUS
        for (label in snapshot.labels) {
            val seconds = SlayerRules.firePillarSeconds(label.text)
            if (seconds !in 0..PILLAR_WARNING_SECONDS) continue
            if (label.entity.distanceToSqr(reference) > limit) continue
            val key = "${label.entity.id}:$seconds"
            if (key == lastPillarKey) return
            lastPillarKey = key
            pillarAnnouncements++
            TitleAlertStack.submit("blaze_slayer", label.text, PILLAR_TITLE_TICKS, PURPLE)
            if (s.pillarSound.value()) ModuleSounds.play("minecraft:block.note_block.pling", 1f, 1f)
            diagnostics.event("FIRE_PILLAR seconds=$seconds distance=${label.entity.distanceTo(reference).toInt()}")
            return
        }
    }

    /** The attunement shown on [body] this scan, or null; Slayer Boss Highlight reads it to recolor the boss. */
    fun attunementFor(body: net.minecraft.world.entity.Entity): SlayerRules.Attunement? =
        if (s.attunement.value()) targets.firstOrNull { it.body === body }?.attunement else null

    private fun isDemonBody(type: EntityType<*>): Boolean =
        type === EntityTypes.BLAZE || type === EntityTypes.WITHER_SKELETON || type === EntityTypes.ZOMBIFIED_PIGLIN

    fun render(context: LevelRenderContext) {
        if (!isActive()) return
        val current = targets
        if (current.isEmpty()) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera().position()
        val buffers = WorldRenderBuffer.current()
        var drew = false
        val partialTick = SlayerRender.partialTick()
        for (target in current) {
            if (target.body.isRemoved || SlayerBossHighlightModule.INSTANCE.draws(target.body)) continue
            val bounds = SlayerRender.interpolatedBox(target.body, partialTick)
            val outline = target.attunement.argb
            val fill = outline and 0x00FFFFFF or (0x40 shl 24)
            EspRenderer.renderBox(
                context.poseStack(), buffers, bounds.minX - camera.x, bounds.minY - camera.y, bounds.minZ - camera.z,
                bounds.xsize, bounds.ysize, bounds.zsize, fill, outline, 2.0f, s.depthCheck.value(),
            )
            drew = true
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Blaze Slayer")
        val attunement = spec.toggle("attunement")
        val firePillar = spec.toggle("firePillar")
        val pillarSound = spec.toggle("pillarSound")
        val depthCheck = spec.toggle("depthCheck")
    }

    companion object {
        private const val LABEL_RADIUS = 2.5
        private const val PILLAR_RADIUS = 22.0
        private const val DEMON_RADIUS = 40.0
        private const val PILLAR_WARNING_SECONDS = 5
        private const val PILLAR_TITLE_TICKS = 15
        private const val FIGHT_MEMORY_TICKS = 600L
        private const val MAX_TARGETS = 32
        private const val PURPLE = 0xFFAA00AA.toInt()

        @JvmField
        val INSTANCE = BlazeSlayerModule(Settings())
    }
}
