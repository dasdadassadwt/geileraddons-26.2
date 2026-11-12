package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.entity.Nameplates
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.location.SafariBiome
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.ClientAsset
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.sqrt

/** Finds and boxes the Hideyho wherever it appears in Critter Safari. */
class HideyhoFinderModule private constructor(settings: Settings) : Module(
    "Hideyho Finder",
    "Finds the Hideyho in the Critter Safari.",
    Category.HUNTING,
    settings.color,
    settings.scanInterval,
    settings.debugMode,
) {
    private val color = settings.color
    private val scanInterval = settings.scanInterval
    private val debugMode = settings.debugMode

    /** The Hideyho as of the last sweep; cleared by a sweep that no longer finds it. */
    private var found: Entity? = null

    /** Every fake player the last sweep looked at, kept only while Debug Mode is effective. */
    private val candidates = ArrayList<Entity>()
    private var ticksSinceScan = 0
    private var wasActive = false
    private var lastLevel: ClientLevel? = null

    private var diagnosticTick = 0L
    private var lastDiagnosticTick = Long.MIN_VALUE
    private var lastDiagnosticState = ""
    private var suppressedDiagnosticChanges = 0
    private var detailsThisTick = 0
    private var suppressedDetails = 0
    private var scansSinceStatus = 0
    private var lastScan = "not-run"
    private var lastRender = "not-rendered"

    private constructor() : this(Settings())

    override fun isActive(): Boolean {
        if (!isEnabled() || HypixelModApi.currentIsland() != Island.SAFARI) return false
        val biome = SafariBiome.current()
        return biome == null || biome == SafariBiome.HAUNTED
    }

    override fun inactiveReason(): String? {
        if (!isEnabled() || isActive()) return null
        val islandReason = HypixelModApi.reasonNotOn(Island.SAFARI)
        return islandReason ?: "Not in the ${SafariBiome.HAUNTED.displayName()}"
    }

    override fun onEnable() {
        diagnosticTick = 0L
        lastDiagnosticTick = Long.MIN_VALUE
        lastDiagnosticState = ""
        suppressedDiagnosticChanges = 0
        detailsThisTick = 0
        suppressedDetails = 0
        scansSinceStatus = 0
        lastScan = "not-run"
        lastRender = "not-rendered"
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.HUNTING, name(), true)
        writeDiagnostic("FEATURE_ENABLED ${diagnosticSettings()}")
    }

    override fun onDisable() {
        val hadFound = found != null
        val candidateCount = candidates.size
        reset()
        writeDiagnostic("FEATURE_DISABLED clearedFound=$hadFound clearedDebugCandidates=$candidateCount")
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.HUNTING, name(), false)
    }

    private fun reset() {
        found = null
        candidates.clear()
        ticksSinceScan = 0
        wasActive = false
    }

    /** Called once per client tick on the client thread. */
    fun tick() {
        if (!isEnabled()) {
            if (wasActive) {
                wasActive = false
                reset()
            }
            return
        }

        val gate = readGate()
        if (diagnosticTick < Long.MAX_VALUE) diagnosticTick++
        detailsThisTick = 0

        if (!gate.active) {
            if (wasActive) {
                wasActive = false
                reset()
            }
            val minecraft = Minecraft.getInstance()
            reportDiagnostics(gate, minecraft.level, minecraft.player)
            return
        }
        wasActive = true

        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val player = minecraft.player
        if (level !== lastLevel) {
            lastLevel = level
            reset()
        }
        if (level == null || player == null) {
            reportDiagnostics(gate, level, player)
            return
        }

        // Killing it removes the entity, so the box goes without waiting for the next sweep.
        val currentFound = found
        if (currentFound != null && (currentFound.isRemoved() || !currentFound.isAlive())) {
            found = null
        }
        if (++ticksSinceScan >= scanInterval.intValue()) {
            ticksSinceScan = 0
            scan(level, player)
        }
        reportDiagnostics(gate, level, player)
    }

    /** Only the Haunted side of Safari; an unknown biome deliberately keeps scanning. */
    private fun readGate(): Gate {
        val island = HypixelModApi.currentIsland()
        if (island != Island.SAFARI) return Gate(island, null, biomeRead = false, active = false)
        val biome = SafariBiome.current()
        return Gate(island, biome, biomeRead = true, active = biome == null || biome == SafariBiome.HAUNTED)
    }

    // ---- scanning -----------------------------------------------------------------------

    private fun scan(level: ClientLevel, player: LocalPlayer) {
        val renderDistanceChunks = Minecraft.getInstance().options.getEffectiveRenderDistance()
        val range = minOf(renderDistanceChunks * 16.0, MAX_RANGE.toDouble())
        val at = player.position()
        val area = AABB.ofSize(at, range * 2, range * 2, range * 2)

        candidates.clear()
        if (debugMode.value()) {
            candidates.addAll(level.getEntities(EntityTypeTest.forClass(AbstractClientPlayer::class.java), area) {
                it !== player
            })
        }

        var nearbyEntities = 0
        var aliveEntities = 0
        var fakePlayers = 0
        var textureMatches = 0
        var nameMatches = 0
        var textureOnlyMatches = 0
        var nameOnlyMatches = 0
        val matches = level.getEntities(EntityTypeTest.forClass(Entity::class.java), area) { entity ->
            if (entity !== player) {
                nearbyEntities = incrementBounded(nearbyEntities)
                if (entity is AbstractClientPlayer) fakePlayers = incrementBounded(fakePlayers)
            }
            if (entity === player || !entity.isAlive()) {
                false
            } else {
                aliveEntities = incrementBounded(aliveEntities)
                val textureMatch = isTextureHashMatch(entity)
                // Keep the same short-circuit order as the selector: a skin match skips name work.
                val nameMatch = !textureMatch && isNameFallbackMatch(entity)
                if (textureMatch) textureMatches = incrementBounded(textureMatches)
                if (nameMatch) nameMatches = incrementBounded(nameMatches)
                when {
                    textureMatch -> textureOnlyMatches = incrementBounded(textureOnlyMatches)
                    nameMatch -> nameOnlyMatches = incrementBounded(nameOnlyMatches)
                }
                textureMatch || nameMatch
            }
        }

        var best: Entity? = null
        var bestDistance = Double.MAX_VALUE
        var directMatches = 0
        var redirectedBodies = 0
        var unresolvedMatches = 0
        for (match in matches) {
            // Name fallback can land on a floating label; exact fake-player matches resolve directly.
            val directHideyho = isDirectHideyhoEntityMatch(match)
            if (directHideyho) directMatches = incrementBounded(directMatches)
            val body = Nameplates.resolveBody(level, match, directHideyho)
            if (body == null) {
                unresolvedMatches = incrementBounded(unresolvedMatches)
                continue
            }
            if (body !== match) redirectedBodies = incrementBounded(redirectedBodies)
            val distance = body.position().distanceToSqr(at)
            if (distance < bestDistance) {
                bestDistance = distance
                best = body
            }
        }
        found = best
        scansSinceStatus = incrementBounded(scansSinceStatus)
        lastScan = "rangeBlocks=${oneDecimal(range)},renderDistanceChunks=$renderDistanceChunks" +
            ",nearby=$nearbyEntities,alive=$aliveEntities,fakePlayers=$fakePlayers" +
            ",textureMatches=$textureMatches,nameMatches=$nameMatches" +
            ",textureOnly=$textureOnlyMatches,nameOnly=$nameOnlyMatches" +
            ",matches=${matches.size},direct=$directMatches,redirected=$redirectedBodies" +
            ",unresolved=$unresolvedMatches,selected=${best != null}" +
            ",selectedDistanceBlocks=${if (best == null) "none" else oneDecimal(sqrt(bestDistance))}" +
            ",debugCandidates=${candidates.size}"
        recordDetail("SCAN $lastScan")
    }

    /** A texture/name-matched player is the Hideyho body, not a floating label to redirect. */
    private fun isDirectHideyhoEntityMatch(entity: Entity): Boolean =
        entity is AbstractClientPlayer && isHideyho(entity)

    private fun isHideyho(entity: Entity): Boolean = isTextureHashMatch(entity) || isNameFallbackMatch(entity)

    private fun isTextureHashMatch(entity: Entity): Boolean {
        if (entity !is AbstractClientPlayer) return false
        val body = entity.getSkin().body()
        return body is ClientAsset.DownloadedTexture && hasHideyhoTextureHash(body.url())
    }

    private fun isNameFallbackMatch(entity: Entity): Boolean =
        ChatText.plain(entity.getName().getString()).lowercase(Locale.ROOT).contains(NAME_FALLBACK)

    // ---- rendering ----------------------------------------------------------------------

    /** Drawn through walls on purpose - a Hideyho that isn't hidden behind something isn't hidden. */
    fun render(context: LevelRenderContext) {
        if (!isActive()) {
            lastRender = "skipped-inactive"
            return
        }
        if (found == null && candidates.isEmpty()) {
            lastRender = "skipped-no-targets"
            return
        }

        val cameraPosition = Minecraft.getInstance().gameRenderer.mainCamera().position()
        val poseStack: PoseStack = context.poseStack()
        val bufferSource = WorldRenderBuffer.current()
        val argb = color.argb()
        var debugBoxes = 0
        var foundBoxes = 0

        for (candidate in candidates) {
            if (candidate === found || candidate.isRemoved()) continue
            box(poseStack, bufferSource, cameraPosition, candidate, ARGB.color(DEBUG_ALPHA, argb))
            debugBoxes++
        }
        val currentFound = found
        if (currentFound != null) {
            box(poseStack, bufferSource, cameraPosition, currentFound, argb)
            foundBoxes = 1
        }
        GeilerAddonsRenderTypes.endBatches(bufferSource)
        lastRender = "debugBoxes=$debugBoxes,foundBoxes=$foundBoxes,submitted=${debugBoxes + foundBoxes}" +
            ",throughWalls=true,debugMode=${debugMode.value()},colorArgb=${argbHex(argb)}"
    }

    private fun reportDiagnostics(gate: Gate, level: ClientLevel?, player: LocalPlayer?) {
        val minecraft = Minecraft.getInstance()
        val renderDistanceChunks = minecraft.options.getEffectiveRenderDistance()
        val range = minOf(renderDistanceChunks * 16.0, MAX_RANGE.toDouble())
        val currentFound = found
        val state = "enabled=true,active=${gate.active},island=${gate.island?.name ?: "unknown"}" +
            ",biome=${if (gate.biomeRead) gate.biome?.name ?: "unknown" else "not-read"}" +
            ",level=${level != null},player=${player != null},renderDistanceChunks=$renderDistanceChunks" +
            ",rangeBlocks=${oneDecimal(range)},maxRangeBlocks=$MAX_RANGE" +
            ",scanIntervalTicks=${scanInterval.intValue()},ticksSinceScan=$ticksSinceScan" +
            ",debugMode=${debugMode.value()},debugCandidates=${candidates.size}" +
            ",colorArgb=${argbHex(color.argb())},found=${currentFound != null}" +
            ",scansSinceStatus=$scansSinceStatus,lastScan={$lastScan},lastRender={$lastRender}"
        val changed = state != lastDiagnosticState
        val heartbeat = lastDiagnosticTick == Long.MIN_VALUE ||
            diagnosticTick - lastDiagnosticTick >= DIAGNOSTIC_HEARTBEAT_TICKS
        if (!changed && !heartbeat) return
        if (changed && lastDiagnosticTick != Long.MIN_VALUE &&
            diagnosticTick - lastDiagnosticTick < DIAGNOSTIC_MIN_INTERVAL_TICKS
        ) {
            suppressedDiagnosticChanges = incrementBounded(suppressedDiagnosticChanges)
            return
        }

        writeDiagnostic(
            "STATUS $state,suppressedChanges=$suppressedDiagnosticChanges," +
                "suppressedDetails=$suppressedDetails,lineLimit=$MAX_DIAGNOSTIC_LINE_LENGTH",
        )
        lastDiagnosticState = state
        lastDiagnosticTick = diagnosticTick
        suppressedDiagnosticChanges = 0
        suppressedDetails = 0
        scansSinceStatus = 0
    }

    private fun diagnosticSettings(): String =
        "scanIntervalTicks=${scanInterval.intValue()},debugMode=${debugMode.value()},colorArgb=${argbHex(color.argb())}"

    private fun recordDetail(line: String) {
        if (detailsThisTick >= MAX_DIAGNOSTIC_EVENTS_PER_TICK) {
            suppressedDetails = incrementBounded(suppressedDetails)
            return
        }
        detailsThisTick++
        writeDiagnostic("EVENT $line")
    }

    private fun writeDiagnostic(line: String) {
        GeilerAddonsLog.write(Category.HUNTING, name(), diagnosticTick, line.take(MAX_DIAGNOSTIC_LINE_LENGTH))
    }

    private fun incrementBounded(value: Int): Int = (value + 1).coerceAtMost(MAX_DIAGNOSTIC_COUNTER)

    private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private fun argbHex(argb: Int): String =
        "0x${Integer.toHexString(argb).uppercase(Locale.ROOT).padStart(8, '0')}"

    private data class Gate(
        val island: Island?,
        val biome: SafariBiome?,
        val biomeRead: Boolean,
        val active: Boolean,
    )

    private class Settings {
        val color = ColorSetting("Color", 255, 0, 255, 83)
        /** Ticks between sweeps; the default is one a second. */
        val scanInterval = NumberSetting("Scan Interval", 1f, 200f, 20f, true)
        /** Boxes every fake player instead, which is how to tell a miss from a bad skin match. */
        val debugMode = BooleanSetting.debug("Debug Mode", false)
    }

    companion object {
        private const val TEXTURE_HASH = "3504f1f2327a5110e643bb8667082512815fa434a29ed37f4ca83bb16d2db533"
        private const val NAME_FALLBACK = "hideyho"
        private const val MAX_RANGE = 128
        private const val BOX_LINE_WIDTH = 2.0f
        private const val DEBUG_ALPHA = 70
        private const val DIAGNOSTIC_MIN_INTERVAL_TICKS = 20L
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 8
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 1024
        private const val MAX_DIAGNOSTIC_COUNTER = 1_000_000

        @JvmField
        val INSTANCE = HideyhoFinderModule()

        /** Checks the fetched skin URL, because the downloaded texture identifier is URL-hashed. */
        @JvmStatic
        fun hasHideyhoTextureHash(url: String?): Boolean = url?.contains(TEXTURE_HASH) == true

        /** Preserved Java-visible helper used by the offline acceptance check. */
        @JvmStatic
        fun isDirectHideyhoMatch(entity: Entity): Boolean = INSTANCE.isDirectHideyhoEntityMatch(entity)

        private fun box(
            poseStack: PoseStack,
            bufferSource: WorldRenderBuffer.BufferSource,
            cameraPosition: Vec3,
            entity: Entity,
            argb: Int,
        ) {
            val bounds = entity.getBoundingBox()
            EspRenderer.renderBox(
                poseStack,
                bufferSource,
                bounds.minX - cameraPosition.x,
                bounds.minY - cameraPosition.y,
                bounds.minZ - cameraPosition.z,
                bounds.getXsize(),
                bounds.getYsize(),
                bounds.getZsize(),
                argb,
                argb,
                BOX_LINE_WIDTH,
            )
        }
    }
}
