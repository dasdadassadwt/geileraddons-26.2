package geiler.addons.client.module.impl

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
import net.minecraft.world.entity.Entity

/**
 * Boxes your Slayer boss and its minibosses, and can hide Sven pup name tags, after Skyblocker's Slayer boss and
 * miniboss highlighting and SkyHanni's mini boss highlight and boss spawn mob name hider. A Demonlord box takes the
 * colour of its Blaze Slayer shield attunement while that module's attunement highlight is on.
 */
class SlayerBossHighlightModule private constructor(private val s: Settings) : Module(
    "Slayer Boss Highlight",
    "Boxes your Slayer boss and minibosses, and can hide Sven pup name tags.",
    Category.SLAYER,
    false,
    *s.spec.settings.toTypedArray(),
) {
    private class Target(val entity: Entity, val fill: Int, val outline: Int)

    private val diagnostics = ModuleDiagnostics(Category.SLAYER, { name() }, 8, 100L, 300)

    @Volatile
    private var targets: List<Target> = emptyList()
    private var lastSignature = ""
    private var pupNamesHidden = 0L

    init {
        group(*s.spec.groups.toTypedArray())
    }

    override fun isActive(): Boolean = isEnabled() && SlayerBossTracker.eligible()

    override fun inactiveReason(): String? =
        if (isEnabled() && !SlayerBossTracker.eligible()) "Off in dungeons, Kuudra and outside SkyBlock" else null

    /** The scan range the shared tracker should use while this module is enabled. */
    fun trackerRange(): Double = s.range.intValue().toDouble()

    override fun onEnable() {
        lastSignature = ""
        pupNamesHidden = 0L
        diagnostics.enable(
            "own=${s.ownBoss.value()} other=${s.otherBosses.value()} minibosses=${s.minibosses.value()} " +
                "pups=${s.hidePupNames.value()} range=${s.range.intValue()} depth=${s.depthCheck.value()}",
        )
    }

    override fun onDisable() {
        targets = emptyList()
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val player = Minecraft.getInstance().player
        if (!isActive() || player == null) {
            targets = emptyList()
            diagnostics.statusOnChange { "idle active=false eligible=${SlayerBossTracker.eligible()}" }
            return
        }
        val snapshot = SlayerBossTracker.snapshot
        val rangeSquared = s.range.intValue().toDouble() * s.range.intValue()
        val next = ArrayList<Target>()
        var own = 0
        var others = 0
        for (boss in snapshot.bosses) {
            if (boss.body.distanceToSqr(player) > rangeSquared) continue
            if (!(boss.own && s.ownBoss.value()) && !(!boss.own && s.otherBosses.value())) continue
            val attunement = if (boss.family == SlayerRules.Family.DEMONLORD) {
                BlazeSlayerModule.INSTANCE.attunementFor(boss.body)
            } else {
                null
            }
            if (attunement != null) {
                next.add(Target(boss.body, attunement.argb and 0x00FFFFFF or (0x40 shl 24), attunement.argb))
            } else {
                next.add(Target(boss.body, s.bossFill.argb(), s.bossOutline.argb()))
            }
            if (boss.own) own++ else others++
        }
        var minis = 0
        if (s.minibosses.value()) {
            for (mini in snapshot.minibosses) {
                if (mini.body.distanceToSqr(player) > rangeSquared) continue
                next.add(Target(mini.body, s.minibossFill.argb(), s.minibossOutline.argb()))
                minis++
            }
        }
        targets = java.util.List.copyOf(next)

        var pupsThisTick = 0
        if (s.hidePupNames.value()) {
            for (label in snapshot.labels) {
                if (SlayerRules.isPupLabel(label.text) && label.entity.isCustomNameVisible) {
                    label.entity.isCustomNameVisible = false
                    pupsThisTick++
                }
            }
            pupNamesHidden += pupsThisTick
        }

        val signature = "$own/$others/$minis/${snapshot.ownerUnverified}"
        if (signature != lastSignature) {
            lastSignature = signature
            diagnostics.event(
                "TARGETS own=$own others=$others minibosses=$minis ownerLabels=${snapshot.ownerLabelsSeen} " +
                    "ownerUnverified=${snapshot.ownerUnverified} bossesSeen=${snapshot.bosses.size}",
            )
        }
        if (pupsThisTick > 0) diagnostics.event("PUP_NAMES_HIDDEN count=$pupsThisTick total=$pupNamesHidden")
        diagnostics.statusOnChangeBy(20L, { signature }) {
            "active=true own=$own others=$others minibosses=$minis labels=${snapshot.labels.size} " +
                "bodies=${snapshot.bodies.size} scans=${SlayerBossTracker.scans} pupNamesHidden=$pupNamesHidden"
        }
    }

    /** Whether this module currently draws a box on [body], so Blaze Slayer can skip its own box for the boss. */
    fun draws(body: Entity): Boolean = isActive() && targets.any { it.entity === body }

    fun render(context: LevelRenderContext) {
        if (!isActive()) return
        val current = targets
        if (current.isEmpty()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val camera = client.gameRenderer.mainCamera().position()
        val buffers = WorldRenderBuffer.current()
        val rangeSquared = s.range.intValue().toDouble() * s.range.intValue()
        var drew = false
        val partialTick = SlayerRender.partialTick()
        for (target in current) {
            val entity = target.entity
            if (entity.isRemoved || player.distanceToSqr(entity) > rangeSquared) continue
            val bounds = SlayerRender.interpolatedBox(entity, partialTick)
            EspRenderer.renderBox(
                context.poseStack(), buffers, bounds.minX - camera.x, bounds.minY - camera.y, bounds.minZ - camera.z,
                bounds.xsize, bounds.ysize, bounds.zsize, target.fill, target.outline, 2.0f, s.depthCheck.value(),
            )
            drew = true
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Slayer Boss Highlight")
        val ownBoss = spec.toggle("ownBoss")
        val otherBosses = spec.toggle("otherBosses")
        val bossFill = spec.color("bossFill")
        val bossOutline = spec.color("bossOutline")
        val minibosses = spec.toggle("minibosses")
        val minibossFill = spec.color("minibossFill")
        val minibossOutline = spec.color("minibossOutline")
        val hidePupNames = spec.toggle("hidePupNames")
        val range = spec.slider("range")
        val depthCheck = spec.toggle("depthCheck")
    }

    companion object {
        @JvmField
        val INSTANCE = SlayerBossHighlightModule(Settings())
    }
}
