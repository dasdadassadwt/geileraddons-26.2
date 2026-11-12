package geiler.addons.client.utility

import java.util.ArrayList

/** Saved watch-list API used by Lobby Sniper and its local editor. */
class LobbySniperStore private constructor() {
    private val logic = LobbySniperStoreLogic()

    @Synchronized
    fun entries(): List<Entry> = logic.entries()

    @Synchronized
    fun find(playerName: String?): Entry? = logic.find(playerName)

    @Synchronized
    fun put(playerName: String?, enabled: Boolean, title: String?): Boolean =
        logic.put(playerName, enabled, title)

    @Synchronized
    fun remove(playerName: String?): Boolean = logic.remove(playerName)

    /** Imports the legacy comma/newline list once, keeping any entries already saved in the editor. */
    @Synchronized
    fun importLegacyTargets(text: String?) = logic.importLegacyTargets(text)

    companion object {
        @JvmField
        val INSTANCE = LobbySniperStore()

        const val MAX_TARGETS = 128
        const val MAX_TITLE_LENGTH = 96
    }

    /** Immutable entry value with the same Java accessor names as the former record. */
    data class Entry(
        private val name: String,
        private val targetEnabled: Boolean,
        private val customTitle: String,
    ) {
        fun playerName(): String = name

        fun enabled(): Boolean = targetEnabled

        fun title(): String = customTitle
    }

    /** Persisted Gson shape; keep the public field names stable for existing local data. */
    class Data {
        @JvmField
        var legacyTextImported: Boolean? = null

        @JvmField
        var targets: MutableList<EntryData?>? = ArrayList()
    }

    class EntryData {
        @JvmField
        var playerName: String? = null

        @JvmField
        var enabled: Boolean? = null

        @JvmField
        var title: String? = null
    }
}
