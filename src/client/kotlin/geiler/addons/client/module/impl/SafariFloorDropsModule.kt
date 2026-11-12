package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.config.GeilerAddonsLog
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
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.Display
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.util.HashMap
import java.util.Iterator

/** Boxes floor drops in Critter Safari from their announcing particle and string-display model. */
class SafariFloorDropsModule private constructor(settings: Settings) : Module(
    "Safari Floor Drops",
    "Highlights floor drops in the Critter Safari.",
    Category.HUNTING,
    settings.scanDelay,
    settings.color,
    settings.depthCheck,
) {
    private val scanDelay = settings.scanDelay
    private val color = settings.color
    private val depthCheck = settings.depthCheck

    /** Marked spots and the last time their three-string model was confirmed. */
    private val nodes = HashMap<BlockPos, Long>()
    private var ticksSinceScan = 0
    private var lastLevel: ClientLevel? = null

    private var diagnosticTick = 0L
    private var lastDiagnosticTick = Long.MIN_VALUE
    private var lastDiagnosticState = ""
    private var suppressedDiagnosticChanges = 0
    private var detailsThisTick = 0
    private var suppressedDetails = 0
    private var happyParticles = 0
    private var acceptedParticles = 0
    private var rejectedParticles = 0
    private var duplicateParticles = 0
    private var particlesWithoutLevel = 0
    private var lastReconfirm = "not-run"
    private var lastRender = "not-rendered"

    private constructor() : this(Settings())

    override fun isActive(): Boolean = SafariFloorDropsRules.isActive(
        isEnabled(),
        HypixelModApi.currentIsland() == Island.SAFARI,
    )

    override fun inactiveReason(): String? {
        if (!isEnabled() || isActive()) return null
        return HypixelModApi.reasonNotOn(Island.SAFARI)
    }

    override fun onEnable() {
        diagnosticTick = 0
        lastDiagnosticTick = Long.MIN_VALUE
        lastDiagnosticState = ""
        suppressedDiagnosticChanges = 0
        detailsThisTick = 0
        suppressedDetails = 0
        resetDiagnosticWindow()
        lastReconfirm = "not-run"
        lastRender = "not-rendered"
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.HUNTING, name(), true)
        writeDiagnostic("STATUS enabled=true;awaiting=client-tick;scanDelayTicks=${scanDelay.intValue()}")
    }

    override fun onDisable() {
        val removed = nodes.size
        nodes.clear()
        writeDiagnostic("STATUS enabled=false;cleared=$removed;reason=module-disabled")
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.HUNTING, name(), false)
    }

    fun tick() {
        if (!isEnabled()) return

        diagnosticTick++
        detailsThisTick = 0

        val island = HypixelModApi.currentIsland()
        val active = SafariFloorDropsRules.isActive(isEnabled(), island == Island.SAFARI)
        if (!active) {
            if (nodes.isNotEmpty()) nodes.clear()
            lastReconfirm = "skipped-inactive"
            reportDiagnostics(island, Minecraft.getInstance().level, false)
            return
        }

        val level = Minecraft.getInstance().level
        if (level !== lastLevel) {
            lastLevel = level
            nodes.clear()
        }
        if (level != null) {
            if (++ticksSinceScan >= scanDelay.intValue()) {
                ticksSinceScan = 0
                reconfirm(level)
            }
        } else {
            lastReconfirm = "skipped-no-level"
        }
        reportDiagnostics(island, level, true)
    }

    /** Rechecks marked spots after two seconds and forgets an unconfirmed one after five. */
    private fun reconfirm(level: ClientLevel) {
        val now = System.currentTimeMillis()
        var checked = 0
        var refreshed = 0
        var expired = 0
        val iterator = nodes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!SafariFloorDropsRules.shouldReconfirm(now, entry.value)) continue
            checked++
            if (SafariFloorDropsRules.isConfirmedStringDisplayCount(countStrings(level, entry.key))) {
                entry.setValue(now)
                refreshed++
            } else if (SafariFloorDropsRules.isExpired(now, entry.value)) {
                iterator.remove()
                expired++
            }
        }
        lastReconfirm = "checked=$checked,refreshed=$refreshed,expired=$expired"
        if (refreshed > 0 || expired > 0) recordDetail("reconfirm $lastReconfirm")
    }

    /** The happy-villager particle is one block above the floor drop. */
    fun onParticle(packet: ClientboundLevelParticlesPacket) {
        if (!isActive()) return
        if (!ParticleTypes.HAPPY_VILLAGER.type.equals(packet.getParticle().type)) return

        happyParticles = incrementBounded(happyParticles)
        val level = Minecraft.getInstance().level
        if (level == null) {
            particlesWithoutLevel = incrementBounded(particlesWithoutLevel)
            return
        }

        val pos = SafariFloorDropsRules.markerPosition(packet.getX(), packet.getY(), packet.getZ())
        val stringCount = countStrings(level, pos)
        if (!SafariFloorDropsRules.isConfirmedStringDisplayCount(stringCount)) {
            rejectedParticles = incrementBounded(rejectedParticles)
            return
        }

        val added = SafariFloorDropsRules.rememberIfAbsent(nodes, pos, System.currentTimeMillis())
        if (added) {
            acceptedParticles = incrementBounded(acceptedParticles)
            recordDetail("particle accepted=three-string-displays tracked=${nodes.size}")
        } else {
            duplicateParticles = incrementBounded(duplicateParticles)
        }
    }

    /** Clears a marker immediately when the player attacks or uses its block. */
    fun onClick() {
        if (!isActive() || nodes.isEmpty()) return
        val hit = Minecraft.getInstance().hitResult
        if (hit is BlockHitResult && SafariFloorDropsRules.removeNode(nodes, hit.blockPos)) {
            recordDetail("click removed=true tracked=${nodes.size}")
        }
    }

    private fun countStrings(level: ClientLevel, pos: BlockPos): Int {
        val displays = level.getEntities(
            EntityTypeTest.forClass(Display.ItemDisplay::class.java),
            AABB.ofSize(Vec3.atCenterOf(pos), 1.0, 1.0, 1.0),
        ) { true }

        var count = 0
        for (display in displays) {
            val state: Display.ItemDisplay.ItemRenderState? = display.itemRenderState()
            if (state == null) continue
            val stack: ItemStack = state.itemStack()
            if (!stack.isEmpty && stack.`is`(Items.STRING)) count++
        }
        return count
    }

    fun render(context: LevelRenderContext) {
        if (!isActive()) {
            lastRender = "skipped-inactive"
            return
        }
        if (nodes.isEmpty()) {
            lastRender = "eligible=0,submitted=0,reason=no-markers"
            return
        }

        val cameraPosition = Minecraft.getInstance().gameRenderer.mainCamera().position()
        val poseStack: PoseStack = context.poseStack()
        val bufferSource = WorldRenderBuffer.current()
        val depth = depthCheck.value()
        val argb = color.argb()
        val now = System.currentTimeMillis()
        var eligible = 0
        var submitted = 0

        for ((pos, lastConfirmed) in nodes) {
            if (SafariFloorDropsRules.isExpired(now, lastConfirmed)) continue
            eligible++
            poseStack.pushPose()
            try {
                poseStack.translate(
                    pos.x - cameraPosition.x,
                    pos.y - cameraPosition.y,
                    pos.z - cameraPosition.z,
                )
                EspRenderer.renderBox(
                    poseStack,
                    bufferSource,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    1.0,
                    1.0,
                    argb,
                    argb,
                    BOX_LINE_WIDTH,
                    depth,
                )
                submitted++
            } finally {
                poseStack.popPose()
            }
        }
        GeilerAddonsRenderTypes.endBatches(bufferSource)
        lastRender = "eligible=$eligible,submitted=$submitted,depth=$depth"
    }

    private fun reportDiagnostics(island: Island?, level: ClientLevel?, active: Boolean) {
        val state = buildString {
            append("enabled=true,active=").append(active)
            append(",island=").append(island?.name ?: "unknown")
            append(",level=").append(level != null)
            append(",scanDelayTicks=").append(scanDelay.intValue())
            append(",depthCheck=").append(depthCheck.value())
            append(",colorArgb=").append(color.argb())
            append(",tracked=").append(nodes.size)
            append(",ticksSinceScan=").append(ticksSinceScan)
            append(",particles{happy=").append(happyParticles)
            append(",accepted=").append(acceptedParticles)
            append(",rejected=").append(rejectedParticles)
            append(",duplicates=").append(duplicateParticles)
            append(",noLevel=").append(particlesWithoutLevel).append('}')
            append(",reconfirm{").append(lastReconfirm).append('}')
            append(",render{").append(lastRender).append('}')
        }
        val changed = state != lastDiagnosticState
        val heartbeat = lastDiagnosticTick == Long.MIN_VALUE ||
            diagnosticTick - lastDiagnosticTick >= DIAGNOSTIC_HEARTBEAT_TICKS
        if (!changed && !heartbeat) return
        if (changed && lastDiagnosticTick != Long.MIN_VALUE &&
            diagnosticTick - lastDiagnosticTick < DIAGNOSTIC_MIN_INTERVAL_TICKS
        ) {
            suppressedDiagnosticChanges = (suppressedDiagnosticChanges + 1).coerceAtMost(MAX_SUPPRESSED_EVENTS)
            return
        }

        writeDiagnostic(
            "STATUS $state,suppressedChanges=$suppressedDiagnosticChanges,suppressedDetails=$suppressedDetails," +
                "lineLimit=$MAX_DIAGNOSTIC_LINE_LENGTH",
        )
        lastDiagnosticState = state
        lastDiagnosticTick = diagnosticTick
        suppressedDiagnosticChanges = 0
        suppressedDetails = 0
        resetDiagnosticWindow()
    }

    private fun recordDetail(line: String) {
        if (detailsThisTick >= MAX_DETAILS_PER_TICK) {
            suppressedDetails = (suppressedDetails + 1).coerceAtMost(MAX_SUPPRESSED_EVENTS)
            return
        }
        detailsThisTick++
        writeDiagnostic("EVENT $line")
    }

    private fun writeDiagnostic(line: String) {
        GeilerAddonsLog.write(
            Category.HUNTING,
            name(),
            diagnosticTick,
            line.take(MAX_DIAGNOSTIC_LINE_LENGTH),
        )
    }

    private fun resetDiagnosticWindow() {
        happyParticles = 0
        acceptedParticles = 0
        rejectedParticles = 0
        duplicateParticles = 0
        particlesWithoutLevel = 0
    }

    private fun incrementBounded(value: Int): Int = (value + 1).coerceAtMost(MAX_DIAGNOSTIC_COUNTER)

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        private val spec = ModuleSettingsSpec.of("Safari Floor Drops")
        val scanDelay = spec.slider("scanDelay")
        val color = spec.color("color")
        val depthCheck = spec.toggle("depthCheck")
    }

    companion object {
        @JvmField
        val INSTANCE = SafariFloorDropsModule()

        private const val BOX_LINE_WIDTH = 2.0f
        private const val DIAGNOSTIC_MIN_INTERVAL_TICKS = 20L
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L
        private const val MAX_DETAILS_PER_TICK = 8
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 2048
        private const val MAX_DIAGNOSTIC_COUNTER = 1_000_000
        private const val MAX_SUPPRESSED_EVENTS = 1_000_000
    }
}

