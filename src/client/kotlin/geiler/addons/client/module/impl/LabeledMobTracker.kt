package geiler.addons.client.module.impl

import geiler.addons.client.entity.Nameplates
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Finds mobs by the name tag Hypixel floats above them and draws a box, tracer and world label on each. The label is a
 * separate entity, so each match is paired with the nearest mob body under it (same rule as Sparkling Critter).
 * [classify] turns a label into a tag (or null to skip it).
 */
class LabeledMobTracker<T : Any>(private val classify: (Component) -> T?) {
    class Sighting<T>(val tag: T, val label: Entity, val body: Entity)

    val sightings = ArrayList<Sighting<T>>()
    var lastScan = "not-run"
        private set

    fun clear() = sightings.clear()

    fun prune() {
        sightings.removeIf { it.body.isRemoved || !it.body.isAlive }
    }

    fun scan(level: ClientLevel, player: LocalPlayer, maxRange: Double) {
        val range = minOf(Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0, maxRange)
        val area = AABB.ofSize(player.position(), range * 2, range * 2, range * 2)
        val nearby = level.getEntities(EntityTypeTest.forClass(Entity::class.java), area) { it !== player }
        val labels = ArrayList<Pair<Entity, T>>()
        val bodies = ArrayList<Entity>()
        for (entity in nearby) {
            val name = entity.customName
            val tag = if (name != null) classify(name) else null
            if (tag != null) labels.add(entity to tag) else if (isMobLike(entity)) bodies.add(entity)
        }
        sightings.clear()
        for ((label, tag) in labels) {
            val body = pair(bodies, label) ?: continue
            sightings.add(Sighting(tag, label, body))
        }
        lastScan = "range=${range.toInt()};nearby=${nearby.size};labels=${labels.size};paired=${sightings.size}"
    }

    fun render(context: LevelRenderContext, tracer: Boolean, color: (T) -> Int): Int {
        if (sightings.isEmpty()) return 0
        val camera = Minecraft.getInstance().gameRenderer.mainCamera()
        val cam = camera.position()
        val forward = camera.forwardVector()
        val poseStack = context.poseStack()
        val buffer = WorldRenderBuffer.current()
        for (s in sightings) {
            val box = s.body.boundingBox
            val argb = color(s.tag)
            EspRenderer.renderBox(poseStack, buffer, box.minX - cam.x, box.minY - cam.y, box.minZ - cam.z,
                box.xsize, box.ysize, box.zsize, argb, argb, 2.0f)
            if (tracer) {
                val c = box.center
                EspRenderer.renderLine(poseStack, buffer, forward.x().toDouble(), forward.y().toDouble(), forward.z().toDouble(),
                    c.x - cam.x, c.y - cam.y, c.z - cam.z, argb, 2.0f)
            }
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
        return sightings.size
    }

    /** World labels, projected through the one shared projector (26.2 has no reachable world text stage). */
    fun renderLabels(graphics: GuiGraphicsExtractor, text: (Sighting<T>) -> String, color: (T) -> Int): Int {
        val mc = Minecraft.getInstance()
        if (sightings.isEmpty() || mc.level == null || mc.player == null || mc.gui.hud.isHidden()) return 0
        val camera = mc.gameRenderer.mainCamera()
        var drawn = 0
        for (s in sightings) {
            if (s.body.isRemoved) continue
            val box = ProjectedLabelRenderer.entityBox(s.body)
            val world = Vec3(box.center.x, box.maxY + 0.4, box.center.z)
            if (ProjectedLabelRenderer.draw(graphics, camera, mc.font, world, text(s), color(s.tag), 1.0f)) drawn++
        }
        return drawn
    }

    private fun isMobLike(entity: Entity): Boolean {
        val type = entity.type
        return type !== EntityTypes.ARMOR_STAND && type !== EntityTypes.INTERACTION && type !== EntityTypes.ITEM_DISPLAY &&
            type !== EntityTypes.BLOCK_DISPLAY && type !== EntityTypes.TEXT_DISPLAY && type !== EntityTypes.PLAYER &&
            type !== EntityTypes.ITEM && Nameplates.hasBody(entity)
    }

    private fun pair(bodies: List<Entity>, label: Entity): Entity? {
        var best: Entity? = null
        var bestDistance = LABEL_TO_MOB_RADIUS * LABEL_TO_MOB_RADIUS
        for (body in bodies) {
            val d = body.position().distanceToSqr(label.position())
            if (d < bestDistance) {
                bestDistance = d
                best = body
            }
        }
        return best ?: if (Nameplates.hasBody(label)) label else null
    }

    private companion object {
        const val LABEL_TO_MOB_RADIUS = 3.0
    }
}
