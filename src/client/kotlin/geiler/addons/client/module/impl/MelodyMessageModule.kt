package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.MelodyRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.termsim.TermSimScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.Items

/** Sends a start message and progress messages when you open the Melody terminal in F7 (NoFrills' Melody Message). */
class MelodyMessageModule private constructor() : Module(
    "Melody Message",
    "Sends start and progress messages for the Melody terminal in F7 and M7.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val msg = spec.text("msg")
    private val progress = spec.toggle("progress")
    private val progressMsg = spec.text("progressMsg")

    private var open = false
    private var lastCount = ROWS
    private var sent = 0
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    init {
        group(*spec.groups.toTypedArray())
    }

    override fun onEnable() {
        open = false
        lastCount = ROWS
        sent = 0
        diagnostics.enable("progress=${progress.value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE sent=$sent")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val screen = client.gui.screen() as? AbstractContainerScreen<*>
        // The Terminal Simulator uses the same title; its screen must never make this module send chat.
        val melody = screen != null && screen !is TermSimScreen && MelodyRules.isMelodyTitle(screen.title.string) && onFloor7()
        if (melody && !open) {
            lastCount = ROWS
            send(msg.value(), "START")
        }
        open = melody
        if (melody && progress.value() && screen != null) {
            val slots = containerSlots(screen)
            val result = MelodyRules.progress(slots, lastCount)
            if (result != null) {
                lastCount = result.count
                send(MelodyRules.format(progressMsg.value(), result.percent), "PROGRESS ${result.percent}")
            }
        }
        diagnostics.status { "enabled=true open=$open lastCount=$lastCount sent=$sent suppressedEvents=$it" }
    }

    private fun onFloor7(): Boolean = DungeonContextTracker.inDungeon() && DungeonContextTracker.currentFloor()?.number() == 7

    /** The terminal's own slots (not the player inventory) mapped to [MelodyRules] codes. */
    private fun containerSlots(screen: AbstractContainerScreen<*>): IntArray {
        val all = screen.menu.slots
        val count = (all.size - PLAYER_SLOTS).coerceAtLeast(0)
        return IntArray(count) { i ->
            val stack = all[i].item
            when {
                stack.isEmpty -> MelodyRules.EMPTY
                stack.item == Items.DYED_TERRACOTTA.red() -> MelodyRules.RED
                stack.item == Items.DYED_TERRACOTTA.lime() -> MelodyRules.LIME
                else -> MelodyRules.OTHER
            }
        }
    }

    private fun send(text: String, event: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        val connection = Minecraft.getInstance().player?.connection ?: return
        if (value.startsWith("/")) connection.sendCommand(value.substring(1)) else connection.sendChat(value)
        sent++
        diagnostics.event("SENT $event chars=${value.length}")
    }

    companion object {
        private const val ROWS = 3
        private const val PLAYER_SLOTS = 36
        private val spec = ModuleSettingsSpec.of("Melody Message")

        @JvmField
        val INSTANCE = MelodyMessageModule()
    }
}
