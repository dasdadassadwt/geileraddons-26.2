package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
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

    private class Found(val id: Int, val corpse: MineshaftRules.Corpse, val x: Double, val y: Double, val z: Double)

    private val diagnostics = ModuleDiagnostics(Category.MINING, { name() }, 8, 100L, 400)
    private var found: List<Found> = emptyList()
    private var looted: Map<String, Boolean> = emptyMap()
    private var tickCount = 0L
    private var seenIds = HashSet<Int>()

    init {
        group(geiler.addons.client.module.SettingGroup("Display", esp, labels, hideLooted, lineToCorpse))
    }

    private fun settingsText(): String =
        "esp=${esp.value()};labels=${labels.value()};hideLooted=${hideLooted.value()};line=${lineToCorpse.value()}"

    override fun onEnable() {
        found = emptyList()
        looted = emptyMap()
        seenIds = HashSet()
        diagnostics.enable(settingsText())
    }

    override fun onDisable() {
        found = emptyList()
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
            if (found.isNotEmpty()) diagnostics.event("LEFT_SHAFT corpses=${found.size}")
            found = emptyList()
            seenIds.clear()
            report("outside-mineshaft")
            return
        }
        if (tickCount % SCAN_TICKS != 0L) return
        val scanned = ArrayList<Found>(4)
        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand || !entity.hasItemInSlot(EquipmentSlot.HEAD)) continue
            val corpse = MineshaftRules.corpseForHelmet(SkyblockItems.skyBlockId(entity.getItemBySlot(EquipmentSlot.HEAD))) ?: continue
            scanned.add(Found(entity.id, corpse, entity.x, entity.y, entity.z))
            if (seenIds.add(entity.id)) {
                diagnostics.event("CORPSE_SEEN type=${corpse.label} x=${entity.x.toInt()} y=${entity.y.toInt()} z=${entity.z.toInt()}")
            }
        }
        found = scanned
        looted = MiningTabText.corpses(MiningContext.tabLines())
        report("ready")
    }

    private fun isLooted(corpse: MineshaftRules.Corpse): Boolean = looted[corpse.label] == true

    private fun report(state: String) {
        diagnostics.statusOnChange {
            "state=$state corpses=${found.size} looted=${found.count { isLooted(it.corpse) }} tabRows=${looted.size} seen=${seenIds.size}"
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || found.isEmpty() || !MiningContext.on(Island.MINESHAFT)) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera()
        val cam = camera.position()
        val buffer = WorldRenderBuffer.current()
        val pose = context.poseStack()
        val forward = camera.forwardVector()
        for (item in found) {
            val done = isLooted(item.corpse)
            if (done && hideLooted.value()) continue
            val color = if (done) LOOTED_COLOR else item.corpse.argb
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
        if (!isEnabled() || !labels.value() || found.isEmpty() || !MiningContext.on(Island.MINESHAFT)) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera()
        for (item in found) {
            val done = isLooted(item.corpse)
            if (done && hideLooted.value()) continue
            val text = "${item.corpse.label} Corpse" + if (done) " (Looted)" else ""
            ProjectedLabelRenderer.draw(
                graphics, camera, client.font, Vec3(item.x, item.y + 2.2, item.z), text,
                if (done) LOOTED_COLOR else item.corpse.argb, 1.4f,
            )
        }
    }

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
