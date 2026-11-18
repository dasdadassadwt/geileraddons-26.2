package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Boxes Hideonwall critters in the Critter Safari through walls. They hide flush against a wall, so unlike Critter Markers'
 * fixed spots this only ever marks a Hideonwall the client actually has, never a place one might be. Which critter is a
 * Hideonwall comes from the same labelled-entity sweep Critter Tracker and Critter Markers share.
 */
class HideonwallEspModule private constructor(private val s: Settings) : Module(
    "Hideonwall ESP",
    "Boxes Hideonwall critters through walls in the Critter Safari.",
    Category.HUNTING,
    s.color, s.labels,
) {
    class Settings {
        val color = ColorSetting("Box Color", 170, 85, 255, 255)
        val labels = BooleanSetting("Show Labels", true)
    }

    private val diagnostics = ModuleDiagnostics(Category.HUNTING, { name() }, 8, 100L, 300)
    private var found = 0
    private var drawn = 0
    private var everFound = 0

    override fun isActive(): Boolean = isEnabled() && CritterSafari.inSafari()
    override fun inactiveReason(): String? = if (isEnabled() && !isActive()) "Not in the Critter Safari" else null

    override fun onEnable() {
        found = 0
        drawn = 0
        everFound = 0
        diagnostics.enable("labels=${s.labels.value()} colorArgb=${s.color.argb()} through=walls")
    }

    override fun onDisable() = diagnostics.disable()

    /** The boxes to draw now: each Hideonwall sighting's body, or its name tag's box grown a little when there is no body. */
    private fun boxes(): List<AABB> {
        if (!isActive()) return emptyList()
        val out = ArrayList<AABB>()
        for (sighting in CritterSafari.sightings()) {
            if (sighting.critter.name != HIDEONWALL) continue
            out += sighting.body?.boundingBox ?: sighting.label.boundingBox.inflate(0.4)
        }
        return out
    }

    fun render(context: LevelRenderContext) {
        val boxes = boxes()
        found = boxes.size
        if (boxes.isEmpty()) {
            drawn = 0
            return
        }
        val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
        val pose = context.poseStack()
        val buffer = WorldRenderBuffer.current()
        val line = s.color.argb()
        val fill = (line and 0x00FFFFFF) or (0x40 shl 24)
        for (box in boxes) {
            EspRenderer.renderBox(
                pose, buffer, box.minX - camera.x, box.minY - camera.y, box.minZ - camera.z,
                box.xsize, box.ysize, box.zsize, fill, line, 2.0f, false,
            )
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
        drawn = boxes.size
    }

    /** The name and distance above each box, in the real font. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isActive() || !s.labels.value()) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.level == null || mc.gui.hud.isHidden()) return
        val camera = mc.gameRenderer.mainCamera()
        for (box in boxes()) {
            val centre = box.center
            val text = "$HIDEONWALL ${player.position().distanceTo(centre).toInt()}m"
            ProjectedLabelRenderer.draw(graphics, camera, mc.font, Vec3(centre.x, box.maxY + 0.4, centre.z), text, s.color.argb(), 1.0f)
        }
    }

    /** Call every client tick while enabled. Counts only. */
    fun diagnose() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (found > everFound) {
            everFound = found
            diagnostics.event("FOUND hideonwalls=$found")
        }
        diagnostics.statusOnChangeBy(20L, { "$found$drawn" }) {
            "active=${isActive()} hideonwalls=$found drawn=$drawn mostAtOnce=$everFound labels=${s.labels.value()}"
        }
    }

    companion object {
        private const val HIDEONWALL = "Hideonwall"

        @JvmField
        val INSTANCE = HideonwallEspModule(Settings())
    }
}
