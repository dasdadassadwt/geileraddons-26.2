package geiler.addons.client.module.impl

import geiler.addons.client.entity.RareMob
import geiler.addons.client.fishing.SeaCreatureRules
import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import java.util.EnumMap
import kotlin.math.floor

/** One preset's switch and colour. */
private class MobSettings(val mob: RareMob) {
    val enabled = BooleanSetting(mob.title, true)
    val color = ColorSetting(
        "${mob.title} Color", (mob.color shr 16) and 0xFF, (mob.color shr 8) and 0xFF, mob.color and 0xFF, 255,
    )
}

/** Boxes preset rare mobs on their island and announces the uncommon ones once. */
class RareMobHighlighterModule private constructor(private val s: Settings) : Module(
    "Rare Mob Highlighter",
    "Boxes rare mobs and tells you when one appears.",
    Category.MISCELLANEOUS,
    false,
    *s.all().toTypedArray<Setting>(),
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)
    private val tracker = LabeledMobTracker(npcBodies = true) { name ->
        RareMob.fromNameTag(ChatText.plain(name.string), HypixelModApi.currentIsland())
    }
    private val sightings = ArrayList<LabeledMobTracker.Sighting<RareMob>>()
    private val seen = SeaCreatureRules.SeenSet(SEEN_TTL_NANOS)
    private var lastLevel: ClientLevel? = null
    private var lastPartyNanos = Long.MIN_VALUE
    private var ticks = 0
    private var alerts = 0

    init {
        group(
            SettingGroup("Display", s.tracer, s.labels),
            SettingGroup("When Found", s.title, s.sound, s.partyMessage),
            *RareMob.entries.map { it.island }.distinct().map { island ->
                SettingGroup.folded(
                    island.label(),
                    *RareMob.entries.filter { it.island == island }
                        .flatMap { listOf<Setting>(mobToggle(it), mobColor(it)) }.toTypedArray(),
                )
            }.toTypedArray(),
        )
    }

    fun mobToggle(mob: RareMob): BooleanSetting = s.mobs.getValue(mob).enabled

    fun mobColor(mob: RareMob): ColorSetting = s.mobs.getValue(mob).color

    fun partyMessageToggle(): BooleanSetting = s.partyMessage

    override fun onEnable() {
        reset()
        diagnostics.enable(
            "tracer=${s.tracer.value()} labels=${s.labels.value()} title=${s.title.value()} sound=${s.sound.value()} " +
                "party=${s.partyMessage.value()} mobsOff=${RareMob.entries.filter { !mobToggle(it).value() }}",
        )
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        tracker.clear()
        sightings.clear()
        seen.clear()
        ticks = 0
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        val island = HypixelModApi.currentIsland()
        if (level == null || player == null || RareMob.entries.none { it.island == island }) {
            if (sightings.isNotEmpty() || level == null) reset()
            return
        }
        if (level !== lastLevel) {
            lastLevel = level
            reset()
        }
        sightings.removeIf { it.body.isRemoved || !it.body.isAlive }
        if (++ticks >= SCAN_TICKS) {
            ticks = 0
            sightings.clear()
            sightings.addAll(find(tracker, level, player, island) { mobToggle(it).value() })
            announce(player)
        }
        diagnostics.status {
            "island=$island sightings=${sightings.size} seen=${seen.size()} alerts=$alerts " +
                "scan=${tracker.lastScan} suppressedEvents=$it"
        }
    }

    /**
     * Wanted preset mobs around [player]: name-tag matches plus the two found without a name tag. Also used by
     * Lootshare Range with its own [tracker], so it works while this module is off.
     */
    fun find(
        tracker: LabeledMobTracker<RareMob>,
        level: ClientLevel,
        player: LocalPlayer,
        island: Island,
        wanted: (RareMob) -> Boolean,
    ): List<LabeledMobTracker.Sighting<RareMob>> {
        tracker.scan(level, player, SCAN_RANGE)
        val found = ArrayList(tracker.sightings)
        if (island == Island.THE_END) {
            val area = AABB.ofSize(player.position(), SCAN_RANGE * 2, SCAN_RANGE * 2, SCAN_RANGE * 2)
            for (entity in level.getEntities(EntityTypeTest.forClass(Entity::class.java), area) {
                it is EnderMan || it is EnderDragon
            }) {
                val mob = when {
                    entity is EnderDragon -> RareMob.ENDER_DRAGON
                    entity is EnderMan && entity.carriedBlock?.block === Blocks.END_PORTAL_FRAME -> RareMob.SPECIAL_ZEALOT
                    else -> continue
                }
                // A Special Zealot also carries the plain Zealot name tag; the special entry wins.
                found.removeIf { it.body === entity }
                found.add(LabeledMobTracker.Sighting(mob, entity, entity))
            }
        }
        found.removeIf { !wanted(it.tag) }
        return found
    }

    private fun announce(player: LocalPlayer) {
        val now = System.nanoTime()
        for (sighting in sightings) {
            if (!seen.firstSight(sighting.body.id, now)) continue
            val mob = sighting.tag
            diagnostics.event("FOUND $mob distance=${player.distanceTo(sighting.body).toInt()}")
            if (mob.common()) continue
            alerts++
            if (s.title.value()) {
                TitleAlertStack.submit("rare_mob_highlighter", "${mob.title}!", TITLE_TICKS, mobColor(mob).opaqueArgb())
            }
            if (s.sound.value()) ModuleSounds.play("minecraft:entity.experience_orb.pickup")
            if (s.partyMessage.value() && PartyListBackend.snapshot().inParty() &&
                (lastPartyNanos == Long.MIN_VALUE || now - lastPartyNanos >= PARTY_GAP_NANOS)
            ) {
                lastPartyNanos = now
                val at = sighting.body.position()
                player.connection.sendCommand("pc " + partyText(mob, at.x, at.y, at.z))
                diagnostics.event("PARTY_MESSAGE $mob")
            }
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || sightings.isEmpty()) return
        val camera = Minecraft.getInstance().gameRenderer.mainCamera()
        val cam = camera.position()
        val forward = camera.forwardVector()
        val pose = context.poseStack()
        val buffer = WorldRenderBuffer.current()
        for (sighting in sightings) {
            if (sighting.body.isRemoved) continue
            val box = sighting.body.boundingBox
            val color = mobColor(sighting.tag).opaqueArgb()
            EspRenderer.renderBox(
                pose, buffer, box.minX - cam.x, box.minY - cam.y, box.minZ - cam.z,
                box.xsize, box.ysize, box.zsize, (color and 0x00FFFFFF) or FILL_ALPHA, color, LINE_WIDTH,
            )
            if (s.tracer.value()) {
                EspRenderer.renderLine(
                    pose, buffer, forward.x() * 0.5, forward.y() * 0.5, forward.z() * 0.5,
                    box.center.x - cam.x, box.center.y - cam.y, box.center.z - cam.z, color, LINE_WIDTH,
                )
            }
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
    }

    /** World labels through the shared projector. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || !s.labels.value() || sightings.isEmpty()) return
        val player = Minecraft.getInstance().player ?: return
        tracker.renderLabels(
            graphics, sightings,
            { "${it.tag.title} ${player.distanceTo(it.body).toInt()}m" },
            { mobColor(it).opaqueArgb() },
        )
    }

    private class Settings {
        val tracer = BooleanSetting("Tracer", true)
        val labels = BooleanSetting("World Label", true)
        val title = BooleanSetting("Screen Title", true)
        val sound = BooleanSetting("Sound", true)
        val partyMessage = BooleanSetting("Party Chat Message", "Send Party Chat Message (Sends Chat For You)", false)
        val mobs: Map<RareMob, MobSettings> =
            EnumMap<RareMob, MobSettings>(RareMob::class.java).apply {
                for (mob in RareMob.entries) put(mob, MobSettings(mob))
            }

        fun all(): List<Setting> =
            listOf<Setting>(tracer, labels, title, sound, partyMessage) +
                mobs.values.flatMap { listOf<Setting>(it.enabled, it.color) }
    }

    companion object {
        private const val SCAN_TICKS = 10
        private const val SCAN_RANGE = 128.0
        private const val SEEN_TTL_NANOS = 6L * 60L * 1_000_000_000L
        private const val PARTY_GAP_NANOS = 10_000_000_000L
        private const val TITLE_TICKS = 40
        private const val FILL_ALPHA = 0x30000000
        private const val LINE_WIDTH = 2.0f

        @JvmStatic
        fun partyText(mob: RareMob, x: Double, y: Double, z: Double): String =
            "${mob.title} at x: ${floor(x).toInt()}, y: ${floor(y).toInt()}, z: ${floor(z).toInt()}"

        @JvmField
        val INSTANCE = RareMobHighlighterModule(Settings())
    }
}
