package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.entity.Nameplates
import geiler.addons.client.hunting.CritterSpecies
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.ChatFormatting
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3fc
import java.util.ArrayList
import java.util.HashSet
import java.util.UUID

/**
 * Boxes sparkling critters in the Critter Safari, and can call one out to the party.
 *
 * A sparkling critter is found by reading name tags, not by any hidden state: Hypixel names the
 * label above one [Sparkling Rockmite] rather than [Rockmite], and a name tag already renders
 * through terrain in vanilla. This points out something that was on screen anyway.
 *
 * The label is not the critter. Hypixel floats it on a separate entity, so each sighting is
 * resolved back to the mob underneath before anything is drawn around it. The search for that mob
 * skips players, or a sparkling standing next to someone could box the player.
 */
class SparklingCritterModule private constructor(settings: Settings) : Module(
    "Sparkling Critter",
    "Finds sparkling critters in the Critter Safari.",
    Category.HUNTING,
    settings.color,
    settings.textSize,
    settings.tracer,
    settings.rescanTime,
    settings.debug,
    settings.announce,
    settings.rangeToSend,
) {
    private val color = settings.color
    private val textSize = settings.textSize
    private val tracer = settings.tracer
    private val rescanTime = settings.rescanTime
    private val debug = settings.debug
    private val announce = settings.announce
    private val rangeToSend = settings.rangeToSend

    /** What the last sweep found; rebuilt wholesale rather than diffed. */
    private val sightings = ArrayList<Sighting>()

    /**
     * Labels already called out, so a critter is announced once rather than every sweep.
     * The label is the key because it is the thing that named the critter.
     */
    private val announced = HashSet<UUID>()
    private var ticksSinceScan = 0
    private var wasActive = false
    private var lastLevel: ClientLevel? = null

    private var diagnosticTick = 0L
    private var lastDiagnosticTick = Long.MIN_VALUE
    private var lastDiagnosticState = ""
    private var lastObservedDiagnosticState = ""
    private var suppressedDiagnosticChanges = 0
    private var lastScanSummary = "not-run"
    private var lastCallOutSummary = "not-run"
    private var lastWorldRender = "not-rendered"
    private var lastHudRender = "not-rendered"

    init {
        // Flat settings remain the persistence source of truth; these groups retain the saved GUI
        // ordering and labels.
        group(
            SettingGroup("Highlight", settings.color, settings.textSize, settings.tracer, settings.rescanTime),
            SettingGroup("Announcements", settings.debug, settings.announce, settings.rangeToSend),
        )
    }

    override fun isActive(): Boolean =
        isEnabled() && HypixelModApi.currentIsland() == Island.SAFARI

    override fun inactiveReason(): String? {
        if (!isEnabled() || isActive()) return null
        return HypixelModApi.reasonNotOn(Island.SAFARI)
    }

    override fun onEnable() {
        diagnosticTick = 0
        lastDiagnosticTick = diagnosticTick
        lastDiagnosticState = "enabled=true;awaiting=client-tick"
        lastObservedDiagnosticState = lastDiagnosticState
        suppressedDiagnosticChanges = 0
        lastScanSummary = "not-run"
        lastCallOutSummary = "not-run"
        lastWorldRender = "not-rendered"
        lastHudRender = "not-rendered"
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.HUNTING, name(), true)
        writeDiagnostic("STATUS $lastDiagnosticState;rescanTicks=${rescanTime.intValue()}")
    }

    override fun onDisable() {
        val clearedSightings = sightings.size
        val clearedAnnouncements = announced.size
        reset()
        writeDiagnostic(
            "STATUS enabled=false;reason=module-disabled;clearedSightings=$clearedSightings;" +
                "clearedAnnouncements=$clearedAnnouncements",
        )
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.HUNTING, name(), false)
    }

    fun tick() {
        if (!isEnabled()) return
        diagnosticTick++
        val mc = Minecraft.getInstance()

        if (!isActive()) {
            // Leaving the Safari ends the run, and the next one's sparklings are its own.
            if (wasActive) {
                wasActive = false
                reset()
            }
            reportDiagnostics(false, mc)
            return
        }
        wasActive = true

        val level = mc.level
        val player = mc.player
        if (level !== lastLevel) {
            lastLevel = level
            reset()
        }
        if (level == null || player == null) {
            reportDiagnostics(true, mc)
            return
        }

        // Catching one removes the entity, so its box goes without waiting for the next sweep.
        sightings.removeIf { sighting -> sighting.body.isRemoved() || !sighting.body.isAlive() }

        if (++ticksSinceScan < rescanTime.intValue()) {
            reportDiagnostics(true, mc)
            return
        }
        ticksSinceScan = 0
        scan(level, player)
        callOut(player)
        reportDiagnostics(true, mc)
    }

    private fun reset() {
        sightings.clear()
        announced.clear()
        ticksSinceScan = 0
        wasActive = false
        lastScanSummary = "not-run"
        lastCallOutSummary = "not-run"
        lastWorldRender = "not-rendered"
        lastHudRender = "not-rendered"
    }

    // ---- scanning -----------------------------------------------------------------------

    private fun scan(level: ClientLevel, player: LocalPlayer) {
        val range = minOf(Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0, MAX_RANGE.toDouble())
        val area = AABB.ofSize(player.position(), range * 2, range * 2, range * 2)
        val nearby = level.getEntities(
            EntityTypeTest.forClass(Entity::class.java),
            area,
        ) { entity -> entity !== player }

        val labels = ArrayList<Entity>()
        val species = ArrayList<String>()
        val bodies = ArrayList<Entity>()
        for (entity in nearby) {
            if (entity.hasCustomName()) {
                val rare = CritterSpecies.sparkling(entity.getCustomName()?.getString())
                if (rare != null) {
                    labels.add(entity)
                    species.add(rare)
                    continue
                }
            }
            if (isMobLike(entity)) bodies.add(entity)
        }

        sightings.clear()
        var unpaired = 0
        for (index in labels.indices) {
            val label = labels[index]
            val body = pair(bodies, label)
            if (body != null) {
                sightings.add(Sighting(species[index], label, body))
            } else {
                unpaired++
            }
        }

        val sample = sightings.take(MAX_DIAGNOSTIC_SPECIES).joinToString(",") { it.species }.ifEmpty { "-" }
        lastScanSummary =
            "range=${range.toInt()};nearby=${nearby.size};labels=${labels.size};bodies=${bodies.size};" +
                "paired=${sightings.size};unpaired=$unpaired;species=$sample"
    }

    /**
     * The scaffolding Hypixel builds its props out of, and players.
     *
     * Players are excluded as bodies but not as labels: a Hideyho arrives as a fake player and
     * names itself, so it can be its own body while never being borrowed by a nearby critter.
     */
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

    /** The nearest mob under a label, or the label itself when it is the mob. */
    private fun pair(bodies: List<Entity>, label: Entity): Entity? {
        var best: Entity? = null
        var bestDistance = LABEL_TO_MOB_RADIUS * LABEL_TO_MOB_RADIUS
        for (body in bodies) {
            val distance = body.position().distanceToSqr(label.position())
            if (distance >= bestDistance) continue
            bestDistance = distance
            best = body
        }
        if (best != null) return best
        // A marker with no hitbox of its own would produce an invisible box.
        return if (Nameplates.hasBody(label)) label else null
    }

    // ---- announcing ---------------------------------------------------------------------

    /**
     * Tells the party about anything new that is close enough.
     *
     * An out-of-range sparkling stays eligible, so walking toward it can call it out later.
     */
    private fun callOut(player: LocalPlayer) {
        if (!announce.value() && !debug.value()) {
            lastCallOutSummary = "gate=disabled;eligible=0"
            return
        }

        val range = SparklingCritterDecisions.announcementRange(rangeToSend.value())
        var outOfRange = 0
        var alreadyAnnounced = 0
        var partySubmitted = 0
        var localPreviewSubmitted = 0
        var localPreviewSkipped = 0
        val sample = ArrayList<String>()
        for (sighting in sightings) {
            val distance = player.position().distanceTo(sighting.body.position())
            if (!SparklingCritterDecisions.isWithinAnnouncementRange(distance, range)) {
                outOfRange++
                continue
            }
            if (!announced.add(sighting.label.getUUID())) {
                alreadyAnnounced++
                continue
            }

            val pos: BlockPos = sighting.body.blockPosition()
            val message = SparklingCritterDecisions.announcementMessage(
                sighting.species,
                pos.getX(),
                pos.getY(),
                pos.getZ(),
            )
            if (sample.size < MAX_DIAGNOSTIC_SPECIES) sample.add(sighting.species)
            if (debug.value()) {
                // Debug previews locally and never sends, even when party announcements are on.
                if (chat(message)) localPreviewSubmitted++ else localPreviewSkipped++
            } else {
                player.connection.sendCommand(SparklingCritterDecisions.partyCommand(message))
                partySubmitted++
            }
        }
        val species = sample.joinToString(",").ifEmpty { "-" }
        lastCallOutSummary =
            "gate=enabled;range=$range;outOfRange=$outOfRange;already=$alreadyAnnounced;" +
                "partySubmitted=$partySubmitted;localPreview=$localPreviewSubmitted;" +
                "previewSkipped=$localPreviewSkipped;species=$species"
    }

    private fun chat(message: String): Boolean {
        val gui = Minecraft.getInstance().gui ?: return false
        gui.hud.getChat().addClientSystemMessage(
            Component.literal("[Sparkling] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(message).withStyle(ChatFormatting.WHITE)),
        )
        return true
    }

    private fun reportDiagnostics(active: Boolean, mc: Minecraft) {
        val island = HypixelModApi.currentIsland()?.name?.lowercase() ?: "unknown"
        val levelState = if (mc.level == null) "missing" else "present"
        val playerState = if (mc.player == null) "missing" else "present"
        val state =
            "enabled=true;active=$active;island=$island;level=$levelState;player=$playerState;" +
                "scan=$lastScanSummary;sightings=${sightings.size};announced=${announced.size};" +
                "callout=$lastCallOutSummary;world=$lastWorldRender;hud=$lastHudRender;" +
                "settings=color=${color.argb()},text=${textSize.value()},tracer=${tracer.value()}," +
                "rescan=${rescanTime.intValue()},announce=${announce.value()},debug=${debug.value()}," +
                "range=${SparklingCritterDecisions.announcementRange(rangeToSend.value())}"

        if (state != lastObservedDiagnosticState) {
            if (lastObservedDiagnosticState.isNotEmpty() &&
                (lastDiagnosticTick == Long.MIN_VALUE ||
                    diagnosticTick - lastDiagnosticTick < MIN_DIAGNOSTIC_INTERVAL_TICKS)
            ) {
                suppressedDiagnosticChanges++
            }
            lastObservedDiagnosticState = state
        }

        val firstStatus = lastDiagnosticTick == Long.MIN_VALUE
        val heartbeat = firstStatus || diagnosticTick - lastDiagnosticTick >= DIAGNOSTIC_HEARTBEAT_TICKS
        val changed = state != lastDiagnosticState
        if (!changed && !heartbeat) return
        if (!heartbeat && !firstStatus &&
            diagnosticTick - lastDiagnosticTick < MIN_DIAGNOSTIC_INTERVAL_TICKS
        ) {
            return
        }

        writeDiagnostic("STATUS $state;suppressedChanges=$suppressedDiagnosticChanges")
        lastDiagnosticTick = diagnosticTick
        lastDiagnosticState = state
        suppressedDiagnosticChanges = 0
    }

    private fun writeDiagnostic(line: String) {
        GeilerAddonsLog.write(
            Category.HUNTING,
            name(),
            diagnosticTick.coerceAtLeast(0L),
            line.take(MAX_DIAGNOSTIC_LINE_LENGTH),
        )
    }

    // ---- rendering ----------------------------------------------------------------------

    /** Drawn through walls: a sparkling behind a rock is exactly the one worth pointing at. */
    fun render(context: LevelRenderContext) {
        if (!isActive()) {
            lastWorldRender = "skipped-inactive"
            return
        }
        if (sightings.isEmpty()) {
            lastWorldRender = "skipped-no-sightings"
            return
        }

        val camera: Camera = Minecraft.getInstance().gameRenderer.mainCamera()
        val camPos = camera.position()
        val poseStack: PoseStack = context.poseStack()
        val bufferSource = WorldRenderBuffer.current()
        val argb = color.argb()
        val forward: Vector3fc = camera.forwardVector()

        var boxesSubmitted = 0
        var tracersSubmitted = 0
        for (sighting in sightings) {
            val box = sighting.body.getBoundingBox()
            EspRenderer.renderBox(
                poseStack,
                bufferSource,
                box.minX - camPos.x,
                box.minY - camPos.y,
                box.minZ - camPos.z,
                box.getXsize(),
                box.getYsize(),
                box.getZsize(),
                argb,
                argb,
                BOX_LINE_WIDTH,
            )
            boxesSubmitted++

            if (tracer.value()) {
                val center = box.getCenter()
                EspRenderer.renderLine(
                    poseStack,
                    bufferSource,
                    forward.x() * TRACER_START_DISTANCE,
                    forward.y() * TRACER_START_DISTANCE,
                    forward.z() * TRACER_START_DISTANCE,
                    center.x - camPos.x,
                    center.y - camPos.y,
                    center.z - camPos.z,
                    argb,
                    TRACER_WIDTH,
                )
                tracersSubmitted++
            }
        }
        GeilerAddonsRenderTypes.endBatches(bufferSource)
        lastWorldRender = "submittedBoxes=$boxesSubmitted;submittedTracers=$tracersSubmitted"
    }

    /**
     * The species name above each sighting, in the real font.
     *
     * Projected onto the HUD rather than drawn in the world because in-world text does not render
     * from any level stage reachable here.
     */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isActive()) {
            lastHudRender = "skipped-inactive"
            return
        }
        if (sightings.isEmpty()) {
            lastHudRender = "skipped-no-sightings"
            return
        }
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) {
            lastHudRender = "skipped-no-client-context"
            return
        }
        if (mc.gui.hud.isHidden()) {
            lastHudRender = "skipped-hidden-hud"
            return
        }

        val camera = mc.gameRenderer.mainCamera()
        val scale = textSize.value()
        val argb = color.argb()
        var attempted = 0
        var submitted = 0
        var removed = 0
        for (sighting in sightings) {
            if (sighting.body.isRemoved()) {
                removed++
                continue
            }
            val label = SparklingCritterDecisions.hudLabel(sighting.species)
            val box = ProjectedLabelRenderer.entityBox(sighting.body)
            val world = Vec3(box.getCenter().x, box.maxY + LABEL_HEIGHT, box.getCenter().z)
            attempted++
            if (ProjectedLabelRenderer.draw(graphics, camera, mc.font, world, label, argb, scale)) {
                submitted++
            }
        }
        lastHudRender = "attempted=$attempted;submitted=$submitted;removed=$removed"
    }

    private data class Sighting(val species: String, val label: Entity, val body: Entity)

    private class Settings {
        val color = ColorSetting("Color", 255, 200, 0, 255)
        val textSize = NumberSetting("Text Size", 0.5f, 4.0f, 1.0f)
        val tracer = BooleanSetting("Tracer", true)

        /** Ticks between sweeps; the default is once a second. */
        val rescanTime = NumberSetting("Rescan Time", 1f, 200f, 20f, true)

        /** Shows what would be sent instead of sending; it never sends while enabled. */
        val debug = BooleanSetting.debug("Debug", false)
        val announce = BooleanSetting("Announce to Party Chat", false)

        /** How close a sparkling has to be before the party is told, in blocks. */
        val rangeToSend = TextSetting("Range to Send", "5", MAX_RANGE_TEXT_LENGTH)
    }

    companion object {
        @JvmField
        val INSTANCE = SparklingCritterModule(Settings())

        /** Past this there is nothing to look at anyway, whatever the view distance. */
        private const val MAX_RANGE = 128

        /** The label sits directly above its mob, so the pairing radius can be tight. */
        private const val LABEL_TO_MOB_RADIUS = 3.0

        private const val BOX_LINE_WIDTH = 2.0f
        private const val TRACER_WIDTH = 2.0f

        /** Started a little ahead of the camera, or the line is a dot in the middle of the screen. */
        private const val TRACER_START_DISTANCE = 1.0

        /** Clear of the critter's own nameplate rather than fighting it for the same pixels. */
        private const val LABEL_HEIGHT = 0.4

        private const val MAX_RANGE_TEXT_LENGTH = 6
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 480
        private const val MAX_DIAGNOSTIC_SPECIES = 4
        private const val MIN_DIAGNOSTIC_INTERVAL_TICKS = 20L
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L
    }
}

/** Pure announcement decisions and output formatting, callable from the focused Java behavior check. */
object SparklingCritterDecisions {
    const val DEFAULT_ANNOUNCEMENT_RANGE = 5.0
    const val MAX_ANNOUNCEMENT_RANGE = 128.0

    @JvmStatic
    fun hudLabel(species: String): String = "Sparkling $species"

    @JvmStatic
    fun announcementMessage(species: String, x: Int, y: Int, z: Int): String =
        "Sparkling %s found at x:%d y:%d z:%d".format(species, x, y, z)

    @JvmStatic
    fun partyCommand(message: String): String = "pc $message"

    @JvmStatic
    fun announcementRange(raw: String?): Double {
        val parsed = raw?.trim()?.toDoubleOrNull() ?: return DEFAULT_ANNOUNCEMENT_RANGE
        if (!parsed.isFinite()) return DEFAULT_ANNOUNCEMENT_RANGE
        return parsed.coerceIn(0.0, MAX_ANNOUNCEMENT_RANGE)
    }

    /** Preserves the original inclusive upper bound used by party callouts. */
    @JvmStatic
    fun isWithinAnnouncementRange(distance: Double, range: Double): Boolean = !(distance > range)
}
