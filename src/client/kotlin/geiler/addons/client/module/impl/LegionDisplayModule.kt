package geiler.addons.client.module.impl

import geiler.addons.client.combat.LegionRules
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FishingHook

/** HUD rows for the Legion and Bobbin' Time armor enchants: what is within 30 blocks and the bonus it gives. */
class LegionDisplayModule private constructor(private val s: Settings) : Module(
    "Legion Display",
    "Shows how many players and bobbers count for your Legion and Bobbin' Time armor.",
    Category.MISCELLANEOUS,
    false,
    s.onlyWithEnchant, s.sphere, s.fill, s.sphereColor,
), HudElement {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)
    private var legionLevels = 0
    private var bobbinLevels = 0
    private var players = 0
    private var bobbers = 0
    private var active = false
    private var ticks = 0

    override fun onEnable() {
        reset()
        diagnostics.enable("onlyWithEnchant=${s.onlyWithEnchant.value()} sphere=${s.sphere.value()} fill=${s.fill.value()}")
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        legionLevels = 0
        bobbinLevels = 0
        players = 0
        bobbers = 0
        active = false
        ticks = 0
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        if (level == null || player == null || !SkyblockItems.inSkyblock()) {
            if (active) reset()
            return
        }
        active = true
        if (ticks++ % ARMOR_TICKS == 0) readArmor(player)
        var nearPlayers = 0
        var nearBobbers = 0
        for (entity in level.entitiesForRendering()) {
            if (entity !is Player && entity !is FishingHook) continue
            if (!LegionRules.inRange(player.distanceToSqr(entity))) continue
            if (entity is FishingHook) {
                nearBobbers++
            } else if (entity !== player && entity.uuid.version() == REAL_PLAYER_UUID) {
                // Hypixel NPC player models do not use version 4 ids.
                nearPlayers++
            }
        }
        players = nearPlayers
        bobbers = nearBobbers
        diagnostics.status {
            "legionLevels=$legionLevels bobbinLevels=$bobbinLevels players=$players bobbers=$bobbers suppressedEvents=$it"
        }
    }

    private fun readArmor(player: LocalPlayer) {
        var legion = 0
        var bobbin = 0
        for (slot in ARMOR) {
            val tag = player.getItemBySlot(slot).get(DataComponents.CUSTOM_DATA)?.copyTag() ?: continue
            val enchants = tag.getCompoundOrEmpty("enchantments")
            legion += enchants.getIntOr("ultimate_legion", 0)
            bobbin += enchants.getIntOr("ultimate_bobbin_time", 0)
        }
        if (legion != legionLevels || bobbin != bobbinLevels) {
            diagnostics.event("ARMOR legion=$legion bobbin=$bobbin")
        }
        legionLevels = legion
        bobbinLevels = bobbin
    }

    private fun rows(): List<String> = buildList {
        if (!active) return@buildList
        if (legionLevels > 0 || !s.onlyWithEnchant.value()) add(LegionRules.legionText(legionLevels, players))
        if (bobbinLevels > 0 || !s.onlyWithEnchant.value()) add(LegionRules.bobbinText(bobbinLevels, bobbers))
    }

    override fun id(): String = "legion_display"
    override fun displayName(): String = "Legion Display"
    override fun width(font: Font?): Int = rows().ifEmpty { SAMPLE }.maxOf { font!!.width(it) }
    override fun height(font: Font?): Int = rows().ifEmpty { SAMPLE }.size * (font!!.lineHeight + 1)
    override fun previewWidth(font: Font?): Int = SAMPLE.maxOf { font!!.width(it) }
    override fun previewHeight(font: Font?): Int = SAMPLE.size * (font!!.lineHeight + 1)
    override fun visible(): Boolean = isEnabled() && rows().isNotEmpty()
    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = draw(graphics, font, x, y, rows())

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
        draw(graphics, font, x, y, rows().ifEmpty { SAMPLE })

    private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, rows: List<String>) {
        rows.forEachIndexed { index, row ->
            graphics.text(font, Component.literal(row), x, y + index * (font.lineHeight + 1), WHITE)
        }
    }

    /** The optional 30-block range sphere around you, only while a row is showing. */
    fun render(context: LevelRenderContext) {
        if (!isEnabled() || !s.sphere.value() || rows().isEmpty()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val cam = client.gameRenderer.mainCamera().position()
        val box = ProjectedLabelRenderer.entityBox(player)
        val buffer = WorldRenderBuffer.current()
        // Centred on the feet: the range is measured between entity positions.
        EspRenderer.renderRangeSphere(
            context.poseStack(), buffer, box.center.x - cam.x, box.minY - cam.y, box.center.z - cam.z,
            LegionRules.RANGE.toFloat(), s.sphereColor.argb(), s.fill.value(),
        )
        GeilerAddonsRenderTypes.endBatches(buffer)
    }

    private class Settings {
        val onlyWithEnchant = BooleanSetting("Only With Enchant", "Only Show A Row While You Wear The Enchant", true)
        val sphere = BooleanSetting("Range Sphere", "30 Block Sphere Around You", false)
        val fill = BooleanSetting("Fill", "Fill The Sphere, Not Only Its Lines", false)
        val sphereColor = ColorSetting("Sphere Color", 255, 85, 255, 200)
    }

    companion object {
        private const val ARMOR_TICKS = 20
        private const val REAL_PLAYER_UUID = 4
        private const val WHITE = 0xFFFFFFFF.toInt()
        private val ARMOR = arrayOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
        private val SAMPLE = listOf(LegionRules.legionText(5, 12), LegionRules.bobbinText(5, 3))

        @JvmField
        val INSTANCE = LegionDisplayModule(Settings())
    }
}
