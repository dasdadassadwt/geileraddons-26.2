package geiler.addons.client.dungeon.helpers

import geiler.addons.client.tree.ChatText
import java.util.ArrayDeque
import java.util.HashSet
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Pure helpers for the Java observer's Ice Fill and Three Weirdos facades. */
object DungeonPuzzleObserverLogic {
    private val EXPLICIT_NPC_LINE = Pattern.compile("(?i)^(?:\\[npc\\]\\s*)?(.{1,64}?)\\s*:\\s*(.+)$")
    private val WEIRDOS_CORRECT_CLUES = listOf(
        Pattern.compile("The reward is not in my chest!"),
        Pattern.compile("At least one of them is lying, and the reward is not in .+'s chest.?"),
        Pattern.compile("My chest doesn't have the reward. We are all telling the truth.?"),
        Pattern.compile("My chest has the reward and I'm telling the truth!"),
        Pattern.compile("The reward isn't in any of our chests.?"),
        Pattern.compile("Both of them are telling the truth. Also, .+ has the reward in their chest.?"),
    )
    private val WEIRDOS_WRONG_CLUES = listOf(
        Pattern.compile("One of us is telling the truth!"),
        Pattern.compile("They are both telling the truth. The reward isn't in .+'s chest."),
        Pattern.compile("We are all telling the truth!"),
        Pattern.compile(".+ is telling the truth and the reward is in his chest."),
        Pattern.compile("My chest doesn't have the reward. At least one of the others is telling the truth!"),
        Pattern.compile("One of the others is lying."),
        Pattern.compile("They are both telling the truth, the reward is in .+'s chest."),
        Pattern.compile("They are both lying, the reward is in my chest!"),
        Pattern.compile("The reward is in my chest."),
        Pattern.compile("The reward is not in my chest. They are both lying."),
        Pattern.compile(".+ is telling the truth."),
        Pattern.compile("My chest has the reward."),
    )
    private val ICE_CELL_ORDER = compareBy<DungeonPuzzleLogic.Cell> { it.x }.thenBy { it.y }

    /** Validates and plans only a small, caller-observed Ice Fill footprint. */
    @JvmStatic
    fun planIceFill(
        observedCells: Set<DungeonPuzzleLogic.Cell?>?,
        playerCell: DungeonPuzzleLogic.Cell?,
        nodeBudget: Int,
    ): IceFillPlanResult {
        if (observedCells == null || observedCells.size < 2 || observedCells.size > 22 || playerCell == null ||
            !observedCells.contains(playerCell)
        ) {
            return IceFillPlanResult(
                emptyList(),
                "Observed ice footprint is missing, too large, or does not contain the player.",
            )
        }

        val connected = connectedIce(observedCells, playerCell)
        if (connected.size != observedCells.size) {
            return IceFillPlanResult(
                emptyList(),
                "Observed ice tiles form multiple components; start and goal are ambiguous.",
            )
        }

        val ends = observedCells.filterNotNull()
            .filter { iceDegree(it, observedCells) == 1 }
            .sortedWith(ICE_CELL_ORDER)
        if (ends.size != 2 || iceDegree(playerCell, observedCells) != 1) {
            return IceFillPlanResult(
                emptyList(),
                "Observed ice layout is not a single unambiguous endpoint-to-endpoint path.",
            )
        }

        val goal = if (ends[0] == playerCell) ends[1] else ends[0]
        val orderedCells = observedCells.filterNotNull().sortedWith(ICE_CELL_ORDER)
        val route = DungeonPuzzleLogic.solveIceFill(orderedCells, playerCell, goal, nodeBudget)
        return if (route.isEmpty()) {
            IceFillPlanResult(
                emptyList(),
                "The observed tiles have no complete path within the planner's search budget.",
            )
        } else {
            IceFillPlanResult(route, "")
        }
    }

    /** Extracts the explicit speaker used to resolve an observed NPC entity before clue parsing. */
    @JvmStatic
    fun explicitNpcSpeaker(rawMessage: String?): String? {
        val matcher = explicitNpcMatcher(rawMessage) ?: return null
        return matcher.group(1).trim()
    }

    /** Accepts only a complete supported clue line attributed to a caller-confirmed NPC name. */
    @JvmStatic
    fun parseExplicitNpcClue(
        rawMessage: String?,
        confirmedNpcNames: Set<String?>?,
    ): ParsedNpcClue? {
        if (rawMessage == null || confirmedNpcNames == null || confirmedNpcNames.isEmpty()) return null
        val matcher = explicitNpcMatcher(rawMessage) ?: return null
        val speaker = matcher.group(1).trim()
        val statement = matcher.group(2).trim()
        if (speaker.isBlank() || statement.isBlank() || parseWeirdosClaim(statement) == null) return null
        val canonical = confirmedNpcNames.firstOrNull { name -> normalizeNpcName(name) == normalizeNpcName(speaker) }
            ?: return null
        return ParsedNpcClue(canonical, statement)
    }

    /** Returns true/false for a recognized clue and null when its whole statement is unsupported. */
    @JvmStatic
    fun parseWeirdosClaim(statement: String?): Boolean? {
        if (statement == null) return null
        val text = ChatText.plain(statement).trim()
        if (WEIRDOS_CORRECT_CLUES.any { pattern -> pattern.matcher(text).matches() }) return true
        if (WEIRDOS_WRONG_CLUES.any { pattern -> pattern.matcher(text).matches() }) return false
        return null
    }

    private fun explicitNpcMatcher(rawMessage: String?): Matcher? {
        if (rawMessage == null) return null
        val matcher = EXPLICIT_NPC_LINE.matcher(ChatText.plain(rawMessage).trim())
        return if (matcher.matches()) matcher else null
    }

    private fun normalizeNpcName(value: String?): String =
        ChatText.plain(value ?: "").trim().lowercase(Locale.ROOT)

    private fun connectedIce(
        cells: Set<DungeonPuzzleLogic.Cell?>,
        start: DungeonPuzzleLogic.Cell,
    ): Set<DungeonPuzzleLogic.Cell> {
        val visited = HashSet<DungeonPuzzleLogic.Cell>()
        val queue = ArrayDeque<DungeonPuzzleLogic.Cell>()
        queue.add(start)
        visited.add(start)
        while (!queue.isEmpty()) {
            val at = queue.removeFirst()
            for (next in neighbors(at)) {
                if (cells.contains(next) && visited.add(next)) queue.addLast(next)
            }
        }
        return visited
    }

    private fun iceDegree(
        cell: DungeonPuzzleLogic.Cell,
        cells: Set<DungeonPuzzleLogic.Cell?>,
    ): Int = neighbors(cell).count { neighbor -> cells.contains(neighbor) }

    private fun neighbors(cell: DungeonPuzzleLogic.Cell): List<DungeonPuzzleLogic.Cell> = listOf(
        DungeonPuzzleLogic.Cell(cell.x, cell.y - 1),
        DungeonPuzzleLogic.Cell(cell.x + 1, cell.y),
        DungeonPuzzleLogic.Cell(cell.x, cell.y + 1),
        DungeonPuzzleLogic.Cell(cell.x - 1, cell.y),
    )

    @JvmRecord
    data class IceFillPlanResult(val route: List<DungeonPuzzleLogic.Cell>, val reason: String)

    @JvmRecord
    data class ParsedNpcClue(val speaker: String, val statement: String)
}
