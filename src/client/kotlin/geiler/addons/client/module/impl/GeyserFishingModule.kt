package geiler.addons.client.module.impl

import geiler.addons.client.fishing.GeyserRules
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/**
 * Marks the Crimson Isle trophy-fishing geyser, after SkyHanni's Geyser Fishing: the geyser is found from its cloud
 * particles, drawn as a thin four-block slab at y 117.9 within 96 blocks, and its particles can be hidden next to the
 * bobber so the bobber stays visible.
 */
class GeyserFishingModule private constructor(settings: Settings) : Module(
    "Geyser Fishing",
    "Marks the trophy fishing geyser on the Crimson Isle.",
    Category.FISHING,
    false,
    settings.drawBox,
    settings.boxColor,
    settings.onlyWithRod,
    settings.hideParticles,
) {
    private val drawBox = settings.drawBox
    private val boxColor = settings.boxColor
    private val onlyWithRod = settings.onlyWithRod
    private val hideParticles = settings.hideParticles

    private val diagnostics = ModuleDiagnostics(Category.FISHING, { name() }, 8, 100L, 300)
    private var geyser: Vec3? = null
    private var lastLevel: ClientLevel? = null
    private var hiddenParticles = 0
    private var seenParticles = 0
    private var drawn = 0

    override fun isActive(): Boolean = isEnabled() && HypixelModApi.currentIsland() == Island.CRIMSON_ISLE

    override fun inactiveReason(): String? {
        if (!isEnabled() || isActive()) return null
        return HypixelModApi.reasonNotOn(Island.CRIMSON_ISLE)
    }

    override fun onEnable() {
        reset()
        diagnostics.enable(
            "box=${drawBox.value()} onlyWithRod=${onlyWithRod.value()} hideParticles=${hideParticles.value()}",
        )
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    /** Records a geyser particle; returns true when the packet should be dropped. Runs on the client thread. */
    fun onParticle(packet: ClientboundLevelParticlesPacket): Boolean {
        if (!isActive() || !(drawBox.value() || hideParticles.value())) return false
        if (packet.particle.type != ParticleTypes.CLOUD) return false
        if (!GeyserRules.matches(packet.count, packet.maxSpeed, packet.xDist, packet.yDist, packet.zDist)) return false
        val location = Vec3(packet.x, packet.y, packet.z)
        if (geyser == null) diagnostics.event("GEYSER x=${location.x.toInt()} y=${location.y.toInt()} z=${location.z.toInt()}")
        geyser = location
        seenParticles++
        if (!hideParticles.value()) return false
        val bobber = Minecraft.getInstance().player?.fishing ?: return false
        val hide = GeyserRules.hides(bobber.position().distanceTo(location))
        if (hide) hiddenParticles++
        return hide
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val level = Minecraft.getInstance().level
        if (level !== lastLevel) {
            lastLevel = level
            geyser = null
        }
        diagnostics.status {
            "enabled=true active=${isActive()} geyser=${geyser != null} seenParticles=$seenParticles " +
                "hiddenParticles=$hiddenParticles drawn=$drawn suppressedEvents=$it"
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isActive() || !drawBox.value()) return
        val location = geyser ?: return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val dx = location.x - player.x
        val dz = location.z - player.z
        if (!GeyserRules.inDrawRange(Math.sqrt(dx * dx + dz * dz))) return
        if (onlyWithRod.value() && !player.mainHandItem.`is`(Items.FISHING_ROD)) return
        val box = GeyserRules.box(location.x, location.z)
        val camPos = client.gameRenderer.mainCamera().position()
        val buffer = WorldRenderBuffer.current()
        val argb = boxColor.argb()
        EspRenderer.renderBox(
            context.poseStack(), buffer,
            box[0] - camPos.x, box[1] - camPos.y, box[2] - camPos.z,
            box[3] - box[0], box[4] - box[1], box[5] - box[2],
            argb, argb, LINE_WIDTH,
        )
        GeilerAddonsRenderTypes.endBatches(buffer)
        drawn++
    }

    private fun reset() {
        geyser = null
        hiddenParticles = 0
        seenParticles = 0
        drawn = 0
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        private val spec = ModuleSettingsSpec.of("Geyser Fishing")
        val drawBox = spec.toggle("drawBox")
        val boxColor = spec.color("boxColor")
        val onlyWithRod = spec.toggle("onlyWithRod")
        val hideParticles = spec.toggle("hideParticles")
    }

    companion object {
        private const val LINE_WIDTH = 2.0f

        @JvmField
        val INSTANCE = GeyserFishingModule(Settings())
    }
}
