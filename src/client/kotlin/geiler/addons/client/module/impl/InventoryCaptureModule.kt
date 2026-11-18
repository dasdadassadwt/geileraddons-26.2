package geiler.addons.client.module.impl

import geiler.addons.client.config.ConfigTransferService
import geiler.addons.client.misc.InventoryCaptureRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.utility.PlayerAlerts
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A debug key that saves every slot of the open container, and of your own inventory, to a text file in
 * `config/geileraddons/captures`, so it can be sent to someone (or an AI agent) who needs to see where things are.
 */
class InventoryCaptureModule private constructor() : Module(
    "Inventory Capture",
    "Saves the open menu and your inventory to a text file when you press the capture key.",
    Category.DEV,
    false,
    *spec.settings.toTypedArray(),
) {
    private val captureKey = spec.text("captureKey")
    private var captures = 0L
    private val diagnostics = ModuleDiagnostics(Category.DEV, { name() }, 8, 100L, 300)

    // No group(): the settings data declares no groups, and an empty list hid the capture key entirely.

    override fun onEnable() {
        captures = 0L
        diagnostics.enable("key=${ContainerKeybindRules.normalize(captureKey.value())}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE captures=$captures")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true captures=$captures suppressedEvents=$it" }
    }

    /** A key press in a container screen; true means the press was the capture key and must not reach the screen. */
    fun onKey(screen: AbstractContainerScreen<*>, pressedKeyName: String): Boolean {
        if (!isEnabled()) return false
        val wanted = ContainerKeybindRules.normalize(captureKey.value())
        if (wanted.isEmpty() || wanted != pressedKeyName) return false
        val player = Minecraft.getInstance().player ?: return false
        val lines = ArrayList<String>()
        lines += "screen=${screen.javaClass.simpleName} title=${screen.title.string} slots=${screen.menu.slots.size}"
        for (slot in screen.menu.slots) {
            if (lines.size > InventoryCaptureRules.MAX_SLOTS) break
            val stack = slot.item
            if (stack.isEmpty) continue
            lines += InventoryCaptureRules.slotLine(
                slot.index, slot.container === player.inventory,
                BuiltInRegistries.ITEM.getKey(stack.item).toString(), stack.count, stack.hoverName.string,
                stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList(),
            )
        }
        val directory = ConfigTransferService.configDirectory().resolve("captures")
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = directory.resolve(InventoryCaptureRules.fileName(stamp))
        return try {
            Files.createDirectories(directory)
            Files.write(file, lines, StandardCharsets.UTF_8)
            captures++
            diagnostics.event("CAPTURE slots=${lines.size - 1} file=${file.fileName}")
            PlayerAlerts.chat("§aInventory saved: ${file.toAbsolutePath()}")
            true
        } catch (error: java.io.IOException) {
            diagnostics.event("CAPTURE_FAILED ${error.javaClass.simpleName}")
            PlayerAlerts.chat("§cCould not save the inventory capture: ${error.message}")
            true
        }
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Inventory Capture")

        @JvmField
        val INSTANCE = InventoryCaptureModule()
    }
}
