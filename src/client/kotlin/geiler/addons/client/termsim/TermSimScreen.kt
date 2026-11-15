package geiler.addons.client.termsim

import geiler.addons.client.module.impl.TerminalSolverModule
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.PlayerEquipment
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot

/**
 * A terminal simulator shown as a chest screen over a menu and containers that exist only on this client (Odin's
 * `TermSimGUI`, BSD 3-Clause; written independently). It never talks to the server:
 *
 * - the menu is never registered as the player's open container, so the server has no container to receive clicks for;
 * - `slotClicked` is overridden in [TermSimBase] and never calls `super`, the only place vanilla sends a container click;
 * - `onClose` leaves the screen directly instead of calling `closeContainer`, which would send a close packet;
 * - [TermSimGuard] drops any container packet that still tries to leave while this screen is showing.
 */
class TermSimScreen private constructor(
    val sim: TermSim,
    private val kind: SimKind?,
    private val container: SimpleContainer,
    private val ping: Long,
) : TermSimBase(
    ChestMenu(menuType(sim.size), LOCAL_CONTAINER_ID, localInventory(), container, sim.size / 9),
    localInventory(),
    Component.literal(sim.title),
) {
    private var openedAt = 0L
    private var tickCount = 0
    private val queued = ArrayList<Triple<Int, Int, Int>>()
    private var finished = false

    override fun init() {
        TermSimGuard.opened(this)
        super.init()
    }

    /** Fills the board; called right after the screen is shown, as Odin does. */
    fun start() {
        openedAt = System.currentTimeMillis()
        sim.create()
        sync()
    }

    private fun sync() {
        for (index in sim.takeDirty()) menu.setItem(index, menu.incrementStateId(), TermSimItems.stack(sim.slots[index]))
    }

    override fun containerTick() {
        tickCount++
        sim.tick()
        (sim as? StartMenu)?.takeMessages()?.forEach { TermSimService.message(it) }
        val due = queued.filter { it.first <= tickCount }
        queued.removeAll(due.toSet())
        for ((_, index, button) in due) perform(index, button)
        sync()
        super.containerTick()
    }

    /** A click on a simulator slot: from the mouse, or from the Terminal Solver. Local only. */
    fun clickIndex(index: Int, button: Int) {
        if (index !in 0 until sim.size) return
        if (kind == null || ping <= 0L) return perform(index, button)
        if (sim.slots[index].isBlackPane) return
        queued.add(Triple(tickCount + (ping / 50).toInt().coerceAtLeast(0), index, button))
    }

    private fun perform(index: Int, button: Int) {
        if (finished || Minecraft.getInstance().gui.screen() !== this) return
        val outcome = sim.click(index, button)
        if (outcome.sound) TermSimService.clickSound()
        outcome.messages.forEach { TermSimService.message(it) }
        sync()
        outcome.open?.let { return TermSimService.open(it, ping) }
        if (outcome.completed) {
            finished = true
            TermSimService.completed(kind, System.currentTimeMillis() - openedAt, ping)
        }
    }

    /** The vanilla click path ([TermSimBase] never calls `super`), so no container click packet is built. */
    override fun simSlotClicked(slot: Slot?, slotId: Int, button: Int) {
        if (TerminalSolverModule.INSTANCE.onSlotClicked(this, slotId, button)) return
        if (slot == null || slot.container !== container) return
        if (sim.slots.getOrNull(slot.index)?.isBlackPane != false) return
        clickIndex(slot.index, button)
    }

    /** Leaves the screen without telling the server: vanilla's `onClose` would send a container close packet. */
    override fun onClose() {
        Minecraft.getInstance().gui.setScreen(null)
    }

    override fun removed() {
        TermSimGuard.closed(this)
        super.removed()
    }

    companion object {
        /**
         * A container id no server uses (the server counts 1..100 and the player inventory is 0), so a click that
         * names this menu never matches `player.containerMenu`: vanilla ignores it before building a packet.
         */
        const val LOCAL_CONTAINER_ID = 117

        private fun menuType(size: Int): MenuType<ChestMenu> = when {
            size <= 9 -> MenuType.GENERIC_9x1
            size <= 18 -> MenuType.GENERIC_9x2
            size <= 27 -> MenuType.GENERIC_9x3
            size <= 36 -> MenuType.GENERIC_9x4
            size <= 45 -> MenuType.GENERIC_9x5
            else -> MenuType.GENERIC_9x6
        }

        /** A throwaway inventory: not the player's, so nothing in the simulator can touch real items. */
        private fun localInventory(): Inventory {
            val player = Minecraft.getInstance().player!!
            return Inventory(player, PlayerEquipment(player))
        }

        @JvmStatic
        fun create(sim: TermSim, kind: SimKind?, ping: Long) = TermSimScreen(sim, kind, SimpleContainer(sim.size), ping)
    }
}
