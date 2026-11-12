package geiler.addons.client.utility

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale

/** Persistence and validation for the Kotlin Lobby Sniper store API. */
class LobbySniperStoreLogic private constructor(private val file: Path) {
    constructor() : this(
        FabricLoader.getInstance().configDir.resolve("geileraddons/lobby-sniper-targets.json"),
    )

    private val entries = LinkedHashMap<String, LobbySniperStore.Entry>()
    private var loaded = false
    private var legacyTextImported = false
    @Synchronized
    fun entries(): List<LobbySniperStore.Entry> {
        loadIfNeeded()
        return java.util.List.copyOf(entries.values.sortedBy { key(it.playerName()) })
    }

    @Synchronized
    fun find(playerName: String?): LobbySniperStore.Entry? {
        loadIfNeeded()
        return if (playerName == null) null else entries[key(playerName)]
    }

    @Synchronized
    fun put(playerName: String?, enabled: Boolean, title: String?): Boolean {
        loadIfNeeded()
        val cleanName = playerName?.trim { it <= ' ' } ?: ""
        val cleanTitle = sanitizeTitle(title) ?: return false
        if (!USERNAME.matcher(cleanName).matches()) return false
        val targetKey = key(cleanName)
        if (!entries.containsKey(targetKey) && entries.size >= LobbySniperStore.MAX_TARGETS) return false
        entries[targetKey] = LobbySniperStore.Entry(cleanName, enabled, cleanTitle)
        save()
        return true
    }

    @Synchronized
    fun remove(playerName: String?): Boolean {
        loadIfNeeded()
        if (playerName == null || entries.remove(key(playerName)) == null) return false
        save()
        return true
    }

    /** Imports the legacy comma/newline list once, keeping any entries already saved in the editor. */
    @Synchronized
    fun importLegacyTargets(text: String?) {
        loadIfNeeded()
        if (legacyTextImported) return
        legacyTextImported = true
        for (name in LobbyTargetWatchState.parseTargets(text)) {
            if (entries.size >= LobbySniperStore.MAX_TARGETS) break
            entries.putIfAbsent(key(name), LobbySniperStore.Entry(name, true, ""))
        }
        save()
    }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        val data = ClientJsonFile.readResult(file, LobbySniperStore.Data::class.java).value
        if (data == null) return
        legacyTextImported = data.legacyTextImported == true
        val targets = data.targets ?: return
        for (target in targets) {
            if (target == null) continue
            val rawName = target.playerName ?: continue
            val cleanName = rawName.trim { it <= ' ' }
            if (!USERNAME.matcher(cleanName).matches()) continue
            val title = sanitizeTitle(target.title) ?: continue
            entries.putIfAbsent(
                key(cleanName),
                LobbySniperStore.Entry(cleanName, target.enabled != false, title),
            )
            if (entries.size >= LobbySniperStore.MAX_TARGETS) break
        }
    }

    private fun save() {
        val data = LobbySniperStore.Data()
        data.legacyTextImported = legacyTextImported
        val targets = ArrayList<LobbySniperStore.EntryData?>(entries.size)
        for (entry in entries.values) {
            targets.add(LobbySniperStore.EntryData().also {
                it.playerName = entry.playerName()
                it.enabled = entry.enabled()
                it.title = entry.title()
            })
        }
        data.targets = targets
        ClientJsonFile.writeAsync(file, ClientJsonFile.GSON.toJson(data))
    }

    private fun sanitizeTitle(title: String?): String? {
        if (title == null) return ""
        val clean = title.replace('\r', ' ').replace('\n', ' ').trim { it <= ' ' }
        if (clean.length > LobbySniperStore.MAX_TITLE_LENGTH) return null
        for (character in clean) {
            if (Character.isISOControl(character)) return null
        }
        return clean
    }

    private fun key(name: String): String = name.trim { it <= ' ' }.lowercase(Locale.ROOT)

    companion object {
        private val USERNAME = ChatStringSemantics.MINECRAFT_USERNAME

        /** Isolated file seam for the Java check harness; production uses the no-argument constructor. */
        @JvmStatic
        @JvmName("forPathChecks")
        internal fun forPathChecks(file: Path): LobbySniperStoreLogic = LobbySniperStoreLogic(file)
    }
}
