package geiler.addons.client.module.impl

import geiler.addons.client.foraging.ForagingRules
import geiler.addons.client.foraging.TunerSpeedMeter
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import java.util.IdentityHashMap

/**
 * Solves the Tune Frequency menu (the Galatea tuner): the colour, speed and pitch buttons each get the number of
 * clicks to the right setting, positive for a right click and negative for a left click. The target colour is the
 * pane in the top row, the target speed is measured from how fast that pane moves, and the target pitch is heard from
 * the bass notes the menu plays. Behavior follows Skyblocker's Tuner Solver; the slot layout is the menu's own.
 */
class TunerSolverModule private constructor() : Module(
    "Tuner Solver",
    "Shows the clicks needed on the Tune Frequency colour, speed and pitch buttons.",
    Category.FORAGING,
) {
    private val diagnostics = ModuleDiagnostics(Category.FORAGING, { name() }, 8, 100L, 512)
    private val overlay = IdentityHashMap<ItemStack, Int>()

    private var open = false
    private var lastTargetSlot = -1
    private val speedMeter = TunerSpeedMeter()
    private var serverTicks = 0L
    private var lastMoveTick = 0L
    private var speedLoreLogged = false
    private var targetSpeed = -1
    private var targetPitch = -1
    private var pitchMatches = 0
    private var colorClicks: Int? = null
    private var speedClicks: Int? = null
    private var pitchClicks: Int? = null
    private var sounds = 0L
    private var draws = 0L

    override fun onEnable() {
        reset()
        diagnostics.enable("slots=color:$DYE_SLOT,speed:$SPEED_SLOT,pitch:$PITCH_SLOT")
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        overlay.clear()
        open = false
        lastTargetSlot = -1
        speedMeter.reset()
        lastMoveTick = 0L
        speedLoreLogged = false
        targetSpeed = -1
        targetPitch = -1
        pitchMatches = 0
        colorClicks = null
        speedClicks = null
        pitchClicks = null
        sounds = 0
        draws = 0
    }

    /** Called once per client tick on the client thread. */
    fun tick() {
        if (!isEnabled()) return
        val minecraft = Minecraft.getInstance()
        if (!minecraft.isSameThread) {
            minecraft.execute { tick() }
            return
        }
        diagnostics.advance()
        val screen = minecraft.gui.screen() as? AbstractContainerScreen<*>
        val isTuner = screen != null && screen.title.string.trim().equals(TITLE, ignoreCase = true)
        if (!isTuner) {
            if (open) {
                diagnostics.event("MENU CLOSED sounds=$sounds draws=$draws color=$colorClicks speed=$speedClicks pitch=$pitchClicks")
                reset()
            }
            diagnostics.status { "open=false suppressedEvents=$it" }
            return
        }
        if (!open) {
            reset()
            open = true
            diagnostics.event("MENU OPENED")
        }
        val slots = screen!!.menu.slots
        if (slots.size <= PITCH_SLOT) return
        val stacks = slots.map { it.item }
        trackTargetMovement(stacks)
        logSpeedLore(stacks[SPEED_SLOT])
        colorClicks = solveColor(stacks)
        speedClicks = solveSpeed(stacks)
        pitchClicks = solvePitch(stacks)

        overlay.clear()
        colorClicks?.let { overlay[stacks[DYE_SLOT]] = it }
        speedClicks?.let { overlay[stacks[SPEED_SLOT]] = it }
        pitchClicks?.let { overlay[stacks[PITCH_SLOT]] = it }
        diagnostics.statusOnChangeBy(
            20L,
            { "color=$colorClicks,speed=$speedClicks,pitch=$pitchClicks" },
        ) {
            "open=true color=$colorClicks speed=$speedClicks(target=$targetSpeed) pitch=$pitchClicks(target=$targetPitch,matches=$pitchMatches) " +
                "sounds=$sounds draws=$draws"
        }
    }

    private fun path(stack: ItemStack): String =
        if (stack.isEmpty) "" else BuiltInRegistries.ITEM.getKey(stack.item).path

    /** One server tick, from the server's ping stream (negative ids, as Run Splits counts them). Client thread. */
    fun onServerPing(id: Int) {
        if (id < 0) serverTicks++
    }

    /** The target pane moves along the top row; the server ticks between its moves say how fast the target speed is. */
    private fun trackTargetMovement(stacks: List<ItemStack>) {
        val current = TARGET_SLOTS.firstOrNull { ForagingRules.paneIndex(path(stacks[it])) >= 0 } ?: return
        if (lastTargetSlot != -1 && current != lastTargetSlot) {
            val interval = serverTicks - lastMoveTick
            lastMoveTick = serverTicks
            val measured = speedMeter.move(serverTicks)
            if (measured > 0) targetSpeed = measured
            diagnostics.event("TARGET_MOVE interval=$interval speed=$measured serverTicks=$serverTicks")
        }
        lastTargetSlot = current
    }

    /** The speed button's own text, once per menu, so a wrong lore pattern shows up in the log. */
    private fun logSpeedLore(stack: ItemStack) {
        if (speedLoreLogged || stack.isEmpty) return
        val lore = SkyblockItems.loreLines(stack)
        if (lore.isEmpty()) return
        speedLoreLogged = true
        diagnostics.event("SPEED_LORE " + lore.take(8).joinToString(" | ").take(240))
    }

    private fun solveColor(stacks: List<ItemStack>): Int? {
        val dye = ForagingRules.colorIndex(path(stacks[DYE_SLOT]))
        if (dye < 0) return null
        val target = TARGET_SLOTS.firstNotNullOfOrNull { slot ->
            ForagingRules.paneIndex(path(stacks[slot])).takeIf { it >= 0 }
        } ?: return null
        return ForagingRules.cycleClicks(dye, target, ForagingRules.COLOR_CYCLE)
    }

    private fun solveSpeed(stacks: List<ItemStack>): Int? {
        if (targetSpeed < 1) return null
        val current = SkyblockItems.loreLines(stacks[SPEED_SLOT]).firstNotNullOfOrNull {
            ForagingRules.speedFromLore(it).takeIf { speed -> speed > 0 }
        } ?: return null
        return ForagingRules.cycleClicks(current - 1, targetSpeed - 1, ForagingRules.SPEED_CYCLE)
    }

    private fun currentPitch(stack: ItemStack): Int =
        SkyblockItems.loreLines(stack).map { ForagingRules.pitchFromLore(it) }.firstOrNull { it >= 0 } ?: -1

    private fun solvePitch(stacks: List<ItemStack>): Int? {
        if (targetPitch < 0) return null
        val current = currentPitch(stacks[PITCH_SLOT])
        if (current < 0) return null
        return ForagingRules.cycleClicks(current, targetPitch, ForagingRules.PITCH_CYCLE)
    }

    /** The menu plays the target pitch as a bass note; five notes at the current pitch mean it is already right. */
    fun onSound(sound: Holder<SoundEvent>, pitch: Float) {
        if (!isEnabled() || !open) return
        if (sound.value().location() != SoundEvents.NOTE_BLOCK_BASS.value().location()) return
        sounds++
        val heard = ForagingRules.pitchIndex(pitch)
        if (heard < 0) {
            diagnostics.event("PITCH_UNKNOWN pitch=$pitch")
            return
        }
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return
        val slots = screen.menu.slots
        if (slots.size <= PITCH_SLOT) return
        val current = currentPitch(slots[PITCH_SLOT].item)
        if (current < 0) return
        if (heard != current) {
            targetPitch = heard
            pitchMatches = 0
            diagnostics.event("TARGET_PITCH heard=$heard current=$current")
        } else if (++pitchMatches >= PITCH_MATCHES_NEEDED && targetPitch < 0) {
            targetPitch = current
            diagnostics.event("TARGET_PITCH matches current=$current")
        }
    }

    /** The click count in a button's corner, after the item is drawn. */
    fun drawOnItem(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (!isEnabled() || !open || stack.isEmpty) return
        val clicks = overlay[stack] ?: return
        val text = if (clicks > 0) "+$clicks" else clicks.toString()
        val font = Minecraft.getInstance().font
        draws++
        graphics.text(font, text, x + 17 - font.width(text), y + 9, GREEN, true)
    }

    companion object {
        private const val TITLE = "Tune Frequency"
        private const val DYE_SLOT = 46
        private const val SPEED_SLOT = 48
        private const val PITCH_SLOT = 50
        private const val PITCH_MATCHES_NEEDED = 5
        private const val GREEN = 0xFF55FF55.toInt()
        private val TARGET_SLOTS = 10..16

        @JvmField
        val INSTANCE = TunerSolverModule()
    }
}
