package geiler.addons.client.macros.input

import geiler.addons.client.macros.config.AreaKillerConfig
import geiler.addons.client.macros.module.EtherwarpSimModule
import geiler.addons.client.macros.sim.TeleportItem
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Finds the teleport item on the hotbar and puts its slot and reach into the configuration, so neither has to be typed in and the
 * macro knows how far one click really goes. Reads the item's data once per stack, not every tick.
 */
object TeleportDetect {
    class Found(val slot: Int, val info: TeleportItem.Info)

    private val stacks = arrayOfNulls<ItemStack>(9)
    private val infos = arrayOfNulls<TeleportItem.Info>(9)
    private var simTuners = -1

    fun find(player: LocalPlayer): Found? {
        val sim = EtherwarpSimModule.INSTANCE
        val simulated = sim.isActive()
        if (simTuners != (if (simulated) sim.tuners() else -1)) {
            simTuners = if (simulated) sim.tuners() else -1
            stacks.fill(null)
        }
        val list = ArrayList<TeleportItem.Info?>(infos.size)
        for (slot in infos.indices) {
            val stack = player.inventory.getItem(slot)
            if (stacks[slot] !== stack) {
                stacks[slot] = stack
                infos[slot] = read(stack, simulated, sim)
            }
            list.add(infos[slot])
        }
        val index = TeleportItem.best(list)
        return if (index < 0) null else Found(index, infos[index]!!)
    }

    private fun read(stack: ItemStack, simulated: Boolean, sim: EtherwarpSimModule): TeleportItem.Info? {
        if (stack.isEmpty) return null
        if (simulated && stack.`is`(Items.DIAMOND_SHOVEL)) return TeleportItem.simulated(sim.tuners())
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null
        return TeleportItem.classify(tag.getStringOr("id", ""), tag.getIntOr("ethermerge", 0), tag.getIntOr("tuned_transmission", 0))
    }

    /** What the last [apply] put in the configuration, for the log line that is written when it changes. */
    private var applied: String? = null

    /**
     * Sets the slot (1-based) and reaches in [config] from the item found, when there is one. Returns a log line when what was found
     * differs from the last time, else null.
     */
    fun apply(player: LocalPlayer, config: AreaKillerConfig): String? {
        val found = find(player) ?: return null
        config.teleportSlot = found.slot + 1
        if (found.info.instant) config.teleportDistance = found.info.instantReach
        if (found.info.ether) config.etherRange = found.info.etherReach else config.useEtherwarp = false
        val line = "teleport item slot=${found.slot + 1} instant=${found.info.instant}/${found.info.instantReach} " +
            "ether=${found.info.ether}/${found.info.etherReach} auto=true"
        if (line == applied) return null
        applied = line
        return line
    }
}
