package geiler.addons.client.module.impl

import geiler.addons.client.entity.Nameplates
import geiler.addons.client.mining.MiningConditions
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.decoration.ArmorStand

/**
 * Highlights the rare goblins that spawn for you, after SkyHanni's Golden Goblin Highlight: a Golden or Diamond
 * Goblin label that appears within ten seconds of your own spawn message is yours and gets a box (and an optional
 * line). Goblin Raid Superprotectrons are boxed too. Each label is paired to its body with the shared nameplate rule.
 */
class GoblinHighlighterModule private constructor(settings: Settings) : Module(
    "Goblin Highlighter",
    "Boxes your own Golden and Diamond Goblin and Goblin Raid Superprotectrons.",
    Category.MINING,
    false,
    settings.ownGoblin, settings.lineToGoblin, settings.goblinColor, settings.superprotectron, settings.superprotectronColor,
) {
    private val ownGoblin = settings.ownGoblin
    private val lineToGoblin = settings.lineToGoblin
    private val goblinColor = settings.goblinColor
    private val superprotectron = settings.superprotectron
    private val superprotectronColor = settings.superprotectronColor

    private class Marked(val bodyId: Int, val raid: Boolean)

    private val diagnostics = ModuleDiagnostics(Category.MINING, { name() }, 8, 100L, 400)
    private var marked: List<Marked> = emptyList()
    private val ownLabelIds = HashSet<Int>()
    private val seenLabelIds = HashSet<Int>()
    private var spawnMessageNanos = Long.MIN_VALUE / 2
    private var tick = 0L
    private var spawnMessages = 0L
    private var claimed = 0L

    init {
        group(SettingGroup("Rare Goblins", ownGoblin, lineToGoblin, goblinColor), SettingGroup("Goblin Raid", superprotectron, superprotectronColor))
    }

    private fun settingsText(): String =
        "own=${ownGoblin.value()};line=${lineToGoblin.value()};superprotectron=${superprotectron.value()}"

    override fun onEnable() {
        reset()
        diagnostics.enable(settingsText())
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        marked = emptyList()
        ownLabelIds.clear()
        seenLabelIds.clear()
        spawnMessageNanos = Long.MIN_VALUE / 2
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        if (!MiningConditions.isGoldenGoblinSpawn(plain) && !MiningConditions.isDiamondGoblinSpawn(plain)) return
        spawnMessageNanos = System.nanoTime()
        spawnMessages++
        diagnostics.event("SPAWN_MESSAGE count=$spawnMessages")
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
        val level = mc.level
        if (level == null || mc.player == null || !MiningContext.inMiningIsland()) {
            if (marked.isNotEmpty()) reset()
            return
        }
        if (tick % SCAN_TICKS != 0L) return
        val now = System.nanoTime()
        val found = ArrayList<Marked>(4)
        val live = HashSet<Int>()
        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand) continue
            val name = entity.customName?.string ?: continue
            live.add(entity.id)
            val rare = ownGoblin.value() && MiningConditions.isGoblinLabel(name)
            val raid = superprotectron.value() && MiningConditions.isSuperprotectronLabel(name)
            if (!rare && !raid) continue
            if (rare && seenLabelIds.add(entity.id) && now - spawnMessageNanos <= CLAIM_WINDOW_NANOS) {
                ownLabelIds.add(entity.id)
                claimed++
                diagnostics.event("GOBLIN_CLAIMED label=\"${name.take(40)}\" claimed=$claimed")
            } else if (rare) {
                seenLabelIds.add(entity.id)
            }
            if (rare && entity.id !in ownLabelIds) continue
            val body = Nameplates.resolveBody(level, entity) ?: continue
            found.add(Marked(body.id, raid))
        }
        ownLabelIds.retainAll(live)
        seenLabelIds.retainAll(live)
        marked = found
        diagnostics.statusOnChange {
            "marked=${marked.size} own=${ownLabelIds.size} spawnMessages=$spawnMessages claimed=$claimed"
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || marked.isEmpty()) return
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera()
        val cam = camera.position()
        val forward = camera.forwardVector()
        val buffer = WorldRenderBuffer.current()
        val pose = context.poseStack()
        for (mark in marked) {
            val entity = level.getEntity(mark.bodyId) ?: continue
            val box = entity.boundingBox
            val color = if (mark.raid) superprotectronColor.opaqueArgb() else goblinColor.opaqueArgb()
            EspRenderer.renderBox(
                pose, buffer, box.minX - cam.x, box.minY - cam.y, box.minZ - cam.z,
                box.xsize, box.ysize, box.zsize, (color and 0x00FFFFFF) or FILL_ALPHA, color, LINE_WIDTH,
            )
            if (!mark.raid && lineToGoblin.value()) {
                EspRenderer.renderLine(
                    pose, buffer, forward.x() * 0.5, forward.y() * 0.5, forward.z() * 0.5,
                    box.center.x - cam.x, box.center.y - cam.y, box.center.z - cam.z, color, LINE_WIDTH,
                )
            }
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
    }

    private class Settings {
        val ownGoblin = BooleanSetting("Highlight Your Goblin", true)
        val lineToGoblin = BooleanSetting("Line To Goblin", false)
        val goblinColor = ColorSetting("Goblin Color", 85, 255, 85, 255)
        val superprotectron = BooleanSetting("Highlight Superprotectron", true)
        val superprotectronColor = ColorSetting("Superprotectron Color", 255, 85, 85, 255)
    }

    companion object {
        private const val SCAN_TICKS = 5L
        private const val CLAIM_WINDOW_NANOS = 10_000_000_000L
        private const val FILL_ALPHA = 0x40000000
        private const val LINE_WIDTH = 3.0f

        @JvmField
        val INSTANCE = GoblinHighlighterModule(Settings())
    }
}
