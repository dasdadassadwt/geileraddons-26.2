package geiler.addons.client.carnival

import java.util.Locale

/**
 * Carnival chat and lore lines, from SkyHanni's Carnival RepoPattern defaults (LGPL-2.1). SkyHanni matches colour
 * codes; this mod receives chat as plain trimmed text, so every pattern here is the plain form of SkyHanni's.
 */
object CarnivalPatterns {
    val FRUIT_START = Regex("^\\[NPC] Carnival Pirateman: Good luck, matey!$")
    // SkyHanni: " {31}Fruit Digging"; the summary title arrives trimmed here.
    val FRUIT_END = Regex("^Fruit Digging$")
    val TREASURE = Regex("^TREASURE! There is an? (?<fruit>.*) nearby\\.$")
    val NO_FRUITS_NEARBY = Regex("^(?:TREASURE|ANCHOR)! There are no fruits nearby!$")
    val MINES = Regex("^MINES! There (?:is|are) (?<bombs>\\d+) bombs? hidden nearby\\.$")
    val REVEAL = Regex("^(?<name>[A-Za-z ]+)(?: \\(\\+\\d+\\))?$")

    val SHOOTOUT_START = Regex("^\\[NPC] Carnival Cowboy: Good luck, pal!$")
    val SHOOTOUT_END = Regex("^Zombie Shootout$")

    // SkyHanni matches the full dialogue block; only its first row survives plain-text trimming.
    val SELECT_OPTION = Regex("^Select an option:.*", RegexOption.DOT_MATCHES_ALL)

    val TICKETS_CLAIMED = Regex("^You claimed Carnival Ticket x25!$")
    val TICKETS_ALREADY = Regex(
        "^\\[NPC] Carnival Leader: You've already claimed your Carnival Tickets for today, but I'm happy to answer any questions you might have\\.$",
    )

    val TOKENS = Regex(".*Your Tokens: (?<tokens>[\\d,]*)")
    val UPGRADE_NAME = Regex("^(?<upgrade>.*) (?<tier>[IVXLCDM]+)$")
    const val UPGRADE_MAXED = "UNLOCKED"
    const val GOALS_INVENTORY = "Carnival Goals"
    const val GOAL_COMPLETE = "COMPLETE"

    /** Which NPC dialogue a Carnival game master opens; SkyHanni answers `r_2_1` ("Sure thing, partner!"). */
    @JvmStatic
    fun npcId(name: String): String? = when (name.trim()) {
        "Carnival Cowboy" -> "carnival_cowboy"
        "Carnival Fisherman" -> "carnival_fisherman"
        "Carnival Pirateman" -> "carnival_pirateman"
        else -> null
    }

    /** Roman numeral to number, for upgrade tiers; 0 when it is not one. */
    @JvmStatic
    fun roman(text: String): Int {
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var previous = 0
        for (char in text.uppercase(Locale.ROOT).reversed()) {
            val value = values[char] ?: return 0
            total += if (value < previous) -value else value
            previous = maxOf(previous, value)
        }
        return total
    }
}

/** Carnival daily goals, from SkyHanni's `CarnivalGoal` in plain text. */
enum class CarnivalGoal(val game: Game, val loreLine: String, val chatLine: String, val display: String) {
    FRUIT_DIGGING_PLAY(Game.FRUIT_DIGGING, "Play 3 games of Fruit Digging.", "Play 3 games of Fruit Digging.", "Play 3 games"),
    FRUIT_DIGGING_SCORE(Game.FRUIT_DIGGING, "Reach 3,000 score in a single game", "Reach 3,000 score in a single game of Fruit Digging.", "Reach 3,000 score"),
    DIG_APPLE(Game.FRUIT_DIGGING, "Dig up 3 Apples in a single game of", "Dig up 3 Apples in a single game of Fruit Digging.", "Dig up 3 Apples"),
    UNIQUE_FRUIT(Game.FRUIT_DIGGING, "Dig up 5 unique Fruits in a single", "Dig up 5 unique Fruits in a single game of Fruit Digging.", "Dig up 5 unique Fruits"),
    DRAGONFRUIT(Game.FRUIT_DIGGING, "Dig up a Dragonfruit.", "Dig up a Dragonfruit.", "Dig up a Dragonfruit"),
    CATCH_A_FISH_PLAY(Game.CATCH_A_FISH, "Play 3 games of Catch a Fish.", "Play 3 games of Catch a Fish.", "Play 3 games"),
    CATCH_A_FISH_SCORE(Game.CATCH_A_FISH, "Reach 3,000 score in a single game", "Reach 3,000 score in a single game of Catch a Fish.", "Reach 3,000 score"),
    CATCH_FISH(Game.CATCH_A_FISH, "Catch 30 Fish in a single game of", "Catch 30 Fish in a single game of Catch a Fish.", "Catch 30 Fish"),
    CATCH_YELLOW_FISH(Game.CATCH_A_FISH, "Catch 5 Yellow Fish in a single game", "Catch 5 Yellow Fish in a single game of Catch a Fish.", "Catch 5 Yellow Fish"),
    CATCH_STREAK(Game.CATCH_A_FISH, "Reach a 5 Catch Streak in a single", "Reach a 5 Catch Streak in a single game of Catch a Fish.", "Reach a 5 Catch Streak"),
    ZOMBIE_SHOOTOUT_PLAY(Game.ZOMBIE_SHOOTOUT, "Play 3 games of Zombie Shootout.", "Play 3 games of Zombie Shootout.", "Play 3 games"),
    ZOMBIE_SHOOTOUT_SCORE(Game.ZOMBIE_SHOOTOUT, "Reach 3,000 score in a single game", "Reach 3,000 score in a single game of Zombie Shootout.", "Reach 3,000 score"),
    SHOOT_ZOMBIE(Game.ZOMBIE_SHOOTOUT, "Shoot 60 Zombies in a single game of", "Shoot 60 Zombies in a single game of Zombie Shootout.", "Shoot 60 Zombies"),
    SHOOT_DIAMOND_ZOMBIE(Game.ZOMBIE_SHOOTOUT, "Shoot 5 Diamond Zombies in a single", "Shoot 5 Diamond Zombies in a single game of Zombie Shootout.", "Shoot 5 Diamond Zombies"),
    SHOOT_LAMPS(Game.ZOMBIE_SHOOTOUT, "Shoot 5 Redstone Lamps in a single", "Shoot 5 Redstone Lamps in a single game of Zombie Shootout.", "Shoot 5 Redstone Lamps"),
    ;

    enum class Game(val title: String) { FRUIT_DIGGING("Fruit Digging"), CATCH_A_FISH("Catch a Fish"), ZOMBIE_SHOOTOUT("Zombie Shootout") }

    companion object {
        /** The goal a completion chat line announces (with or without SkyHanni's " - " list prefix). */
        @JvmStatic
        fun fromChat(message: String): CarnivalGoal? {
            val text = message.trim().removePrefix("- ").trim()
            return entries.firstOrNull { it.chatLine == text }
        }

        /** The goal a Carnival Goals item describes: its game and the first lore line. Several games share wording. */
        @JvmStatic
        fun fromLore(game: Game, firstLoreLine: String): CarnivalGoal? =
            entries.firstOrNull { it.game == game && firstLoreLine.trim() == it.loreLine }
    }
}
