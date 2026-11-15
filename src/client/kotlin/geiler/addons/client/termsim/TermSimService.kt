package geiler.addons.client.termsim

import geiler.addons.client.dungeon.f7.F7Support
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.f7.PersonalBestFile
import geiler.addons.client.module.Category
import geiler.addons.client.module.impl.ModuleSounds
import geiler.addons.client.module.impl.TerminalSolverModule
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import kotlin.random.Random

/** Registry lookups and item stacks for the simulators. */
object TermSimItems {
    private fun item(path: String): Item? =
        Identifier.tryParse("minecraft:$path")?.let { BuiltInRegistries.ITEM.getValue(it) }?.takeIf { it != Items.AIR }

    @JvmStatic
    fun stack(simItem: SimItem): ItemStack {
        val item = item(simItem.path) ?: return ItemStack.EMPTY
        if (simItem.count <= 0) return ItemStack.EMPTY
        val stack = ItemStack(item, simItem.count)
        simItem.name?.let { stack.set(DataComponents.CUSTOM_NAME, Component.literal(it)) }
        if (simItem.lore.isNotEmpty()) stack.set(DataComponents.LORE, ItemLore(simItem.lore.map { Component.literal(it) }))
        if (simItem.glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return stack
    }

    /** Every item's registry path, the ones that always glint, and the name a player sees. */
    val pool: ItemPool by lazy {
        val paths = ArrayList<String>()
        val glint = HashSet<String>()
        for (item in BuiltInRegistries.ITEM) {
            val path = BuiltInRegistries.ITEM.getKey(item).path
            paths.add(path)
            if (item.components().has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)) glint.add(path)
        }
        glint.add("golden_apple")
        ItemPool(paths, glint) { path -> item(path)?.let { ItemStack(it).hoverName.string } ?: path }
    }
}

/**
 * Opens simulators, keeps the simulator personal bests and speaks to the player through local chat only. Nothing in
 * this object or in the `termsim` package sends a packet or a chat message to the server.
 */
object TermSimService {
    private val bests = PersonalBestFile(
        "terminal-sim-personal-bests.json",
        mapOf("PANES" to 0.002f, "NUMBERS" to 0.003f, "STARTS_WITH" to 1.373f, "RUBIX" to 1.506f, "MELODY" to 7.168f, "SELECT" to 1.209f),
    )
    private val rng = Random.Default

    fun message(text: String) = F7Support.message(text)

    fun clickSound() {
        if (!TerminalSolverModule.INSTANCE.clickSoundsActive()) ModuleSounds.play("minecraft:entity.experience_orb.pickup", 1f, 1f)
    }

    private fun bestOf(kind: SimKind): Float? = bests.bests().get(kind.name)

    /** Opens the start menu. */
    @JvmStatic
    fun openStart(ping: Long) {
        show(StartMenu({ bestOf(it) }, { bests.reset() }, rng), null, ping)
    }

    /** Opens a simulator of [kind], or the start menu for null. */
    @JvmStatic
    fun open(kind: SimKind?, ping: Long) {
        if (kind == null) openStart(ping) else show(kind.create(rng, TermSimItems.pool), kind, ping)
    }

    @JvmStatic
    fun open(request: SimRequest, ping: Long) {
        when {
            request.random -> open(StartMenu.RANDOM_KINDS.random(rng), ping)
            else -> open(request.kind, ping)
        }
    }

    /** Opens from `/ga termsim`. The command already deferred this call past the closing chat: no second task. */
    @JvmStatic
    fun openFromCommand(kind: SimKind?, ping: Long) {
        val sim = if (kind == null) StartMenu({ bestOf(it) }, { bests.reset() }, rng) else kind.create(rng, TermSimItems.pool)
        GeilerAddonsLog.write(Category.F7, "Terminal Simulator", 0L, "OPEN_COMMAND terminal=${kind?.id ?: "start"} ping=${ping.coerceIn(0L, 5000L)}")
        showNow(sim, kind, ping)
    }

    private fun show(sim: TermSim, kind: SimKind?, ping: Long) {
        val mc = Minecraft.getInstance()
        mc.player ?: return
        mc.execute { showNow(sim, kind, ping) }
    }

    private fun showNow(sim: TermSim, kind: SimKind?, ping: Long) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (player.containerMenu !== player.inventoryMenu) {
            message("§cClose your open container first: the simulator would leave it open on the server.")
            return
        }
        val screen = TermSimScreen.create(sim, kind, ping.coerceIn(0L, 5000L))
        mc.setScreenAndShow(screen)
        screen.start()
    }

    /** A terminal was finished after [elapsedMillis]: record the best, tell the player, back to the menu. */
    fun completed(kind: SimKind?, elapsedMillis: Long, ping: Long) {
        if (kind != null) {
            val seconds = elapsedMillis / 1000f
            val outcome = bests.record(kind.name, seconds)
            val tail = if (outcome.newBest) "§7(§d§lNew PB§r§7) Old PB was §8${outcome.old}" else "§8(§7${outcome.old}§8)"
            message("§a${kind.termName} §7(termsim) §7solved in §6${seconds}s§7! $tail")
        }
        openStart(ping)
    }
}
