package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.entity.Nameplates
import geiler.addons.client.fishing.SeaCreatureRules
import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.tree.ChatText
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Alerts on rare sea creatures, after SkyHanni's Rare Sea Creatures: a title and beep when you catch one (read from
 * the catch chat line), a title when another player's one spawns nearby (read from its name tag), an optional party
 * announcement of your own catch, and a green box around ones within 30 blocks. Off in dungeons and Kuudra.
 */
class SeaCreatureAlertModule private constructor(settings: Settings) : Module(
    "Sea Creature Alerts",
    "Alerts when you or someone near you catches a rare sea creature.",
    Category.FISHING,
    false,
    settings.alertOwnCatches,
    settings.alertOtherCatches,
    settings.creatureName,
    settings.playSound,
    settings.announceRareInParty,
    settings.highlight,
    settings.highlightColor,
) {
    private val alertOwnCatches = settings.alertOwnCatches
    private val alertOtherCatches = settings.alertOtherCatches
    private val creatureName = settings.creatureName
    private val playSound = settings.playSound
    private val announceRareInParty = settings.announceRareInParty
    private val highlight = settings.highlight
    private val highlightColor = settings.highlightColor

    private val diagnostics = ModuleDiagnostics(Category.FISHING, { name() }, 8, 100L, 300)
    private val seen = SeaCreatureRules.SeenSet(SEEN_TTL_NANOS)
    private val sightings = ArrayList<Sighting>()

    private var lastCatchNanos = Long.MIN_VALUE
    private var lastDoubleHookNanos = Long.MIN_VALUE
    private var lastBobberPos: Vec3? = null
    private var ticksSinceScan = 0
    private var lastLevel: ClientLevel? = null
    private var ownCatches = 0
    private var nearbyAlerts = 0

    private class Sighting(val creature: SeaCreatureRules.Creature, val body: Entity)

    override fun isActive(): Boolean = isEnabled() && eligible()

    override fun inactiveReason(): String? {
        if (!isEnabled() || isActive()) return null
        return if (!SkyblockItems.inSkyblock()) "Not on SkyBlock" else "Off in dungeons and Kuudra"
    }

    private fun eligible(): Boolean {
        if (!SkyblockItems.inSkyblock()) return false
        val island = HypixelModApi.currentIsland()
        return island != Island.CATACOMBS && island != Island.KUUDRA
    }

    override fun onEnable() {
        reset()
        diagnostics.enable(
            "own=${alertOwnCatches.value()} other=${alertOtherCatches.value()} name=${creatureName.value()} " +
                "sound=${playSound.value()} party=${announceRareInParty.value()} highlight=${highlight.value()}",
        )
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    /** Called for every clean game chat line. */
    fun onChatMessage(content: String) {
        if (!isActive()) return
        val now = System.nanoTime()
        if (SeaCreatureRules.isDoubleHook(content)) {
            lastDoubleHookNanos = now
            diagnostics.event("DOUBLE_HOOK")
            return
        }
        val creature = SeaCreatureRules.byCatchMessage(content) ?: return
        lastCatchNanos = now
        ownCatches++
        val doubleHook = lastDoubleHookNanos != Long.MIN_VALUE && now - lastDoubleHookNanos <= DOUBLE_HOOK_WINDOW_NANOS
        diagnostics.event("CATCH ${creature.name()} doubleHook=$doubleHook")
        if (alertOwnCatches.value()) alert(SeaCreatureRules.ownTitle(creature, creatureName.value()), OWN_TITLE_TICKS)
        if (announceRareInParty.value() && PartyListBackend.snapshot().inParty()) {
            Minecraft.getInstance().player?.connection?.sendCommand(
                "pc " + SeaCreatureRules.partyMessage(creature, doubleHook),
            )
            diagnostics.event("PARTY_ANNOUNCE ${creature.name()}")
        }
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        if (!eligible() || level == null || player == null) {
            if (sightings.isNotEmpty() || seen.size() > 0) reset()
            return
        }
        if (level !== lastLevel) {
            lastLevel = level
            reset()
        }

        val hook = player.fishing
        if (hook != null && !hook.isRemoved) lastBobberPos = hook.position()
        sightings.removeIf { it.body.isRemoved }

        if (++ticksSinceScan >= SCAN_TICKS) {
            ticksSinceScan = 0
            scan(level, player)
        }
        diagnostics.status {
            "enabled=true active=${isActive()} sightings=${sightings.size} seen=${seen.size()} " +
                "ownCatches=$ownCatches nearbyAlerts=$nearbyAlerts suppressedEvents=$it"
        }
    }

    private fun scan(level: ClientLevel, player: LocalPlayer) {
        val area = AABB.ofSize(player.position(), SCAN_RANGE * 2, SCAN_RANGE * 2, SCAN_RANGE * 2)
        val nearby = level.getEntities(EntityTypeTest.forClass(Entity::class.java), area) { it !== player }
        val labels = ArrayList<Pair<Entity, SeaCreatureRules.Creature>>()
        val bodies = ArrayList<Entity>()
        for (entity in nearby) {
            val label = entity.customName
            val creature = if (label != null) SeaCreatureRules.byNametag(ChatText.plain(label.string)) else null
            if (creature != null) {
                labels.add(entity to creature)
                continue
            }
            if (isMobLike(entity)) bodies.add(entity)
        }

        sightings.clear()
        val now = System.nanoTime()
        for ((label, creature) in labels) {
            val body = pair(bodies, label) ?: continue
            sightings.add(Sighting(creature, body))
            if (!seen.firstSight(label.id, now)) continue
            val bobber = lastBobberPos
            val own = bobber != null && SeaCreatureRules.isOwn(lastCatchNanos, now, bobber.distanceTo(label.position()))
            diagnostics.event("SIGHT ${creature.name()} own=$own distance=${player.distanceTo(label).toInt()}")
            if (!own && alertOtherCatches.value()) {
                nearbyAlerts++
                alert(SeaCreatureRules.nearbyTitle(creature, creatureName.value()), NEARBY_TITLE_TICKS)
            }
        }
    }

    /** Scaffolding, players and items are never the creature's body. */
    private fun isMobLike(entity: Entity): Boolean {
        val type: EntityType<*> = entity.getType()
        return type !== EntityTypes.ARMOR_STAND &&
            type !== EntityTypes.INTERACTION &&
            type !== EntityTypes.ITEM_DISPLAY &&
            type !== EntityTypes.BLOCK_DISPLAY &&
            type !== EntityTypes.TEXT_DISPLAY &&
            type !== EntityTypes.PLAYER &&
            type !== EntityTypes.ITEM &&
            Nameplates.hasBody(entity)
    }

    private fun pair(bodies: List<Entity>, label: Entity): Entity? {
        var best: Entity? = null
        var bestDistance = LABEL_TO_MOB_RADIUS * LABEL_TO_MOB_RADIUS
        for (body in bodies) {
            val distance = body.position().distanceToSqr(label.position())
            if (distance >= bestDistance) continue
            bestDistance = distance
            best = body
        }
        return best ?: if (Nameplates.hasBody(label)) label else null
    }

    private fun alert(title: String, ticks: Int) {
        TitleAlertStack.submit("sea_creature_alerts", title, ticks, WHITE)
        if (playSound.value()) ModuleSounds.play("minecraft:entity.experience_orb.pickup")
    }

    /** Boxes the creatures within 30 blocks, through walls. */
    fun render(context: LevelRenderContext) {
        if (!isActive() || !highlight.value() || sightings.isEmpty()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val camPos = client.gameRenderer.mainCamera().position()
        val poseStack: PoseStack = context.poseStack()
        val buffer = WorldRenderBuffer.current()
        val argb = highlightColor.argb()
        var drawn = 0
        for (sighting in sightings) {
            if (sighting.body.isRemoved || player.distanceTo(sighting.body) > HIGHLIGHT_RANGE) continue
            val box = sighting.body.boundingBox
            EspRenderer.renderBox(
                poseStack, buffer,
                box.minX - camPos.x, box.minY - camPos.y, box.minZ - camPos.z,
                box.xsize, box.ysize, box.zsize,
                argb, argb, BOX_LINE_WIDTH,
            )
            drawn++
        }
        if (drawn > 0) GeilerAddonsRenderTypes.endBatches(buffer)
    }

    private fun reset() {
        sightings.clear()
        seen.clear()
        lastCatchNanos = Long.MIN_VALUE
        lastDoubleHookNanos = Long.MIN_VALUE
        lastBobberPos = null
        ticksSinceScan = 0
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        private val spec = ModuleSettingsSpec.of("Sea Creature Alerts")
        val alertOwnCatches = spec.toggle("alertOwnCatches")
        val alertOtherCatches = spec.toggle("alertOtherCatches")
        val creatureName = spec.toggle("creatureName")
        val playSound = spec.toggle("playSound")
        val announceRareInParty = spec.toggle("announceRareInParty")
        val highlight = spec.toggle("highlight")
        val highlightColor = spec.color("highlightColor")
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SEEN_TTL_NANOS = 6L * 60L * 1_000_000_000L
        private const val DOUBLE_HOOK_WINDOW_NANOS = 100_000_000L
        private const val OWN_TITLE_TICKS = 30
        private const val NEARBY_TITLE_TICKS = 30
        private const val SCAN_TICKS = 10
        private const val SCAN_RANGE = 96.0
        private const val HIGHLIGHT_RANGE = 30.0
        private const val LABEL_TO_MOB_RADIUS = 3.0
        private const val BOX_LINE_WIDTH = 2.0f

        @JvmField
        val INSTANCE = SeaCreatureAlertModule(Settings())
    }
}
