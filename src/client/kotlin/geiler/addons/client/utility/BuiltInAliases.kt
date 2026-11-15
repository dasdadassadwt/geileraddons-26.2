package geiler.addons.client.utility

import java.util.Locale

/**
 * Always-available aliases for Hypixel dungeon queues: f1-f7 and m1-m7 join a Catacombs or Master Catacombs floor,
 * t1-t5 join a Kuudra tier. They are not saved to the alias list, so they cannot be deleted; the Aliases module
 * switch turns them off.
 */
object BuiltInAliases {
    private val FLOOR_WORDS = listOf("ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN")
    private val KUUDRA_TIERS = listOf("NORMAL", "HOT", "BURNING", "FIERY", "INFERNAL")

    /** shortcut: names follow the Hypixel wiki pattern (CATACOMBS_FLOOR_ONE, KUUDRA_INFERNAL); verify KUUDRA_NORMAL in game. */
    private val COMMANDS: Map<String, String> = buildMap {
        FLOOR_WORDS.forEachIndexed { index, word ->
            put("f${index + 1}", "joininstance CATACOMBS_FLOOR_$word")
            put("m${index + 1}", "joininstance MASTER_CATACOMBS_FLOOR_$word")
        }
        KUUDRA_TIERS.forEachIndexed { index, tier -> put("t${index + 1}", "joininstance KUUDRA_$tier") }
    }

    /** Every built-in alias name, lowercase. */
    @JvmStatic
    fun names(): Set<String> = COMMANDS.keys

    @JvmStatic
    fun isBuiltInName(alias: String?): Boolean = key(alias)?.let { COMMANDS.containsKey(it) } == true

    /** The command for a built-in alias that equals the whole input (ignoring case, spaces and a leading slash). */
    @JvmStatic
    fun command(input: String?): String? = key(input)?.let { COMMANDS[it] }

    private fun key(input: String?): String? =
        input?.trim()?.removePrefix("/")?.lowercase(Locale.ROOT)
}
