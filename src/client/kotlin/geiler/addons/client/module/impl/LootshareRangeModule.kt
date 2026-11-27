package geiler.addons.client.module.impl

import geiler.addons.client.combat.ShareMarks
import geiler.addons.client.entity.RareMob
import geiler.addons.client.fishing.SeaCreatureRules
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.tree.ChatText
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * A 30-block lootshare sphere around rare mobs (the Rare Mob Highlighter's chosen presets) and rare sea creatures (the
 * Sea Creature Alerts list), with a mark for whether you were seen to hit the mob. Range as SkyHanni uses it.
 */
class LootshareRangeModule private constructor(private val s: Settings) : Module(
    "Lootshare Range",
    "Shows the lootshare range of rare mobs as a sphere and whether you hit them.",
    Category.MISCELLANEOUS,
    false,
    s.rareMobs, s.seaCreatures, s.hitMark, s.fill, s.outsideColor, s.insideColor,
) {
    private class Target(val name: String, val label: Entity, val body: Entity)

    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)
    private val rareTracker = LabeledMobTracker(npcBodies = true) { name ->
        RareMob.fromNameTag(ChatText.plain(name.string), HypixelModApi.currentIsland())
    }
    private val creatureTracker = LabeledMobTracker { name ->
        SeaCreatureRules.byNametag(ChatText.plain(name.string))
    }
    private val marks = ShareMarks()
    private val targets = ArrayList<Target>()
    private var lastLevel: ClientLevel? = null
    private var ticks = 0

    override fun onEnable() {
        reset()
        diagnostics.enable(
            "rare=${s.rareMobs.value()} sea=${s.seaCreatures.value()} hitMark=${s.hitMark.value()} fill=${s.fill.value()}",
        )
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        targets.clear()
        marks.clear()
        ticks = 0
    }

    /** From `AttackEntityCallback`; the attacked entity may be the mob or its name tag. */
    fun onAttack(entity: Entity) {
        if (isEnabled()) marks.onAttack(entity.id)
    }

    /** `ClientPacketListener.handleDamageEvent` TAIL. */
    fun onDamageEvent(packet: ClientboundDamageEventPacket) {
        if (!isEnabled()) return
        val self = Minecraft.getInstance().player?.id ?: return
        marks.onDamage(packet.entityId(), packet.sourceCauseId(), self)
        if (targets.any { it.body.id == packet.entityId() }) {
            diagnostics.event("TARGET_DAMAGED id=${packet.entityId()} cause=${packet.sourceCauseId()} self=$self")
        }
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        if (level == null || player == null || !SkyblockItems.inSkyblock()) {
            if (targets.isNotEmpty() || marks.size() > 0) reset()
            return
        }
        if (level !== lastLevel) {
            lastLevel = level
            reset()
        }
        targets.removeIf { it.body.isRemoved || !it.body.isAlive }
        if (++ticks >= SCAN_TICKS) {
            ticks = 0
            targets.clear()
            if (s.rareMobs.value()) {
                val highlighter = RareMobHighlighterModule.INSTANCE
                highlighter.find(rareTracker, level, player, HypixelModApi.currentIsland()) {
                    highlighter.mobToggle(it).value() && !it.common()
                }.forEach { targets.add(Target(it.tag.title, it.label, it.body)) }
            }
            if (s.seaCreatures.value()) {
                creatureTracker.scan(level, player, SCAN_RANGE)
                creatureTracker.sightings.forEach { targets.add(Target(it.tag.name(), it.label, it.body)) }
            }
            // Marks outlive a scan that misses a mob, so only ids that left the world are forgotten.
            if (marks.size() > 0) marks.retain(level.entitiesForRendering().mapTo(HashSet()) { it.id })
        }
        diagnostics.status {
            "targets=${targets.joinToString(",", "[", "]") { "${it.name}:${if (hit(it)) "hit" else "no"}" }} " +
                "marks=${marks.size()} suppressedEvents=$it"
        }
    }

    private fun hit(target: Target): Boolean = marks.hit(target.body.id) || marks.hit(target.label.id)

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || targets.isEmpty()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val cam = client.gameRenderer.mainCamera().position()
        val buffer = WorldRenderBuffer.current()
        val drawn = ArrayList<Vec3>()
        for (target in targets) {
            if (target.body.isRemoved) continue
            val box = ProjectedLabelRenderer.entityBox(target.body)
            val center = Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2)
            // A pack of rare mobs would otherwise stack spheres on one spot.
            if (drawn.count { it.distanceToSqr(center) < CROWD_DISTANCE_SQUARED } >= CROWD_LIMIT) continue
            drawn.add(center)
            val inside = player.position().distanceToSqr(center) <= RANGE * RANGE
            EspRenderer.renderRangeSphere(
                context.poseStack(), buffer, center.x - cam.x, center.y - cam.y, center.z - cam.z, RANGE.toFloat(),
                if (inside) s.insideColor.argb() else s.outsideColor.argb(), s.fill.value(),
            )
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        val mc = Minecraft.getInstance()
        if (!isEnabled() || !s.hitMark.value() || targets.isEmpty() || mc.level == null || mc.gui.hud.isHidden()) return
        val camera = mc.gameRenderer.mainCamera()
        for (target in targets) {
            if (target.body.isRemoved) continue
            val box = ProjectedLabelRenderer.entityBox(target.body)
            val hit = hit(target)
            ProjectedLabelRenderer.draw(
                graphics, camera, mc.font, Vec3(box.center.x, box.maxY + 0.9, box.center.z),
                if (hit) "Lootshare: hit" else "Lootshare: not hit", if (hit) GREEN else RED, 1.0f,
            )
        }
    }

    private class Settings {
        val rareMobs = BooleanSetting("Rare Mobs", "Show The Range Of Rare Mob Highlighter Mobs", true)
        val seaCreatures = BooleanSetting("Sea Creatures", "Show The Range Of Rare Sea Creatures", true)
        val hitMark = BooleanSetting("Hit Mark", "Show Whether You Hit The Mob", true)
        val fill = BooleanSetting("Fill", "Fill The Sphere, Not Only Its Lines", false)
        val outsideColor = ColorSetting("Outside Color", 255, 255, 255, 220)
        val insideColor = ColorSetting("Inside Color", 85, 255, 85, 220)
    }

    companion object {
        private const val RANGE = 30.0
        private const val SCAN_TICKS = 10
        private const val SCAN_RANGE = 96.0
        private const val CROWD_LIMIT = 2
        private const val CROWD_DISTANCE_SQUARED = 100.0
        private const val GREEN = 0xFF55FF55.toInt()
        private const val RED = 0xFFFF5555.toInt()

        @JvmField
        val INSTANCE = LootshareRangeModule(Settings())
    }
}
