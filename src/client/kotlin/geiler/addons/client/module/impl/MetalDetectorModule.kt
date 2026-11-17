package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.mining.MetalDetectorRules
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/**
 * Finds the Mines of Divan treasure from the metal detector's distance readout, ported 1:1 from SkyHanni's Metal
 * Detector Solver. Hold the detector, stand still, and the chest spots at that exact distance are marked; move and
 * read again until only one is left.
 */
class MetalDetectorModule private constructor(settings: Settings) : Module(
    "Metal Detector",
    "Marks the Mines of Divan treasure chest spots that match the metal detector's distance.",
    Category.MINING,
    false,
    settings.stopWhenAllTools, settings.showTimeTaken, settings.pling,
) {
    private val stopWhenAllTools = settings.stopWhenAllTools
    private val showTimeTaken = settings.showTimeTaken
    private val pling = settings.pling

    private val diagnostics = ModuleDiagnostics(Category.MINING, { name() }, 8, 100L, 400)
    private val offsets: List<MetalDetectorRules.Pos> by lazy { loadOffsets() }
    private var base: MetalDetectorRules.Pos? = null
    private var predicted: List<MetalDetectorRules.Pos> = emptyList()
    private var ignore: MetalDetectorRules.Pos? = null
    private var lastSearchNanos = Long.MIN_VALUE / 2
    private var lastPlayerKey = ""
    private var playedPling = false
    private var lastFoundNanos = Long.MIN_VALUE / 2
    private var lastNotice = ""
    private var inDivan = false
    private var divanTick = Long.MIN_VALUE
    private var tick = 0L
    private var readings = 0L
    private var lastLevelId = 0

    init {
        group(SettingGroup("Metal Detector", stopWhenAllTools, showTimeTaken, pling))
    }

    private fun settingsText(): String =
        "stopWhenAllTools=${stopWhenAllTools.value()};timeTaken=${showTimeTaken.value()};pling=${pling.value()}"

    override fun onEnable() {
        resetState()
        readings = 0
        diagnostics.enable(settingsText() + ";offsets=${offsets.size}")
    }

    override fun onDisable() {
        resetState()
        diagnostics.disable()
    }

    private fun resetState() {
        base = null
        predicted = emptyList()
        ignore = null
        lastSearchNanos = Long.MIN_VALUE / 2
        lastPlayerKey = ""
        playedPling = false
        lastFoundNanos = Long.MIN_VALUE / 2
        lastNotice = ""
        divanTick = Long.MIN_VALUE
    }

    /** Called for every chat line and action bar line; [overlay] is true for the action bar. */
    fun onMessage(plain: String, overlay: Boolean) {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { onMessage(plain, overlay) }
            return
        }
        if (!inMinesOfDivan(mc)) return
        if (overlay) onActionBar(mc, plain) else onChat(mc, plain)
    }

    private fun onActionBar(mc: Minecraft, plain: String) {
        if (predicted.size == 1) return
        val distance = MetalDetectorRules.parseDistance(plain) ?: return
        if (stopWhenAllTools.value() && hasAllTools(mc)) return
        val player = mc.player ?: return
        val key = "${player.x},${player.y},${player.z}"
        if (key != lastPlayerKey) {
            lastPlayerKey = key
            playedPling = false
        }
        readings++
        val now = System.nanoTime()
        if (base == null) findBase(mc, now)
        val anchor = base ?: return
        val spots = MetalDetectorRules.candidates(anchor, offsets, ignore, distance, player.x, player.y, player.z)
        if (spots.isNotEmpty() && predicted.isEmpty() && !playedPling && pling.value()) {
            mc.level?.playLocalSound(player.x, player.y, player.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 1f, 1f, false)
            playedPling = true
        }
        predicted = spots
        diagnostics.event("READING distance=$distance candidates=${spots.size} base=$anchor")
        if (spots.size > 1 || spots.isEmpty()) {
            // Right after a find the readout is stale; SkyHanni stays quiet for half a second.
            if (now - lastFoundNanos < FOUND_GRACE_NANOS) return
        }
        val message = when (spots.size) {
            1 -> "Found a treasure chest location"
            0 -> "No chests found. Try standing still with the metal detector in a different spot."
            else -> "${spots.size} possible locations found. Please try standing still with the metal detector in a different spot."
        }
        notice(mc, message)
    }

    private fun onChat(mc: Minecraft, plain: String) {
        if (!MetalDetectorRules.isTreasureFound(plain)) return
        playedPling = false
        predicted = emptyList()
        val now = System.nanoTime()
        val taken = now - lastFoundNanos
        diagnostics.event("TREASURE_FOUND tookSeconds=${if (lastFoundNanos < 0) -1 else taken / 1_000_000_000L}")
        if (showTimeTaken.value() && lastFoundNanos > 0) {
            notice(mc, "You found the treasure in ${taken / 1_000_000_000L} seconds.", force = true)
        }
        lastFoundNanos = now
    }

    private fun notice(mc: Minecraft, text: String, force: Boolean = false) {
        if (!force && text == lastNotice) return
        lastNotice = text
        mc.player?.sendSystemMessage(Component.literal("§e[Metal Detector] §f$text"))
    }

    fun tick() {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        diagnostics.advance()
        tick++
        val levelId = System.identityHashCode(mc.level)
        if (levelId != lastLevelId) {
            lastLevelId = levelId
            resetState()
        }
        val player = mc.player
        if (player != null && predicted.size == 1) {
            val spot = predicted[0]
            if (player.distanceToSqr(spot.x.toDouble(), spot.y.toDouble(), spot.z.toDouble()) <= 25.0) {
                ignore = spot
                predicted = emptyList()
                diagnostics.event("SPOT_REACHED spot=$spot")
            }
        } else if (player != null && predicted.isEmpty()) {
            val skip = ignore
            if (skip != null && player.distanceToSqr(skip.x.toDouble(), skip.y.toDouble(), skip.z.toDouble()) > 100.0) ignore = null
        }
        diagnostics.statusOnChange {
            "divan=$inDivan base=${base ?: "none"} predicted=${predicted.size} readings=$readings ignore=${ignore ?: "none"}"
        }
    }

    private fun inMinesOfDivan(mc: Minecraft): Boolean {
        if (mc.level == null || mc.player == null || !MiningContext.on(Island.CRYSTAL_HOLLOWS)) return false
        if (tick - divanTick >= DIVAN_REFRESH_TICKS || divanTick == Long.MIN_VALUE) {
            divanTick = tick
            inDivan = MiningContext.sidebarLines().any { it.contains("Mines of Divan") }
        }
        return inDivan
    }

    private fun hasAllTools(mc: Minecraft): Boolean {
        val inventory = mc.player?.inventory ?: return false
        val ids = HashSet<String>()
        for (slot in 0 until inventory.containerSize) ids.add(SkyblockItems.skyBlockId(inventory.getItem(slot)))
        return MetalDetectorRules.hasAllTools(ids)
    }

    /** Scans around the player for the base point at most once every 15 seconds, like SkyHanni. */
    private fun findBase(mc: Minecraft, now: Long) {
        if (now - lastSearchNanos < SEARCH_COOLDOWN_NANOS) return
        lastSearchNanos = now
        val level = mc.level ?: return
        val player = mc.player ?: return
        val cursor = BlockPos.MutableBlockPos()
        val found = MetalDetectorRules.findBase(Math.round(player.x).toInt(), Math.round(player.y).toInt(), Math.round(player.z).toInt()) { x, y, z ->
            cursor.set(x, y, z)
            if (!level.hasChunkAt(cursor)) {
                MetalDetectorRules.Block.OTHER
            } else {
                val block = level.getBlockState(cursor).block
                when (block) {
                    Blocks.QUARTZ_STAIRS -> MetalDetectorRules.Block.QUARTZ_STAIRS
                    Blocks.BARRIER -> MetalDetectorRules.Block.BARRIER
                    else -> MetalDetectorRules.Block.OTHER
                }
            }
        }
        base = found
        diagnostics.event("BASE_SEARCH found=${found ?: "none"}")
    }

    /** A box, a line from the crosshair and a tall marker on each candidate, through walls. */
    fun render(context: LevelRenderContext) {
        if (!isEnabled() || predicted.isEmpty()) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera()
        val cam = camera.position()
        val forward = camera.forwardVector()
        val buffer = WorldRenderBuffer.current()
        val pose = context.poseStack()
        for (spot in predicted) {
            EspRenderer.renderBox(
                pose, buffer, spot.x - cam.x, spot.y - cam.y, spot.z - cam.z, 1.0, 1.0, 1.0,
                0x40FFAA00, 0xFFFFAA00.toInt(), LINE_WIDTH,
            )
            EspRenderer.renderBox(
                pose, buffer, spot.x + 0.35 - cam.x, spot.y + 1.0 - cam.y, spot.z + 0.35 - cam.z, 0.3, 60.0, 0.3,
                0x30FF5555, 0xFFFF5555.toInt(), 1.5f,
            )
            EspRenderer.renderLine(
                pose, buffer, forward.x() * 0.5, forward.y() * 0.5, forward.z() * 0.5,
                spot.x + 0.5 - cam.x, spot.y + 0.5 - cam.y, spot.z + 0.5 - cam.z, 0xFFFFFFFF.toInt(), LINE_WIDTH,
            )
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || predicted.isEmpty()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val camera = client.gameRenderer.mainCamera()
        for (spot in predicted) {
            val distance = Math.sqrt(player.distanceToSqr(spot.x + 0.5, spot.y + 0.5, spot.z + 0.5))
            ProjectedLabelRenderer.draw(
                graphics, camera, client.font, Vec3(spot.x + 0.5, spot.y + 2.0, spot.z + 0.5),
                "Treasure: ${MetalDetectorRules.distanceText(distance)}", 0xFFFFFF55.toInt(), 1.5f,
            )
        }
    }

    private fun loadOffsets(): List<MetalDetectorRules.Pos> =
        try {
            MetalDetectorModule::class.java.getResourceAsStream(DATA)!!.use {
                MetalDetectorRules.parseOffsets(String(it.readAllBytes(), Charsets.UTF_8))
            }
        } catch (failure: Exception) {
            diagnostics.record("DATA_LOAD_FAILED ${failure.javaClass.simpleName}")
            emptyList()
        }

    private class Settings {
        val stopWhenAllTools = BooleanSetting("Stop When All Tools", false)
        val showTimeTaken = BooleanSetting("Show Time Taken", true)
        val pling = BooleanSetting("Pling On Match", true)
    }

    companion object {
        private const val DATA = "/geileraddons/mining/metal_detector_chests.json"
        private const val LINE_WIDTH = 3.0f
        private const val FOUND_GRACE_NANOS = 500_000_000L
        private const val SEARCH_COOLDOWN_NANOS = 15_000_000_000L
        private const val DIVAN_REFRESH_TICKS = 20L

        @JvmField
        val INSTANCE = MetalDetectorModule(Settings())
    }
}
