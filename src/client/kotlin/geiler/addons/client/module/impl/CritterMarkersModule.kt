package geiler.addons.client.module.impl

import geiler.addons.client.hunting.BeeStatus
import geiler.addons.client.hunting.MarkerKind
import geiler.addons.client.hunting.MarkerRules
import geiler.addons.client.hunting.WallState
import geiler.addons.client.location.Island
import geiler.addons.client.location.SafariBiome
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Boxes the fixed Critter Safari spots worth walking to, and what has been found: Snooper and Troodon walls, the
 * eleven bee spots, Rockmite mounds, Hunter trade spots, Bloodbats and Duplico. Hideonwalls have their own module.
 *
 * Preset positions are map knowledge and are boxed through terrain. Anything read out of the world (a wall's
 * state, a bee nest, a mound, a critter) is only used once there is a clear line to it, and Bloodbats are boxed only
 * where they stand in view: that helps spot something you could see anyway rather than reporting what is behind a
 * wall. Duplico is the one exception, boxed through terrain by request, since it looks like a block until found. Behaviour follows Critter Mod (MrCloudy2, MIT); the code is written independently.
 */
class CritterMarkersModule private constructor(private val s: Settings) : Module(
    "Critter Markers",
    "Boxes Critter Safari walls, bee spots, mounds, Hunter trades, Bloodbats and Duplico.",
    Category.HUNTING,
    s.snooperWalls, s.snooperColor, s.troodonWalls, s.troodonColor, s.beeSpots, s.beeSpotColor, s.beeNestColor,
    s.mounds, s.moundColor, s.trades, s.tradeColor,
    s.bloodbats, s.bloodbatColor, s.duplico, s.duplicoColor, s.labels,
) {
    class Settings {
        val snooperWalls = BooleanSetting("Snooper Walls", false)
        val snooperColor = ColorSetting("Snooper Wall Color", 255, 170, 0, 255)
        val troodonWalls = BooleanSetting("Troodon Walls", false)
        val troodonColor = ColorSetting("Troodon Wall Color", 85, 170, 255, 255)
        val beeSpots = BooleanSetting("Bee Spots", false)
        val beeSpotColor = ColorSetting("Bee Spot Color", 85, 255, 85, 255)
        val beeNestColor = ColorSetting("Bee Nest Color", 255, 204, 34, 255)
        val mounds = BooleanSetting("Rockmite Mounds", false)
        val moundColor = ColorSetting("Mound Color", 204, 119, 68, 255)
        val trades = BooleanSetting("Hunter Trades", false)
        val tradeColor = ColorSetting("Trade Color", 85, 255, 255, 255)
        val bloodbats = BooleanSetting("Bloodbat Boxes", true)
        val bloodbatColor = ColorSetting("Bloodbat Color", 255, 85, 85, 255)
        val duplico = BooleanSetting("Duplico Boxes", true)
        val duplicoColor = ColorSetting("Duplico Color", 255, 85, 255, 255)
        val labels = BooleanSetting("Show Labels", true)
    }

    /** One thing to draw. [depth] true hides it behind terrain; false draws it through. */
    class Marker(val box: AABB, val label: String, val argb: Int, val depth: Boolean)

    private val diagnostics = ModuleDiagnostics(Category.HUNTING, { name() }, 8, 100L, 480)

    init {
        // Folded sections keep the page short: only the critters you box live are open by default.
        group(
            SettingGroup.folded("Walls", s.snooperWalls, s.snooperColor, s.troodonWalls, s.troodonColor),
            SettingGroup.folded("Bee Spots", s.beeSpots, s.beeSpotColor, s.beeNestColor),
            SettingGroup.folded("Rockmite Mounds", s.mounds, s.moundColor),
            SettingGroup.folded("Hunter Trades", s.trades, s.tradeColor),
            SettingGroup("Critters", s.bloodbats, s.bloodbatColor, s.duplico, s.duplicoColor),
            SettingGroup.folded("Display", s.labels),
        )
    }

    private var lastCount = 0
    private var lastSubmitted = 0
    private var lastLabels = 0

    override fun isActive(): Boolean = isEnabled() && CritterSafari.inSafari()
    override fun inactiveReason(): String? = if (isEnabled() && !isActive()) "Not in the Critter Safari" else null

    fun wantsWalls(): Boolean = isEnabled() && (s.snooperWalls.value() || s.troodonWalls.value())
    fun wantsBees(): Boolean = isEnabled() && s.beeSpots.value()
    fun wantsMounds(): Boolean = isEnabled() && s.mounds.value()

    override fun onEnable() {
        lastCount = 0
        lastSubmitted = 0
        diagnostics.enable(
            "snooper=${s.snooperWalls.value()} troodon=${s.troodonWalls.value()} bees=${s.beeSpots.value()} mounds=${s.mounds.value()} " +
                "trades=${s.trades.value()} bloodbats=${s.bloodbats.value()} duplico=${s.duplico.value()} labels=${s.labels.value()}",
        )
    }

    override fun onDisable() = diagnostics.disable()

    /** What to draw right now. Each preset only shows in its own biome; trades and Bloodbats show anywhere in the Safari. */
    fun collect(): List<Marker> {
        if (!isActive()) return emptyList()
        val biome = SafariBiome.current()
        val out = ArrayList<Marker>()
        val w = CritterSafariWorld

        fun wallMarkers(on: Boolean, kind: MarkerKind, set: CritterSafariWorld.WallSet, name: String, color: ColorSetting) {
            if (!on || !MarkerRules.shows(kind, biome)) return
            for (i in set.pos.indices) {
                // Everything but BROKEN: the positions are preset, and only having looked and found it gone should drop one.
                if (set.states[i] != WallState.BROKEN) out += Marker(AABB(set.pos[i]), name, color.argb(), false)
            }
        }
        wallMarkers(s.snooperWalls.value(), MarkerKind.SNOOPER_WALL, w.snooper, "Snooper wall", s.snooperColor)
        wallMarkers(s.troodonWalls.value(), MarkerKind.TROODON_WALL, w.troodon, "Troodon wall", s.troodonColor)

        if (s.beeSpots.value() && MarkerRules.shows(MarkerKind.BEE_SPOT, biome)) {
            for (i in w.beePos.indices) {
                when (w.beeStatus[i]) {
                    BeeStatus.CLEARED -> Unit
                    BeeStatus.NEST -> out += Marker(AABB(w.beePos[i]), "Bee nest", s.beeNestColor.argb(), false)
                    BeeStatus.UNCHECKED -> out += Marker(AABB(w.beePos[i]), "Bee spot", s.beeSpotColor.argb(), false)
                }
            }
        }
        if (s.mounds.value() && MarkerRules.shows(MarkerKind.MOUND, biome)) {
            for (pos in w.mounds) out += Marker(AABB(pos), "Mound", s.moundColor.argb(), false)
        }
        if (s.trades.value()) {
            for (trade in CritterSafari.trades.all()) {
                val spot = trade.spot ?: continue
                out += Marker(AABB(BlockPos(spot.x, spot.y, spot.z)), trade.offer.critter.name, s.tradeColor.argb(), false)
            }
        }
        val boxed = boxedCritters()
        if (boxed.isNotEmpty()) {
            for (sighting in CritterSafari.sightings()) {
                val name = sighting.critter.name
                if (name !in boxed) continue
                if (name == "Duplico") {
                    // Duplico hides as an ordinary block, so it is boxed through terrain even before you can see it (as Skyblocker does);
                    // without a body entity the name tag's own box, grown a little, stands in for it.
                    out += Marker(sighting.body?.boundingBox ?: sighting.label.boundingBox.inflate(0.4), name, s.duplicoColor.argb(), false)
                    continue
                }
                // A Bloodbat's name tag alone gives nothing to box; the mob under it is the thing, and only where it can be seen.
                val body = sighting.body ?: continue
                if (!sighting.visible) continue
                out += Marker(body.boundingBox, name, s.bloodbatColor.argb(), true)
            }
        }
        return out
    }

    /** Critters boxed: Bloodbats where they stand in view, Duplico (which disguises itself as an ordinary block) even while hidden. */
    fun boxedCritters(): List<String> = buildList {
        if (s.bloodbats.value()) add("Bloodbat")
        if (s.duplico.value()) add("Duplico")
    }

    fun render(context: LevelRenderContext) {
        if (!isActive()) return
        val markers = collect()
        lastCount = markers.size
        if (markers.isEmpty()) {
            lastSubmitted = 0
            return
        }
        val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
        val pose = context.poseStack()
        val buffer = WorldRenderBuffer.current()
        var submitted = 0
        for (m in markers) {
            val fill = (m.argb and 0x00FFFFFF) or (0x40 shl 24)
            EspRenderer.renderBox(
                pose, buffer, m.box.minX - camera.x, m.box.minY - camera.y, m.box.minZ - camera.z,
                m.box.xsize, m.box.ysize, m.box.zsize, fill, m.argb, 2.0f, m.depth,
            )
            submitted++
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
        lastSubmitted = submitted
    }

    /** Name and distance above each box, in the real font (in-world text does not render from any reachable level stage). */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isActive() || !s.labels.value()) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.level == null || mc.gui.hud.isHidden()) return
        val camera = mc.gameRenderer.mainCamera()
        var drawn = 0
        for (m in collect()) {
            val centre = m.box.center
            val at = Vec3(centre.x, m.box.maxY + 0.4, centre.z)
            val text = "${m.label} ${player.position().distanceTo(centre).toInt()}m"
            if (ProjectedLabelRenderer.draw(graphics, camera, mc.font, at, text, m.argb, 1.0f)) drawn++
        }
        lastLabels = drawn
    }

    /** Call every client tick while enabled. Counts and states only; no coordinates or names. */
    fun diagnose() {
        if (!isEnabled()) return
        diagnostics.advance()
        val w = CritterSafariWorld
        diagnostics.statusOnChangeBy(20L, { "$lastCount$lastSubmitted${w.moundCount()}${w.snooper.remaining()}${w.troodon.remaining()}" }) {
            "active=${isActive()} island=${if (CritterSafari.inSafari()) Island.SAFARI.name else "other"} biome=${SafariBiome.current()?.name ?: "none"} " +
                "markers=$lastCount submittedBoxes=$lastSubmitted labels=$lastLabels mounds=${w.moundCount()} " +
                "snooperLeft=${w.snooper.remaining()} troodonLeft=${w.troodon.remaining()} " +
                "bees=${w.beeStatus.count { it == BeeStatus.NEST }}nest/${w.beeStatus.count { it == BeeStatus.CLEARED }}cleared " +
                "trades=${CritterSafari.trades.all().size}"
        }
    }

    companion object {
        @JvmField
        val INSTANCE = CritterMarkersModule(Settings())
    }
}
