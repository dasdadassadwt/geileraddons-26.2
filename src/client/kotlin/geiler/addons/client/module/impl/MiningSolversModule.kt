package geiler.addons.client.module.impl

import geiler.addons.client.mining.FossilSolver
import geiler.addons.client.mining.MiningConditions
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.Slot
import net.minecraft.world.phys.Vec3

/**
 * The Dwarven Mines helpers: Fetchur's daily item named from his hint (Skyblocker's Fetchur), Puzzler's dig spot
 * boxed from his arrows (Skyblocker's Puzzler), and the Fossil Excavator solver that marks the best chisel slot
 * (SkyHanni's Fossil Solver).
 */
class MiningSolversModule private constructor(settings: Settings) : MiningHudModule(
    "Mining Solvers",
    "Names Fetchur's item, marks Puzzler's dig spot and solves the Fossil Excavator.",
    "mining_solvers",
    "Mining Solvers",
    listOf("§eFetchur: §fMithril", "§ePuzzler: §f181, 195, 145"),
    settings.display,
    settings.appearance,
) {
    private val fetchur = settings.fetchur
    private val puzzler = settings.puzzler
    private val fossil = settings.fossil

    private var fetchurItem: String? = null
    private var fetchurNanos = 0L
    private var puzzlerSpot: Pair<Int, Int>? = null
    private var puzzlerNanos = 0L

    private var fossilScreen = false
    private var fossilMenu = false
    private var fossilSignature = emptyList<String>()
    private var fossilMaxCharges = 0
    private var fossilCharges = 0
    private var fossilPercentage: String? = null
    private var fossilResult: FossilSolver.Result? = null
    private var tickCount = 0L
    private var solves = 0L

    override fun diagnosticSettings(): String = "fetchur=${fetchur.value()};puzzler=${puzzler.value()};fossil=${fossil.value()}"

    override fun onEnable() {
        fetchurItem = null
        puzzlerSpot = null
        resetFossil()
        solves = 0
        super.onEnable()
    }

    private fun resetFossil() {
        fossilScreen = false
        fossilMenu = false
        fossilSignature = emptyList()
        fossilMaxCharges = 0
        fossilCharges = 0
        fossilPercentage = null
        fossilResult = null
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { onChatMessage(plain) }
            return
        }
        if (fetchur.value()) {
            MiningConditions.fetchurItem(plain)?.let {
                fetchurItem = it
                fetchurNanos = System.nanoTime()
                diagnostics.event("FETCHUR item=\"$it\"")
            }
        }
        if (puzzler.value()) {
            MiningConditions.puzzlerSpot(plain)?.let {
                puzzlerSpot = it
                puzzlerNanos = System.nanoTime()
                diagnostics.event("PUZZLER x=${it.first} z=${it.second}")
            }
        }
    }

    fun tick() {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        beginTick()
        tickCount++
        val now = System.nanoTime()
        if (now - fetchurNanos > NOTE_LIFETIME_NANOS) fetchurItem = null
        val spot = puzzlerSpot
        val player = mc.player
        if (spot != null && (now - puzzlerNanos > NOTE_LIFETIME_NANOS ||
                (player != null && player.distanceToSqr(spot.first + 0.5, 195.0, spot.second + 0.5) < REACHED_SQ))
        ) {
            puzzlerSpot = null
            diagnostics.event("PUZZLER_CLEARED")
        }
        val out = ArrayList<String>(2)
        if (fetchur.value()) fetchurItem?.let { out.add("§eFetchur: §f$it") }
        if (puzzler.value()) puzzlerSpot?.let { out.add("§ePuzzler: §f${it.first}, 195, ${it.second}") }
        lines = out
        if (fossil.value()) readFossil(mc) else resetFossil()
        diagnostics.statusOnChange {
            "fetchur=${fetchurItem != null} puzzler=${puzzlerSpot != null} fossilScreen=$fossilScreen " +
                "solves=$solves charges=$fossilCharges/$fossilMaxCharges next=${fossilResult?.slot}"
        }
    }

    private fun readFossil(mc: Minecraft) {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*>
        if (screen == null || !screen.title.string.trim().equals("Fossil Excavator", ignoreCase = true)) {
            if (fossilScreen) resetFossil()
            return
        }
        fossilScreen = true
        val slots = screen.menu.slots
        val names = slots.take(BOARD_SLOTS).map { it.item.hoverName.string.trim() }
        fossilMenu = names.contains("Start Excavator")
        if (fossilMenu || names == fossilSignature) return
        fossilSignature = names
        val fossilSlots = HashSet<Int>()
        val dirtSlots = HashSet<Int>()
        var chargesRead = false
        for ((index, slot) in slots.take(BOARD_SLOTS).withIndex()) {
            val kind = names[index]
            if (kind != "Dirt" && kind != "Fossil") continue
            if (kind == "Dirt") dirtSlots.add(index) else fossilSlots.add(index)
            for (line in SkyblockItems.loreLines(slot.item)) {
                if (!chargesRead) CHARGES.find(line.trim())?.let {
                    fossilCharges = it.groupValues[1].toInt()
                    if (fossilMaxCharges == 0) fossilMaxCharges = fossilCharges
                    chargesRead = true
                }
                if (kind == "Fossil" && fossilPercentage == null) {
                    PROGRESS.find(line.trim())?.let { fossilPercentage = it.groupValues[1] }
                }
            }
        }
        val result = FossilSolver.solve(fossilSlots, dirtSlots, fossilPercentage, fossilMaxCharges)
        fossilResult = result
        solves++
        diagnostics.event(
            "FOSSIL_SOLVED state=${result.state} slot=${result.slot} placements=${result.placements} " +
                "found=${fossilSlots.size} dirt=${dirtSlots.size} progress=$fossilPercentage",
        )
    }

    /** Behind the items: the best chisel slot in green, then a short summary in the screen's corner. */
    fun drawFossilOverlay(graphics: GuiGraphicsExtractor, slots: List<Slot>) {
        if (!isEnabled() || !fossil.value() || !fossilScreen || fossilMenu) return
        val result = fossilResult ?: return
        if (result.state == FossilSolver.State.NEXT) {
            for ((index, slot) in slots.withIndex()) {
                if (index == result.slot && slot.isActive) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, HIGHLIGHT)
            }
        }
        val font = Minecraft.getInstance().font
        val text = ArrayList<String>(4)
        when (result.state) {
            FossilSolver.State.IMPOSSIBLE -> text.add("§cNo possible fossils on board.")
            FossilSolver.State.COMPLETED -> text.add("§aFossil found, get all the loot you can.")
            else -> text.add(
                "§eChisel the green slot: §a${"%.1f".format(java.util.Locale.ROOT, result.chance * 100)}% §7(${result.placements} placements)",
            )
        }
        text.add("§eCharges left: §a$fossilCharges")
        if (result.possible.isNotEmpty()) text.add("§7Possible: " + result.possible.joinToString(", ") { it.label })
        for ((row, line) in text.withIndex()) graphics.text(font, line, 6, 6 + row * (font.lineHeight + 2), 0xFFFFFFFF.toInt(), true)
    }

    /** A box on Puzzler's dig block at y 195, through walls. */
    fun render(context: LevelRenderContext) {
        if (!isEnabled() || !puzzler.value()) return
        val spot = puzzlerSpot ?: return
        val client = Minecraft.getInstance()
        val cam = client.gameRenderer.mainCamera().position()
        val buffer = WorldRenderBuffer.current()
        EspRenderer.renderBox(
            context.poseStack(), buffer, spot.first - cam.x, PUZZLER_Y - cam.y, spot.second - cam.z, 1.0, 1.0, 1.0,
            0x4055FF55, 0xFF55FF55.toInt(), 3.0f,
        )
        GeilerAddonsRenderTypes.endBatches(buffer)
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || !puzzler.value()) return
        val spot = puzzlerSpot ?: return
        val client = Minecraft.getInstance()
        ProjectedLabelRenderer.draw(
            graphics, client.gameRenderer.mainCamera(), client.font, Vec3(spot.first + 0.5, PUZZLER_Y + 1.6, spot.second + 0.5),
            "Puzzler", 0xFF55FF55.toInt(), 1.5f,
        )
    }

    private class Settings {
        val fetchur = BooleanSetting("Fetchur", true)
        val puzzler = BooleanSetting("Puzzler", true)
        val fossil = BooleanSetting("Fossil Excavator", true)
        val display: List<Setting> = listOf(fetchur, puzzler, fossil)
        val appearance = MiningHudAppearance()
    }

    companion object {
        private const val BOARD_SLOTS = 54
        private const val PUZZLER_Y = 195
        private const val NOTE_LIFETIME_NANOS = 600_000_000_000L
        private const val REACHED_SQ = 9.0
        private const val HIGHLIGHT = 0x9955FF55.toInt()
        private val CHARGES = Regex("Chisel Charges Remaining: (\\d+)")
        private val PROGRESS = Regex("Fossil Excavation Progress: ([\\d.]+%)")

        @JvmField
        val INSTANCE = MiningSolversModule(Settings())
    }
}
