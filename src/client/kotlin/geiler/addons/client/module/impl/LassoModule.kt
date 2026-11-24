package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.f7.F7Support
import geiler.addons.client.hud.HudElement
import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.hunting.LassoLogic
import geiler.addons.client.hunting.ReelScheduler
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.macros.input.SimulatedMouse
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Leashable
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.entity.EntityTypeTest

/**
 * Lasso HUD: how full the stamina bar over a lassoed critter is, and REEL NOW at the reel point. Auto Reel right-clicks
 * once per REEL prompt after a random reaction delay and holds the click for a random time.
 *
 * The lassoed entity is whatever is leashed to the local player. The bar is an armour stand next to it named with 20
 * blank cells whose first styled part holds the filled cells (Skyblocker's Lasso HUD); REEL is a separate tag within
 * 2 blocks (NoFrills' Lasso Alert). Behaviour only; the code is written independently.
 */
class LassoModule private constructor(private val s: Settings) : Module(
    "Lasso",
    "Lasso stamina HUD with a reel alert and optional Auto Reel.",
    Category.HUNTING,
    s.alertTitle, s.alertSound, s.autoReel, s.reactMin, s.reactMax, s.holdMin, s.holdMax,
), HudElement {
    class Settings {
        val alertTitle = BooleanSetting("Reel Title", false)
        val alertSound = BooleanSetting("Reel Sound", true)
        val autoReel = BooleanSetting("Auto Reel", false)
        val reactMin = NumberSetting("Reaction Min (ms)", 0f, 1000f, 180f, true)
        val reactMax = NumberSetting("Reaction Max (ms)", 0f, 1000f, 320f, true)
        val holdMin = NumberSetting("Click Hold Min (ms)", 10f, 500f, 60f, true)
        val holdMax = NumberSetting("Click Hold Max (ms)", 10f, 500f, 110f, true)
    }

    private val scheduler = ReelScheduler()
    private val diagnostics = ModuleDiagnostics(Category.HUNTING, { name() }, 8, 100L, 480)
    private var lassoed: Entity? = null
    private var reelCells = 0
    private var percent = -1
    private var reelReady = false
    private var reels = 0
    private var autoClicks = 0
    private var cancelled = 0

    init {
        group(
            SettingGroup("Alert", s.alertTitle, s.alertSound),
            SettingGroup("Auto Reel", s.autoReel, s.reactMin, s.reactMax, s.holdMin, s.holdMax),
        )
    }

    override fun isActive(): Boolean = isEnabled() && HypixelModApi.currentIsland().let { it == Island.GALATEA || it == Island.TORRHUS_CANYON }
    override fun inactiveReason(): String? = if (isEnabled() && !isActive()) "Not on Galatea or in Torrhus Canyon" else null

    override fun onEnable() {
        reels = 0
        autoClicks = 0
        cancelled = 0
        diagnostics.enable(settingsLine())
    }

    override fun onDisable() {
        releaseIfHeld(Minecraft.getInstance())
        clear()
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val mc = Minecraft.getInstance()
        val player = mc.player
        val heldId = player?.let { F7Support.skyblockId(it.mainHandItem) }
        val cells = LassoLogic.reelCells(heldId)
        val target = if (isActive() && player != null) Leashable.leashableLeashedTo(player).firstOrNull() as? Entity else null
        if (target == null || target.isRemoved) {
            if (lassoed != null) diagnostics.event("LASSO end reels=$reels")
            clear()
        } else {
            if (target !== lassoed) diagnostics.event("LASSO start item=${heldId ?: "-"} reelCells=${cells ?: 0}")
            lassoed = target
            if (cells != null) reelCells = cells
            readTags(target)
        }

        val allowed = s.autoReel.value() && lassoed != null && cells != null && mc.gui.screen() == null
        val waiting = scheduler.pending() && !scheduler.isPressed()
        when (scheduler.poll(System.currentTimeMillis(), allowed)) {
            ReelScheduler.Action.PRESS -> {
                SimulatedMouse.press(mc, SimulatedMouse.Button.RIGHT)
                autoClicks++
                diagnostics.event("AUTO_REEL press")
            }
            ReelScheduler.Action.RELEASE -> {
                SimulatedMouse.release(mc, SimulatedMouse.Button.RIGHT)
                diagnostics.event("AUTO_REEL release")
            }
            ReelScheduler.Action.NONE -> if (waiting && !scheduler.pending()) {
                cancelled++
                diagnostics.event("AUTO_REEL cancelled lassoed=${lassoed != null} lasso=${cells != null} screen=${mc.gui.screen() != null}")
            }
        }
        diagnostics.statusOnChangeBy(20L, { "${isActive()}|${lassoed != null}|$reelReady" }) {
            "active=${isActive()} lassoed=${lassoed != null} percent=$percent reel=$reelReady reels=$reels " +
                "autoClicks=$autoClicks cancelled=$cancelled ${settingsLine()}"
        }
    }

    private fun readTags(target: Entity) {
        val level = target.level()
        val near = level.getEntities(EntityTypeTest.forClass(ArmorStand::class.java), target.boundingBox.inflate(4.0)) { it.hasCustomName() }
        var ready = false
        for (stand in near) {
            val name = stand.customName ?: continue
            val plain = name.string
            if (LassoLogic.isBar(plain, name.siblings.size)) {
                percent = LassoLogic.percent(name.siblings[0].string.length, reelCells)
            } else if (LassoLogic.isReelPrompt(plain) && horizontal(stand, target) <= 2.0) {
                ready = true
            }
        }
        if (ready && !reelReady) onReel()
        reelReady = ready
        if (ready) percent = 0
    }

    private fun onReel() {
        reels++
        val mc = Minecraft.getInstance()
        if (s.alertTitle.value()) TitleAlertStack.submit("lasso", "§e§lREEL", 20, 0xFFFFFF55.toInt())
        if (s.alertSound.value()) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f))
        if (s.autoReel.value() && scheduler.onReel(System.currentTimeMillis(), s.reactMin.intValue(), s.reactMax.intValue(),
                s.holdMin.intValue(), s.holdMax.intValue())) {
            diagnostics.event("REEL scheduled")
        } else {
            diagnostics.event("REEL auto=${s.autoReel.value()}")
        }
    }

    private fun horizontal(a: Entity, b: Entity): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return Math.sqrt(dx * dx + dz * dz)
    }

    private fun clear() {
        lassoed = null
        percent = -1
        reelReady = false
    }

    private fun releaseIfHeld(mc: Minecraft) {
        if (scheduler.isPressed()) SimulatedMouse.release(mc, SimulatedMouse.Button.RIGHT)
        scheduler.cancel()
    }

    private fun settingsLine(): String =
        "auto=${s.autoReel.value()} react=${s.reactMin.intValue()}-${s.reactMax.intValue()} hold=${s.holdMin.intValue()}-${s.holdMax.intValue()} " +
            "title=${s.alertTitle.value()} sound=${s.alertSound.value()}"

    // ---- HUD ----------------------------------------------------------------------------

    override fun id(): String = "lasso_hud"
    override fun displayName(): String = "Lasso"
    override fun hudIslands(): Set<Island> = setOf(Island.GALATEA, Island.TORRHUS_CANYON)
    override fun visible(): Boolean = isActive() && lassoed != null && percent >= 0
    override fun previewMode(): String = if (visible()) "live" else "sample"
    override fun width(font: Font?): Int = WIDTH
    override fun height(font: Font?): Int = font!!.lineHeight + BAR_HEIGHT + 3

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = draw(graphics, font, x, y, percent, reelReady)

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
        if (visible()) render(graphics, font, x, y) else draw(graphics, font, x, y, 62, false)

    private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, value: Int, ready: Boolean) {
        val label = if (ready || value == 0) "§a§lREEL NOW" else "§3§lLasso §7$value%"
        graphics.text(font, label, x, y, 0xFFFFFFFF.toInt())
        val top = y + font.lineHeight + 2
        graphics.fill(x, top, x + WIDTH, top + BAR_HEIGHT, 0x90000000.toInt())
        val filled = (WIDTH - 2) * value.coerceIn(0, 100) / 100
        val color = if (ready || value == 0) 0xFF55FF55.toInt() else 0xFF55FFFF.toInt()
        if (ready || value == 0) graphics.fill(x + 1, top + 1, x + WIDTH - 1, top + BAR_HEIGHT - 1, color)
        else graphics.fill(x + 1, top + 1, x + 1 + filled, top + BAR_HEIGHT - 1, color)
    }

    companion object {
        @JvmField
        val INSTANCE = LassoModule(Settings())

        private const val WIDTH = 90
        private const val BAR_HEIGHT = 5
    }
}
