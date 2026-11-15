package geiler.addons.client.utility

import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.Locale

/** Saved chat aliases: a short message that is replaced by a command before it reaches the server. */
class AliasStore private constructor(private val file: Path) {
    constructor() : this(FabricLoader.getInstance().configDir.resolve("geileraddons/aliases.json"))

    /** Immutable alias value. The command is stored without a leading slash. */
    data class Entry(private val aliasText: String, private val commandText: String, private val on: Boolean) {
        fun alias(): String = aliasText
        fun command(): String = commandText
        fun enabled(): Boolean = on
    }

    /** Persisted Gson shape. */
    class Data {
        @JvmField
        var aliases: MutableList<EntryData?>? = ArrayList()
    }

    class EntryData {
        @JvmField
        var alias: String? = null

        @JvmField
        var command: String? = null

        @JvmField
        var enabled: Boolean? = null
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var loaded = false

    @Synchronized
    fun entries(): List<Entry> {
        loadIfNeeded()
        return entries.values.sortedBy { key(it.alias()) }
    }

    /** The command for an enabled alias that equals the whole input (ignoring case, spaces and a leading slash). */
    @Synchronized
    fun resolve(input: String?): String? {
        if (input == null) return null
        loadIfNeeded()
        val entry = entries[key(input.trim().removePrefix("/"))] ?: return null
        return if (entry.enabled()) entry.command() else null
    }

    @Synchronized
    fun put(alias: String?, command: String?, enabled: Boolean): Boolean {
        loadIfNeeded()
        val cleanAlias = alias?.trim()?.removePrefix("/") ?: return false
        val cleanCommand = command?.trim()?.removePrefix("/")?.trim() ?: return false
        if (!valid(cleanAlias, cleanCommand) || BuiltInAliases.isBuiltInName(cleanAlias)) return false
        val key = key(cleanAlias)
        if (!entries.containsKey(key) && entries.size >= MAX_ENTRIES) return false
        entries[key] = Entry(cleanAlias, cleanCommand, enabled)
        save()
        return true
    }

    @Synchronized
    fun remove(alias: String?): Boolean {
        loadIfNeeded()
        if (alias == null || entries.remove(key(alias.trim().removePrefix("/"))) == null) return false
        save()
        return true
    }

    private fun valid(alias: String, command: String): Boolean =
        alias.isNotEmpty() && alias.length <= MAX_ALIAS && alias.none { it.isWhitespace() || Character.isISOControl(it) } &&
            command.isNotEmpty() && command.length <= MAX_COMMAND && command.none { Character.isISOControl(it) }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        val data = ClientJsonFile.readResult(file, Data::class.java).value ?: return
        for (saved in data.aliases ?: return) {
            val alias = saved?.alias?.trim() ?: continue
            val command = saved.command?.trim() ?: continue
            if (!valid(alias, command) || BuiltInAliases.isBuiltInName(alias) || entries.size >= MAX_ENTRIES) continue
            entries.putIfAbsent(key(alias), Entry(alias, command, saved.enabled != false))
        }
    }

    private fun save() {
        val data = Data()
        data.aliases = entries.values.map { entry ->
            EntryData().also {
                it.alias = entry.alias()
                it.command = entry.command()
                it.enabled = entry.enabled()
            }
        }.toMutableList()
        ClientJsonFile.writeAsync(file, ClientJsonFile.GSON.toJson(data))
    }

    private fun key(alias: String): String = alias.lowercase(Locale.ROOT)

    companion object {
        const val MAX_ENTRIES = 128
        const val MAX_ALIAS = 32
        const val MAX_COMMAND = 256

        private val shared by lazy { AliasStore() }

        /** The shared store; created on first use so checks that only use a temp file never touch the config dir. */
        @JvmStatic
        fun instance(): AliasStore = shared

        /** Isolated file seam for the Java check harness; production uses the shared instance. */
        @JvmStatic
        @JvmName("forPathChecks")
        internal fun forPathChecks(file: Path): AliasStore = AliasStore(file)
    }
}
