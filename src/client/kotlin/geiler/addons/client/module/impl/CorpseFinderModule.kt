package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.mining.CorpseTracker
import geiler.addons.client.mining.MineshaftRules
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.mining.MiningTabText
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3

/**
 * Finds the frozen corpses in a Glacite Mineshaft, after SkyHanni's Corpse Finder: a corpse is an armor stand wearing
 * a Lapis, Mineral, Armor of Yog or Vanguard helmet. Each one is boxed through walls and labelled with its type; the
 * tab list's Frozen Corpses widget says which are already looted, and those turn green (or hide).
 */
class CorpseFinderModule private constructor(settings: Settings) : Module(
    "Corpse Finder",
    "Marks the frozen corpses in a Mineshaft with a box and their type.",
    Category.MINING,
    false,
    settings.esp, settings.labels, settings.hideLooted, settings.lineToCorpse,
) {
    private val esp = settings.esp
    private val labels = settings.labels
    private val hideLooted = settings.hideLooted
    private val lineToCorpse = settings.lineToCorpse

    private val diagnostics = ModuleDiagnostics(Category.MINING, { name() }, 8, 100L, 400)
    private val tracker = CorpseTracker()
    private var tabRows: Map<String, Boolean> = emptyMap()
    private var tickCount = 0L

    init {
        group(geiler.addons.client.module.SettingGroup("Display", esp, labels, hideLooted, lineToCorpse))
    }

    private fun settingsText(): String =
        "esp=${esp.value()};labels=${labels.value()};hideLooted=${hideLooted.value()};line=${lineToCorpse.value()}"

    override fun onEnable() {
        tracker.clear()
        tabRows = emptyMap()
        diagnostics.enable(settingsText())
    }

    override fun onDisable() {
        tracker.clear()
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        diagnostics.advance()
        tickCount++
        val level = mc.level
        if (level == null || mc.player == null || !MiningContext.on(Island.MINESHAFT)) {
            if (tracker.entries().isNotEmpty()) diagnostics.event("LEFT_SHAFT corpses=${tracker.entries().size}")
            tracker.clear()
            report("outside-mineshaft")
            return
        }
        if (tickCount % SCAN_TICKS != 0L) return
        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand || !entity.hasItemInSlot(EquipmentSlot.HEAD)) continue
            val corpse = MineshaftRules.corpseForHelmet(SkyblockItems.skyBlockId(entity.getItemBySlot(EquipmentSlot.HEAD))) ?: continue
            if (tracker.see(corpse.label, entity.x, entity.y, entity.z)) {
                diagnostics.event("CORPSE_SEEN type=${corpse.label} x=${entity.x.toInt()} y=${entity.y.toInt()} z=${entity.z.toInt()}")
            }
        }
        tabRows = MiningTabText.corpses(MiningContext.tabLines())
        val player = mc.player!!
        for (done in tracker.update(tabRows, player.x, player.y, player.z)) {
            diagnostics.event("CORPSE_LOOTED type=${done.type} x=${done.x.toInt()} y=${done.y.toInt()} z=${done.z.toInt()} tab=$tabRows")
        }
        report("ready")
    }

    private fun report(state: String) {
        diagnostics.statusOnChange {
            "state=$state corpses=${tracker.entries().size} looted=${tracker.entries().count { it.looted }} tabRows=${tabRows.size}"
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || tracker.entries().isEmpty() || !MiningContext.on(Island.MINESHAFT)) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera()
        val cam = camera.position()
        val buffer = WorldRenderBuffer.current()
        val pose = context.poseStack()
        val forward = camera.forwardVector()
        for (item in tracker.entries()) {
            val done = item.looted
            if (done && hideLooted.value()) continue
            val color = if (done) LOOTED_COLOR else corpseColor(item)
            if (esp.value()) {
                EspRenderer.renderBox(
                    pose, buffer, item.x - 0.4 - cam.x, item.y - cam.y, item.z - 0.4 - cam.z, 0.8, 1.6, 0.8,
                    (color and 0x00FFFFFF) or FILL_ALPHA, color, LINE_WIDTH,
                )
            }
            if (lineToCorpse.value() && !done) {
                EspRenderer.renderLine(
                    pose, buffer, forward.x() * 0.5, forward.y() * 0.5, forward.z() * 0.5,
                    item.x - cam.x, item.y + 0.8 - cam.y, item.z - cam.z, color, LINE_WIDTH,
                )
            }
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || !labels.value() || tracker.entries().isEmpty() || !MiningContext.on(Island.MINESHAFT)) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera()
        for (item in tracker.entries()) {
            val done = item.looted
            if (done && hideLooted.value()) continue
            val text = "${item.type} Corpse" + if (done) " (Looted)" else ""
            ProjectedLabelRenderer.draw(
                graphics, camera, client.font, Vec3(item.x, item.y + 2.2, item.z), text,
                if (done) LOOTED_COLOR else corpseColor(item), 1.4f,
            )
        }
    }

    private fun corpseColor(item: CorpseTracker.Entry): Int =
        (MineshaftRules.corpseByLabel(item.type)?.argb ?: LOOTED_COLOR)

    private class Settings {
        val esp = BooleanSetting("Corpse ESP", true)
        val labels = BooleanSetting("Type Labels", true)
        val hideLooted = BooleanSetting("Hide Looted", false)
        val lineToCorpse = BooleanSetting("Line To Corpse", false)
    }

    companion object {
        private const val SCAN_TICKS = 10L
        private const val FILL_ALPHA = 0x40000000
        private const val LINE_WIDTH = 3.0f
        private const val LOOTED_COLOR = 0xFF55FF55.toInt()

        @JvmField
        val INSTANCE = CorpseFinderModule(Settings())
    }
}
