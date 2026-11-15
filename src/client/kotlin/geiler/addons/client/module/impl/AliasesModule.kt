package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.utility.AliasStore
import geiler.addons.client.utility.BuiltInAliases
import net.minecraft.client.Minecraft
import java.util.Locale

/** Replaces a typed alias such as "dh" with a command such as "warp dh" before it is sent to the server. */
class AliasesModule private constructor(private val ui: Settings) : Module(
    "Aliases",
    "Type a short alias in chat and the server receives its command instead, for example dh becomes /warp dh.",
    Category.MISCELLANEOUS,
    false,
    ui.aliasText,
    ui.commandText,
    ui.addAlias,
    ui.builtIn,
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() })
    private val rows = LinkedHashMap<String, AliasRow>()
    private val rowDelete = HashMap<String, ModuleAction>()
    private var sending = false
    private var used = 0

    /** Per-alias controls on the module page; the store stays the persisted source of truth. */
    private class AliasRow(entry: AliasStore.Entry) {
        val enabled = BooleanSetting("Enabled", entry.enabled())
        val command = TextSetting("Command", "Command", entry.command(), AliasStore.MAX_COMMAND)
    }

    override fun groups(): List<SettingGroup> {
        syncRows()
        val children = ArrayList<SettingGroup>()
        for (entry in AliasStore.instance().entries()) {
            val row = rows.getOrPut(entry.alias()) { AliasRow(entry) }
            val delete = rowDelete.getOrPut(entry.alias()) {
                ModuleAction("Delete Alias", "Remove this alias.", Runnable { remove(entry.alias()) })
            }
            children.add(
                SettingGroup.switched(entry.alias(), row.enabled, row.command, delete)
                    .keyed("alias:${entry.alias().lowercase(Locale.ROOT)}"),
            )
        }
        return listOf(
            SettingGroup("Built-in Aliases", ui.builtIn),
            SettingGroup("Add Alias", ui.aliasText, ui.commandText, ui.addAlias),
            SettingGroup("Aliases", null, false, emptyList<Setting>(), children, false, "aliases-list"),
        )
    }

    /** Writes row switch and command edits back to the store so they persist without a Save button. */
    private fun syncRows() {
        val store = AliasStore.instance()
        val live = HashSet<String>()
        for (entry in store.entries()) {
            live.add(entry.alias())
            val row = rows[entry.alias()] ?: continue
            val command = row.command.value().trim()
            if (row.enabled.value() != entry.enabled() || command != entry.command()) {
                if (store.put(entry.alias(), command, row.enabled.value())) {
                    diagnostics.event("ALIAS_EDITED alias=${bounded(entry.alias())} enabled=${row.enabled.value()} commandChars=${command.length}")
                }
            }
        }
        rows.keys.retainAll(live)
        rowDelete.keys.retainAll(live)
    }

    private fun remove(alias: String) {
        if (AliasStore.instance().remove(alias)) diagnostics.event("ALIAS_REMOVED alias=${bounded(alias)}")
        rows.remove(alias)
        rowDelete.remove(alias)
    }

    private fun addAlias() {
        if (AliasStore.instance().put(ui.aliasText.value(), ui.commandText.value(), true)) {
            diagnostics.event("ALIAS_ADDED alias=${bounded(ui.aliasText.value())} commandChars=${ui.commandText.value().length}")
            ui.aliasText.setValue("")
            ui.commandText.setValue("")
        } else {
            diagnostics.event("ALIAS_REJECTED aliasChars=${ui.aliasText.value().length} commandChars=${ui.commandText.value().length}")
        }
    }

    override fun onEnable() {
        diagnostics.enable("aliases=${AliasStore.instance().entries().size}")
    }

    override fun onDisable() {
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        syncRows()
        diagnostics.status { suppressed -> "aliases=${AliasStore.instance().entries().size} usedSinceLastStatus=$used suppressedEvents=$suppressed".also { used = 0 } }
    }

    /** Built-in queue aliases come first; saved aliases never use a built-in name, so the order cannot conflict. */
    private fun resolve(input: String?, replacementInFlight: Boolean): String? {
        if (!isEnabled() || replacementInFlight) return null
        if (ui.builtIn.value()) BuiltInAliases.command(input)?.let { return it }
        return AliasStore.instance().resolve(input)
    }

    /** True when this outgoing text is an enabled alias. A replacement being sent is never aliased again. */
    fun shouldIntercept(command: Boolean, input: String?, replacementInFlight: Boolean): Boolean =
        resolve(input, replacementInFlight) != null

    /** Sends the alias's command instead of [input]; returns true when the original must not be sent. */
    fun intercept(input: String?, command: Boolean): Boolean {
        val resolved = resolve(input, sending) ?: return false
        val player = Minecraft.getInstance().player ?: return false
        sending = true
        try {
            player.connection.sendCommand(resolved)
        } finally {
            sending = false
        }
        used++
        diagnostics.event("USED via=${if (command) "command" else "chat"} alias=${bounded(input.orEmpty())} commandChars=${resolved.length}")
        return true
    }

    private fun bounded(text: String): String = text.take(MAX_DIAGNOSTIC_CHARS).replace(' ', '_')

    private class Settings {
        val aliasText = TextSetting("Alias", "Alias (for example dh)", "", AliasStore.MAX_ALIAS)
        val commandText = TextSetting("Command", "Command (for example warp dh)", "", AliasStore.MAX_COMMAND)
        val builtIn = BooleanSetting("Built-in Aliases", true)
        val addAlias = ModuleAction(
            "Add Alias",
            "Save this alias. Each alias below has its own switch, command and Delete.",
            Runnable { INSTANCE.addAlias() },
        )
    }

    companion object {
        private const val MAX_DIAGNOSTIC_CHARS = 32

        @JvmField
        val INSTANCE = AliasesModule(Settings())
    }
}
