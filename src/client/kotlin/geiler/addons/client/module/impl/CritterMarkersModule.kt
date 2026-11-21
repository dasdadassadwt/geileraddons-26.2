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
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Boxes the fixed Critter Safari spots worth walking to, and what has been found: Snooper and Troodon walls, the
 * eleven bee spots, Rockmite mounds, Hunter trade spots, Bloodbats, Duplico, Hideonwalls and Hideonfloors.
 *
 * Preset positions are map knowledge. Anything read out of the world (a wall's state, a bee nest, a mound, a critter)
 * is only used once there is a clear line to it, and Bloodbats are boxed only where they stand in view: that helps spot
 * something you could see anyway rather than reporting what is behind a wall. Duplico and Hideonwall are the
 * exceptions, boxed from the entities the client has even while hidden, since they pass for scenery until found; so is
 * the Hideonfloor, a green shulker box block in the Forest. Every box obeys one Depth Check setting, off by default so
 * boxes show through terrain. Behaviour follows Critter Mod (MrCloudy2, MIT); the code is written independently.
 */
class CritterMarkersModule private constructor(private val s: Settings) : Module(
    "Critter Markers",
    "Boxes Critter Safari walls, bee spots, mounds, Hunter trades, Bloodbats, Duplico, Hideonwalls and Hideonfloors.",
    Category.HUNTING,
    s.snooperWalls, s.snooperColor, s.troodonWalls, s.troodonColor, s.beeSpots, s.beeSpotColor, s.beeNestColor,
    s.mounds, s.moundColor, s.trades, s.tradeColor,
    s.bloodbats, s.bloodbatColor, s.duplico, s.duplicoColor, s.hideonwall, s.hideonwallColor, s.hideonfloor, s.hideonfloorColor,
    s.labels, s.depthCheck,
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
        val hideonwall = BooleanSetting("Hideonwall Boxes", true)
        val hideonwallColor = ColorSetting("Hideonwall Color", 170, 85, 255, 255)
        val hideonfloor = BooleanSetting("Hideonfloor Boxes", true)
        val hideonfloorColor = ColorSetting("Hideonfloor Color", 85, 255, 85, 255)
        val labels = BooleanSetting("Show Labels", true)
        val depthCheck = BooleanSetting("Depth Check", false)
    }

    /** One thing to draw. Whether it hides behind terrain is the module's single Depth Check. */
    class Marker(val box: AABB, val label: String, val argb: Int)

    private val diagnostics = ModuleDiagnostics(Category.HUNTING, { name() }, 8, 100L, 640)

    init {
        // Folded sections keep the page short: only the critters you box live are open by default.
        group(
            SettingGroup.folded("Walls", s.snooperWalls, s.snooperColor, s.troodonWalls, s.troodonColor),
            SettingGroup.folded("Bee Spots", s.beeSpots, s.beeSpotColor, s.beeNestColor),
            SettingGroup.folded("Rockmite Mounds", s.mounds, s.moundColor),
            SettingGroup.folded("Hunter Trades", s.trades, s.tradeColor),
            SettingGroup(
                "Critters", s.bloodbats, s.bloodbatColor, s.duplico, s.duplicoColor,
                s.hideonwall, s.hideonwallColor, s.hideonfloor, s.hideonfloorColor,
            ),
            SettingGroup.folded("Display", s.labels, s.depthCheck),
        )
    }

    private var lastCount = 0
    private var lastSubmitted = 0
    private var lastLabels = 0
    private var floors: List<BlockPos> = emptyList()
    private var floorTicks = 0
    private var everFloors = 0

    override fun isActive(): Boolean = isEnabled() && CritterSafari.inSafari()
    override fun inactiveReason(): String? = if (isEnabled() && !isActive()) "Not in the Critter Safari" else null

    fun wantsWalls(): Boolean = isEnabled() && (s.snooperWalls.value() || s.troodonWalls.value())
    fun wantsBees(): Boolean = isEnabled() && s.beeSpots.value()
    fun wantsMounds(): Boolean = isEnabled() && s.mounds.value()

    override fun onEnable() {
        lastCount = 0
        lastSubmitted = 0
        floors = emptyList()
        floorTicks = 0
        everFloors = 0
        diagnostics.enable(
            "snooper=${s.snooperWalls.value()} troodon=${s.troodonWalls.value()} bees=${s.beeSpots.value()} mounds=${s.mounds.value()} " +
                "trades=${s.trades.value()} bloodbats=${s.bloodbats.value()} duplico=${s.duplico.value()} " +
                "hideonwall=${s.hideonwall.value()} hideonfloor=${s.hideonfloor.value()} labels=${s.labels.value()} depthCheck=${s.depthCheck.value()}",
        )
    }

    override fun onDisable() {
        floors = emptyList()
        diagnostics.disable()
    }

    /**
     * Call every client tick while enabled. Finds green shulker boxes the client holds as block entities (the Hideonfloor
     * is one); like Trapped Chest ESP it reads block entities rather than every block, and only runs in the Forest.
     */
    fun tick() {
        if (!isActive() || !s.hideonfloor.value() || !MarkerRules.shows(MarkerKind.HIDEONFLOOR, SafariBiome.current())) {
            floors = emptyList()
            return
        }
        if (floorTicks++ % FLOOR_SCAN_INTERVAL != 0) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val chunkRadius = (FLOOR_RANGE shr 4) + 1
        val limit = FLOOR_RANGE.toDouble() * FLOOR_RANGE
        val found = ArrayList<BlockPos>()
        for (cx in (player.blockX shr 4) - chunkRadius..(player.blockX shr 4) + chunkRadius) {
            for (cz in (player.blockZ shr 4) - chunkRadius..(player.blockZ shr 4) + chunkRadius) {
                val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
                for (position in chunk.blockEntities.keys) {
                    if (found.size >= MAX_FLOORS) break
                    if (player.distanceToSqr(position.x + 0.5, position.y + 0.5, position.z + 0.5) > limit) continue
                    if (level.getBlockState(position).`is`(Blocks.DYED_SHULKER_BOX.green())) found.add(position.immutable())
                }
            }
        }
        floors = found
    }

    /** What to draw right now. Each preset only shows in its own biome; trades, Bloodbats and Hideonwalls show anywhere in the Safari. */
    fun collect(): List<Marker> {
        if (!isActive()) return emptyList()
        val biome = SafariBiome.current()
        val out = ArrayList<Marker>()
        val w = CritterSafariWorld

        fun wallMarkers(on: Boolean, kind: MarkerKind, set: CritterSafariWorld.WallSet, name: String, color: ColorSetting) {
            if (!on || !MarkerRules.shows(kind, biome)) return
            for (i in set.pos.indices) {
                // Everything but BROKEN: the positions are preset, and only having looked and found it gone should drop one.
                if (set.states[i] != WallState.BROKEN) out += Marker(AABB(set.pos[i]), name, color.argb())
            }
        }
        wallMarkers(s.snooperWalls.value(), MarkerKind.SNOOPER_WALL, w.snooper, "Snooper wall", s.snooperColor)
        wallMarkers(s.troodonWalls.value(), MarkerKind.TROODON_WALL, w.troodon, "Troodon wall", s.troodonColor)

        if (s.beeSpots.value() && MarkerRules.shows(MarkerKind.BEE_SPOT, biome)) {
            for (i in w.beePos.indices) {
                when (w.beeStatus[i]) {
                    BeeStatus.CLEARED -> Unit
                    BeeStatus.NEST -> out += Marker(AABB(w.beePos[i]), "Bee nest", s.beeNestColor.argb())
                    BeeStatus.UNCHECKED -> out += Marker(AABB(w.beePos[i]), "Bee spot", s.beeSpotColor.argb())
                }
            }
        }
        if (s.mounds.value() && MarkerRules.shows(MarkerKind.MOUND, biome)) {
            for (pos in w.mounds) out += Marker(AABB(pos), "Mound", s.moundColor.argb())
        }
        if (s.hideonfloor.value() && MarkerRules.shows(MarkerKind.HIDEONFLOOR, biome)) {
            for (pos in floors) out += Marker(AABB(pos), "Hideonfloor", s.hideonfloorColor.argb())
        }
        if (s.trades.value()) {
            for (trade in CritterSafari.trades.all()) {
                val spot = trade.spot ?: continue
                out += Marker(AABB(BlockPos(spot.x, spot.y, spot.z)), trade.offer.critter.name, s.tradeColor.argb())
            }
        }
        val boxed = boxedCritters()
        if (boxed.isNotEmpty()) {
            for (sighting in CritterSafari.sightings()) {
                val name = sighting.critter.name
                if (name !in boxed) continue
                if (name == "Duplico" || name == "Hideonwall") {
                    // These pass for scenery (a block, a flat wall critter), so they are boxed even before you can see them (as Skyblocker
                    // does for Duplico); without a body entity the name tag's own box, grown a little, stands in for it.
                    val color = if (name == "Duplico") s.duplicoColor else s.hideonwallColor
                    out += Marker(
                        sighting.body?.let { ProjectedLabelRenderer.entityBox(it) } ?: ProjectedLabelRenderer.entityBox(sighting.label).inflate(0.4),
                        name, color.argb(),
                    )
                    continue
                }
                // A Bloodbat's name tag alone gives nothing to box; the mob under it is the thing, and only where it can be seen.
                val body = sighting.body ?: continue
                if (!sighting.visible) continue
                out += Marker(ProjectedLabelRenderer.entityBox(body), name, s.bloodbatColor.argb())
            }
        }
        return out
    }

    /** Critters boxed: Bloodbats where they stand in view, Duplico (which disguises itself as an ordinary block) and Hideonwall even while hidden. */
    fun boxedCritters(): List<String> = buildList {
        if (s.bloodbats.value()) add("Bloodbat")
        if (s.duplico.value()) add("Duplico")
        if (s.hideonwall.value()) add("Hideonwall")
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
        val depth = s.depthCheck.value()
        for (m in markers) {
            val fill = (m.argb and 0x00FFFFFF) or (0x40 shl 24)
            EspRenderer.renderBox(
                pose, buffer, m.box.minX - camera.x, m.box.minY - camera.y, m.box.minZ - camera.z,
                m.box.xsize, m.box.ysize, m.box.zsize, fill, m.argb, 2.0f, depth,
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
        if (floors.size > everFloors) {
            everFloors = floors.size
            diagnostics.event("FOUND hideonfloors=${floors.size}")
        }
        diagnostics.statusOnChangeBy(20L, { "$lastCount$lastSubmitted${w.moundCount()}${w.snooper.remaining()}${w.troodon.remaining()}${floors.size}" }) {
            "active=${isActive()} island=${if (CritterSafari.inSafari()) Island.SAFARI.name else "other"} biome=${SafariBiome.current()?.name ?: "none"} " +
                "markers=$lastCount submittedBoxes=$lastSubmitted labels=$lastLabels labelSlotChanges=${ProjectedLabelRenderer.slotChanges} depthCheck=${s.depthCheck.value()} " +
                "hideonfloors=${floors.size} mostFloorsAtOnce=$everFloors hideonwall=${s.hideonwall.value()} mounds=${w.moundCount()} " +
                "snooperLeft=${w.snooper.remaining()} troodonLeft=${w.troodon.remaining()} " +
                "bees=${w.beeStatus.count { it == BeeStatus.NEST }}nest/${w.beeStatus.count { it == BeeStatus.CLEARED }}cleared " +
                "trades=${CritterSafari.trades.all().size}"
        }
    }

    companion object {
        private const val FLOOR_SCAN_INTERVAL = 10
        private const val FLOOR_RANGE = 64
        private const val MAX_FLOORS = 64

        @JvmField
        val INSTANCE = CritterMarkersModule(Settings())
    }
}