/** Small deterministic rules shared with the standalone direct checks. */
object SafariFloorDropsRules {
    const val REQUIRED_STRING_DISPLAYS = 3
    const val LIFETIME_MILLIS = 5_000L
    const val RECONFIRM_MILLIS = 2_000L

    @JvmStatic
    fun isActive(enabled: Boolean, onSafari: Boolean): Boolean = enabled && onSafari

    @JvmStatic
    fun markerPosition(particleX: Double, particleY: Double, particleZ: Double): BlockPos =
        BlockPos.containing(particleX, particleY - 1.0, particleZ)

    @JvmStatic
    fun isConfirmedStringDisplayCount(count: Int): Boolean = count == REQUIRED_STRING_DISPLAYS

    @JvmStatic
    fun shouldReconfirm(now: Long, lastConfirmed: Long): Boolean = now - lastConfirmed >= RECONFIRM_MILLIS

    @JvmStatic
    fun isExpired(now: Long, lastConfirmed: Long): Boolean = now - lastConfirmed >= LIFETIME_MILLIS

    @JvmStatic
    fun rememberIfAbsent(nodes: MutableMap<BlockPos, Long>, pos: BlockPos, now: Long): Boolean =
        nodes.putIfAbsent(pos.immutable(), now) == null

    @JvmStatic
    fun removeNode(nodes: MutableMap<BlockPos, Long>, pos: BlockPos?): Boolean =
        pos != null && nodes.remove(pos) != null
}
