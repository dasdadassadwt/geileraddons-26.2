package geiler.addons.client.module.impl

import geiler.addons.client.fishing.HotspotRules
import geiler.addons.client.fishing.ParticlePathFitter
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.phys.Vec3

/**
 * Finds a Fishing Hotspot from the Hotspot Radar's flame particles, after SkyHanni's Fishing Hotspot Radar: the
 * particle path of one radar use is fitted with a cubic and extrapolated to the hotspot, which is then marked with
 * a label, its distance, and an optional line from the crosshair. The mark clears within 10 blocks.
 */
class HotspotRadarModule private constructor(settings: Settings) : Module(
    "Hotspot Radar",
    "Guesses where the Hotspot Radar points and marks it.",
    Category.FISHING,
    false,
    settings.lineToHotspot,
) {
    private val lineToHotspot = settings.lineToHotspot

    private val diagnostics = ModuleDiagnostics(Category.FISHING, { name() }, 8, 100L, 300)
    private val fitter = ParticlePathFitter()
    private var hotspot: Vec3? = null
    private var lastAbilityNanos = Long.MIN_VALUE / 2
    private var lastLevel: ClientLevel? = null
    private var particles = 0
    private var rejectedGuesses = 0

    override fun onEnable() {
        reset()
        diagnostics.enable("line=${lineToHotspot.value()}")
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    /** Runs on the client thread for every particle packet. */
    fun onParticle(packet: ClientboundLevelParticlesPacket) {
        if (!isEnabled() || !SkyblockItems.inSkyblock()) return
        if (!HotspotRules.matches(packet.particle.type == ParticleTypes.FLAME, packet.count, packet.maxSpeed)) return
        particles++
        val now = System.nanoTime()
        // Other flame particles (torches, mobs) must not count as a running sample, or they would eat radar clicks.
        if (now - lastAbilityNanos <= HotspotRules.RADAR_WINDOW_NANOS) lastParticleNanos = now
        if (!fitter.tryAdd(packet.x, packet.y, packet.z, HotspotRules.MAX_STEP, lastAbilityNanos, now)) return
        val guess = fitter.solve() ?: return
        val player = Minecraft.getInstance().player ?: return
        val spot = Vec3(guess[0], guess[1], guess[2])
        // Without island bounds data, a guess far from the player is a stretched fit, not a real hotspot.
        if (spot.distanceTo(player.position()) > MAX_GUESS_DISTANCE) {
            rejectedGuesses++
            hotspot = null
            diagnostics.event("GUESS_REJECTED points=${fitter.count()}")
            return
        }
        if (hotspot == null) diagnostics.event("HOTSPOT points=${fitter.count()} x=${spot.x.toInt()} y=${spot.y.toInt()} z=${spot.z.toInt()}")
        hotspot = spot
    }

    /** A radar use starts a new sample; the click is cancelled when it lands inside a running sample. */
    private fun onItemUse(): InteractionResult {
        if (!isEnabled() || !SkyblockItems.inSkyblock()) return InteractionResult.PASS
        val player = Minecraft.getInstance().player ?: return InteractionResult.PASS
        val held = SkyblockItems.skyBlockId(player.mainHandItem)
        if (held != RADAR_ID) return InteractionResult.PASS
        val now = System.nanoTime()
        if (now - lastParticleNanos < RADAR_RECLICK_NANOS) {
            diagnostics.event("RADAR_RECLICK_CANCELLED")
            return InteractionResult.FAIL
        }
        fitter.reset()
        lastAbilityNanos = now
        diagnostics.event("RADAR_USED heldId=$held particlesSoFar=$particles")
        return InteractionResult.PASS
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val level = Minecraft.getInstance().level
        if (level !== lastLevel) {
            lastLevel = level
            reset()
        }
        diagnostics.status {
            "enabled=true hotspot=${hotspot != null} points=${fitter.count()} particles=$particles " +
                "rejectedGuesses=$rejectedGuesses suppressedEvents=$it"
        }
    }

    /** The optional crosshair line, through walls. */
    fun render(context: LevelRenderContext) {
        val spot = hotspot ?: return
        if (!isEnabled() || !lineToHotspot.value()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        if (spot.distanceTo(player.eyePosition) <= HotspotRules.REACHED_DISTANCE) return
        val camera = client.gameRenderer.mainCamera()
        val camPos = camera.position()
        val forward = camera.forwardVector()
        val buffer = WorldRenderBuffer.current()
        EspRenderer.renderLine(
            context.poseStack(), buffer,
            forward.x() * LINE_START, forward.y() * LINE_START, forward.z() * LINE_START,
            spot.x - camPos.x, spot.y - camPos.y, spot.z - camPos.z,
            LINE_COLOR, LINE_WIDTH,
        )
        GeilerAddonsRenderTypes.endBatches(buffer)
    }

    /** The label and distance above the hotspot; clears the mark once the player is within 10 blocks. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        val spot = hotspot ?: return
        if (!isEnabled()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val distance = spot.distanceTo(player.eyePosition)
        if (distance <= HotspotRules.REACHED_DISTANCE) {
            diagnostics.event("REACHED distance=${distance.toInt()}")
            hotspot = null
            fitter.reset()
            return
        }
        val camera = client.gameRenderer.mainCamera()
        // Both lines are placed exactly from one projected point (no collision shuffling, no distance-dependent height),
        // so the number cannot jump while the player moves; the metre count only changes when its text does.
        val anchor = spot.add(0.0, 1.7, 0.0)
        ProjectedLabelRenderer.drawFloatingText(graphics, camera, client.font, anchor, "§d§lHOTSPOT", WHITE, 1.7f)
        ProjectedLabelRenderer.drawFloatingText(
            graphics, camera, client.font, anchor, HotspotRules.distanceText(distance), WHITE, 1.0f,
            DISTANCE_GAP_PIXELS,
        )
    }

    private fun reset() {
        hotspot = null
        fitter.reset()
        particles = 0
        rejectedGuesses = 0
        lastAbilityNanos = Long.MIN_VALUE / 2
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        private val spec = ModuleSettingsSpec.of("Hotspot Radar")
        val lineToHotspot = spec.toggle("lineToHotspot")
    }

    companion object {
        private const val RADAR_ID = "HOTSPOT_RADAR"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val LINE_COLOR = 0xFFFF55FF.toInt()
        private const val LINE_WIDTH = 3.0f
        private const val LINE_START = 0.5
        private const val MAX_GUESS_DISTANCE = 1000.0
        private const val DISTANCE_GAP_PIXELS = 14f
        private const val RADAR_RECLICK_NANOS = 200_000_000L

        @Volatile
        private var lastParticleNanos = Long.MIN_VALUE / 2

        @JvmField
        val INSTANCE = HotspotRadarModule(Settings())

        init {
            UseItemCallback.EVENT.register { _, level, _: InteractionHand ->
                if (level.isClientSide) INSTANCE.onItemUse() else InteractionResult.PASS
            }
        }
    }
}
