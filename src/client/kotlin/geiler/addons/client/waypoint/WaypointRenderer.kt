package geiler.addons.client.waypoint

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.phys.Vec3

/** Drawing shared by the Waypoints and Chat Waypoints modules. */
object WaypointRenderer {
    /** One drawable waypoint, camera independent. */
    class Target(
        val bx: Int, val by: Int, val bz: Int,
        val label: String, val rgb: Int, val alpha: Float,
        val type: WaypointType, val throughWalls: Boolean,
        val role: Int, val number: Int,
        val showText: Boolean,
    )

    private fun argb(alpha: Int, rgb: Int): Int = (alpha shl 24) or (rgb and 0xFFFFFF)

    /** Boxes and beams, plus a line to the next route waypoint when [lineWidth] is above zero. */
    fun draw(context: LevelRenderContext, targets: List<Target>, maxDistance: Double, lineArgb: Int, lineWidth: Float) {
        if (targets.isEmpty()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        if (client.level == null) return
        val camera = client.gameRenderer.mainCamera().position()
        val pose: PoseStack = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        val limitSquared = maxDistance * maxDistance
        var drew = false
        var next: Target? = null
        for (t in targets) {
            if (player.distanceToSqr(t.bx + 0.5, t.by + 0.5, t.bz + 0.5) > limitSquared) continue
            val fill = if (t.type.fill) argb((t.alpha * 255f).toInt().coerceIn(0, 255), t.rgb) else 0
            val outline = if (t.type.outline) argb(255, t.rgb) else 0
            val depth = !t.throughWalls
            EspRenderer.renderBox(
                pose, buffers, t.bx - camera.x, t.by - camera.y, t.bz - camera.z, 1.0, 1.0, 1.0,
                fill, outline, 2.5f, depth,
            )
            if (t.type.beam) {
                val height = (320.0 - t.by - 1.0).coerceAtLeast(1.0)
                EspRenderer.renderBox(
                    pose, buffers, t.bx + 0.375 - camera.x, t.by + 1.0 - camera.y, t.bz + 0.375 - camera.z, 0.25, height, 0.25,
                    argb((t.alpha * 255f).toInt().coerceIn(40, 200), t.rgb), 0, 0f, depth,
                )
            }
            if (t.role == OrderedWaypointRules.NEXT) next = t
            drew = true
        }
        val target = next
        if (lineWidth > 0f && target != null) {
            val look = player.getViewVector(1.0f)
            EspRenderer.renderLine(
                pose, buffers, look.x, look.y, look.z,
                target.bx + 0.5 - camera.x, target.by + 0.5 - camera.y, target.bz + 0.5 - camera.z,
                lineArgb, lineWidth, false,
            )
            drew = true
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    /** Floating name and distance labels; a target whose group hides its text gets none. */
    fun drawLabels(
        graphics: GuiGraphicsExtractor, targets: List<Target>, maxDistance: Double, showDistance: Boolean, textScale: Float,
    ) {
        if (targets.isEmpty()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val limitSquared = maxDistance * maxDistance
        for (t in targets) {
            if (!t.showText) continue
            val distanceSquared = player.distanceToSqr(t.bx + 0.5, t.by + 0.5, t.bz + 0.5)
            if (distanceSquared > limitSquared) continue
            val parts = ArrayList<String>(2)
            val name = if (t.number > 0) (if (t.label.isBlank()) "${t.number}" else "${t.number} ${t.label}") else t.label
            if (name.isNotBlank()) parts.add(name)
            if (showDistance) parts.add("${Math.sqrt(distanceSquared).toInt()}m")
            if (parts.isEmpty()) continue
            ProjectedLabelRenderer.drawFloatingText(
                graphics, client.gameRenderer.mainCamera(), client.font,
                Vec3(t.bx + 0.5, t.by + 1.5, t.bz + 0.5), parts.joinToString(" "), argb(255, t.rgb), textScale,
            )
        }
    }
}
