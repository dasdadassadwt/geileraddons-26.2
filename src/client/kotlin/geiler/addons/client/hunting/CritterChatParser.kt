package geiler.addons.client.hunting

import geiler.addons.client.location.SafariBiome
import java.util.regex.Pattern

/** What a Critter Safari chat line reported. */
class CritterEvent(
    val type: Type,
    val critter: Critter?,
    /** Who caught it; null for the local player's own catch. */
    val catcher: String?,
    val shards: Int,
    val sparkling: Boolean,
) {
    enum class Type { OWN_CATCH, SHARED_CATCH, ATTEMPT, FAILED }

    fun isCatch(): Boolean = type == Type.OWN_CATCH || type == Type.SHARED_CATCH
}

/**
 * Turns a Hypixel chat line into a [CritterEvent].
 *
 * Grammar (from Critter Mod's 3,200 observed events):
 * `CAPTURE! You caught a <C> and gained a|Nx <C> Shard!`,
 * `CAPTURE! You found Hideyho, and as a reward he gave you a|Nx Hideyho Shard!`,
 * `LOOT SHARE! You received a|Nx <C> Shard from <player> catching|finding ...!`.
 * It keys off the `CAPTURE!` / `LOOT SHARE!` prefix and resolves the species by roster lookup, so unseen
 * wordings still parse as long as the species name is present.
 */
object CritterChatParser {
    private val SHARD_AMOUNT = Pattern.compile("(\\d[\\d,]*)x\\s+\\S")
    private val CATCHER = Pattern.compile("from\\s+(\\w{1,16})\\s+(?:catching|finding)\\b")
    private val ATTEMPT = Pattern.compile("^You threw a Critter Capsule at the (.+)!$")
    private val FAILED = Pattern.compile("^The (.+?) (?:escaped your Critter Capsule|dodged your critter capsule)\\b")
    private val HOTSPOT = Pattern.compile("^HOTSPOT! Your Hunting Hotspot is the (.+?) Biome!$")
    private val COUNTER_SUFFIX = Pattern.compile("\\s*(?:\\(\\s*[x×]?\\s*\\d+\\s*\\)|\\[\\s*[x×]?\\s*\\d+\\s*])$")

    /**
     * A line a player typed. Anything a player can type, a player can quote: a partymate pasting their
     * hotspot line must not set the reader's hotspot. `[NPC]` and `[MOB]` are the game, so they are excluded.
     */
    private val PLAYER_SAID = Pattern.compile(
        "^(?:Party|Guild|Officer|Co-op|Team|Friend) >.*" +
            "|^(?:From|To) .*" +
            "|^(?:\\[(?!NPC]|MOB])[^]]+]\\s*)?\\w{1,16}: .*",
    )

    @JvmStatic
    fun playerSaid(line: String): Boolean = PLAYER_SAID.matcher(line).matches()

    /** Strips the trailing duplicate counter chat-compacting mods append (` (3)`, ` [x3]`). */
    @JvmStatic
    fun clean(plain: String): String = COUNTER_SUFFIX.matcher(plain.trim()).replaceAll("").trim()

    /** The event a cleaned line reports, or null when it is not a Safari event. */
    @JvmStatic
    fun parse(line: String): CritterEvent? {
        if (line.startsWith("CAPTURE!")) {
            val critter = CritterSpecies.findIn(line) ?: return null
            return CritterEvent(CritterEvent.Type.OWN_CATCH, critter, null, shardAmount(line), line.contains("SPARKLING"))
        }
        if (line.startsWith("LOOT SHARE!")) {
            val catcher = CATCHER.matcher(line)
            if (!catcher.find()) return null
            // Resolved from the part before the player's name, so a player called "Foxtrot" is not a catch.
            val critter = CritterSpecies.findIn(line.substring(0, catcher.start())) ?: return null
            return CritterEvent(
                CritterEvent.Type.SHARED_CATCH, critter, catcher.group(1), shardAmount(line), line.contains("SPARKLING"),
            )
        }
        ATTEMPT.matcher(line).let { m ->
            if (m.matches()) {
                val critter = CritterSpecies.byName(m.group(1)) ?: return null
                return CritterEvent(CritterEvent.Type.ATTEMPT, critter, null, 0, false)
            }
        }
        FAILED.matcher(line).let { m ->
            if (m.find()) {
                val critter = CritterSpecies.byName(m.group(1)) ?: return null
                return CritterEvent(CritterEvent.Type.FAILED, critter, null, 0, false)
            }
        }
        return null
    }

    /** The biome a `HOTSPOT!` line names, or null. Never call on a player-typed line. */
    @JvmStatic
    fun hotspot(line: String): SafariBiome? {
        val m = HOTSPOT.matcher(line)
        return if (m.matches()) BiomeStyle.fromName(m.group(1)) else null
    }

    private fun shardAmount(line: String): Int {
        val m = SHARD_AMOUNT.matcher(line)
        if (!m.find()) return 1
        return m.group(1).replace(",", "").toIntOrNull()?.coerceIn(1, 10_000) ?: 1
    }
}
