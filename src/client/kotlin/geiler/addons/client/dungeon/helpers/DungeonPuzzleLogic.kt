package geiler.addons.client.dungeon.helpers

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Small data-driven puzzle algorithms. Callers supply observations; this class ships no room maps. */
object DungeonPuzzleLogic {
    private val BLAZE_HP = Pattern.compile("(?i)^\\[Lv\\d+]\\s+\\S+\\s+Blaze\\s+[\\d,]+/([\\d,]+)❤$")
    private val COLOR_CODE = Regex("(?i)[§&][0-9A-FK-OR]")

    /** Lower Blaze rooms are ordered high-to-low; Higher Blaze rooms are ordered low-to-high. */
    @JvmStatic
    fun orderBlazes(observed: List<BlazeTarget?>?, lowerBlazeRoom: Boolean): List<BlazeTarget> {
        if (observed == null || observed.isEmpty()) return java.util.List.of()
        val byHealthAndId = Comparator<BlazeTarget> { first, second ->
            val healthOrder = if (lowerBlazeRoom) {
                second.health.compareTo(first.health)
            } else {
                first.health.compareTo(second.health)
            }
            if (healthOrder != 0) healthOrder
            else (first.stableId ?: "").compareTo(second.stableId ?: "")
        }
        val ordered = observed.filterNotNull().filter { it.health >= 0 }.sortedWith(byHealthAndId)
        return java.util.List.copyOf(ordered)
    }

    @JvmStatic
    fun parseBlazeHealth(formattedName: String?): Int? {
        if (formattedName == null) return null
        val plain = formattedName.replace(COLOR_CODE, "").trim()
        val matcher: Matcher = BLAZE_HP.matcher(plain)
        if (!matcher.matches()) return null
        return matcher.group(1).replace(",", "").toIntOrNull()
    }

    /** Returns the least-click subset of levers that reaches a caller-provided target state. */
    @JvmStatic
    fun solveLeverSubset(leverCount: Int, initialMask: Int, goal: StateGoal?): Int {
        if (leverCount < 0 || leverCount > 20 || goal == null) return -1
        val stateCount = 1 shl leverCount
        val mask = initialMask and (stateCount - 1)
        var best = -1
        var bestClicks = Int.MAX_VALUE
        for (subset in 0 until stateCount) {
            val state = mask xor subset
            val clickCount = Integer.bitCount(subset)
            if (clickCount >= bestClicks || !goal.accepts(state)) continue
            best = subset
            bestClicks = clickCount
        }
        return best
    }

    /** BFS path for an observed walkable grid; cells and obstacles are supplied from loaded blocks. */
    @JvmStatic
    fun shortestPath(width: Int, height: Int, blocked: Set<Cell?>?, start: Cell?, goal: Cell?): List<Cell> {
        if (width <= 0 || height <= 0 || width.toLong() * height.toLong() > 1_000_000L) {
            return java.util.List.of()
        }
        val startCell = start ?: return java.util.List.of()
        val goalCell = goal ?: return java.util.List.of()
        if (!inside(startCell, width, height) || !inside(goalCell, width, height)) return java.util.List.of()
        val walls = blocked ?: emptySet()
        if (walls.contains(startCell) || walls.contains(goalCell)) return java.util.List.of()
        val queue = ArrayDeque<Cell>()
        val previous = HashMap<Cell, Cell?>()
        queue.add(startCell)
        previous[startCell] = null
        val delta = arrayOf(
            intArrayOf(0, -1),
            intArrayOf(1, 0),
            intArrayOf(0, 1),
            intArrayOf(-1, 0),
        )
        while (!queue.isEmpty()) {
            val at = queue.removeFirst()
            if (at == goalCell) return unwind(previous, goalCell)
            for (step in delta) {
                val next = Cell(at.x + step[0], at.y + step[1])
                if (inside(next, width, height) && !walls.contains(next) && !previous.containsKey(next)) {
                    previous[next] = at
                    queue.addLast(next)
                }
            }
        }
        return java.util.List.of()
    }

    /** Selects a viable unvisited portal in the same caller-supplied group as the current one. */
    @JvmStatic
    fun choosePortal(
        portals: List<Portal?>?,
        currentId: Int,
        visited: Set<Int>?,
        viable: Set<Int>?,
        playerYaw: Float,
    ): Portal? {
        if (portals == null || portals.isEmpty()) return null
        val seen = visited ?: emptySet()
        val candidatesByRay = viable ?: emptySet()
        val current = portals.firstOrNull { it!!.id == currentId } ?: return null
        val candidates = portals.filter { portal ->
            portal != null && portal.group == current.group &&
                portal.id != currentId && !seen.contains(portal.id)
        }.filterNotNull()
        val comparator = compareBy<Portal> { !candidatesByRay.contains(it.id) }
            .thenBy {
                val targetYaw =
                    (Math.toDegrees(
                        Math.atan2(it.z - current.z, it.x - current.x),
                    ) - 90.0).toFloat()
                yawDifference(playerYaw, targetYaw)
            }
            .thenBy { it.id }
        return candidates.minWithOrNull(comparator)
    }

    /** Returns a shortest lever sequence for a small, observed binary board. */
    @JvmStatic
    fun solveToggleBoard(
        initialState: Int,
        targetState: Int,
        leverEffects: List<Int?>?,
        maximumBits: Int,
    ): List<Int> = DungeonWaterBoardSolver.standard(initialState, targetState, leverEffects, maximumBits)

    /**
     * Finds a complete adjacent-cell route through a caller-observed Ice Fill board. The board is
     * intentionally capped at 22 cells so malformed inputs cannot trigger unbounded search.
     */
    @JvmStatic
    fun solveIceFill(
        walkableCells: List<Cell?>?,
        start: Cell?,
        goal: Cell?,
        nodeBudget: Int,
    ): List<Cell> {
        if (walkableCells == null || walkableCells.isEmpty() || walkableCells.size > 22 ||
            walkableCells.any { it == null } || start == null || goal == null
        ) {
            return java.util.List.of()
        }
        val cells = walkableCells.filterNotNull().distinct()
        if (cells.size != walkableCells.size || !cells.contains(start) || !cells.contains(goal)) {
            return java.util.List.of()
        }
        val indices = HashMap<Cell, Int>()
        for (i in cells.indices) indices[cells[i]] = i
        val startIndex = indices[start] ?: return java.util.List.of()
        val goalIndex = indices[goal] ?: return java.util.List.of()
        val route = ArrayList<Cell>(cells.size)
        val used = BooleanArray(cells.size)
        val remaining = intArrayOf(maxOf(1, minOf(5_000_000, nodeBudget)))
        return if (iceFillDfs(cells, indices, startIndex, goalIndex, used, route, remaining)) {
            java.util.List.copyOf(route)
        } else {
            java.util.List.of()
        }
    }

    /** Sokoban-style route for one observed boulder on a caller-supplied walkable grid. */
    @JvmStatic
    fun solveBoulder(
        walkable: Set<Cell?>?,
        player: Cell?,
        boulder: Cell?,
        goal: Cell?,
    ): List<PushStep> {
        if (walkable == null || walkable.isEmpty() || walkable.size > 256 ||
            player == null || boulder == null || goal == null || player == boulder ||
            !walkable.contains(player) || !walkable.contains(boulder) || !walkable.contains(goal)
        ) {
            return java.util.List.of()
        }
        val start = BoulderState(player, boulder)
        val queue = ArrayDeque<BoulderState>()
        val previous = HashMap<BoulderState, PreviousPush?>()
        queue.add(start)
        previous[start] = null
        val delta = arrayOf(
            intArrayOf(0, -1),
            intArrayOf(1, 0),
            intArrayOf(0, 1),
            intArrayOf(-1, 0),
        )
        while (!queue.isEmpty()) {
            val state = queue.removeFirst()
            val statePlayer = state.player!!
            val stateBoulder = state.boulder!!
            if (stateBoulder == goal) return unwindBoulder(previous, state)
            for (step in delta) {
                val nextPlayer = Cell(statePlayer.x + step[0], statePlayer.y + step[1])
                if (!walkable.contains(nextPlayer)) continue
                var nextBoulder: Cell? = stateBoulder
                val pushed = nextPlayer == stateBoulder
                if (pushed) {
                    nextBoulder = Cell(stateBoulder.x + step[0], stateBoulder.y + step[1])
                    if (!walkable.contains(nextBoulder)) continue
                }
                val next = BoulderState(nextPlayer, nextBoulder)
                if (previous.containsKey(next)) continue
                previous[next] = PreviousPush(state, if (pushed) nextBoulder else null)
                queue.addLast(next)
            }
        }
        return java.util.List.of()
    }

    /** Minimum-cost perfect pairing for a small even set of beam endpoints. No endpoints are invented. */
    @JvmStatic
    fun pairEndpoints(endpointIds: List<Int?>?, cost: PairCost?): List<Pair> {
        if (endpointIds == null || cost == null || endpointIds.size < 2 || endpointIds.size > 18 ||
            endpointIds.size % 2 != 0 || endpointIds.any { it == null } ||
            endpointIds.distinct().size != endpointIds.size
        ) {
            return java.util.List.of()
        }
        val ids = endpointIds.filterNotNull()
        val count = ids.size
        val pairChoice = IntArray(1 shl count)
        Arrays.fill(pairChoice, -1)
        val memo = DoubleArray(1 shl count) { Double.NaN }
        val best = pairingCost(ids, cost, 0, memo, pairChoice)
        if (!best.isFinite()) return java.util.List.of()
        val pairs = ArrayList<Pair>(count / 2)
        var mask = 0
        while (Integer.bitCount(mask) < count) {
            val first = Integer.numberOfTrailingZeros(mask.inv() and ((1 shl count) - 1))
            val second = pairChoice[mask]
            if (second < 0) return java.util.List.of()
            pairs.add(Pair(ids[first], ids[second]))
            mask = mask or (1 shl first) or (1 shl second)
        }
        return java.util.List.copyOf(pairs)
    }

    private fun pairingCost(ids: List<Int>, cost: PairCost, mask: Int, memo: DoubleArray, choices: IntArray): Double {
        val full = (1 shl ids.size) - 1
        if (mask == full) return 0.0
        if (!memo[mask].isNaN()) return memo[mask]
        val first = Integer.numberOfTrailingZeros(mask.inv() and full)
        var best = Double.POSITIVE_INFINITY
        var bestSecond = -1
        for (second in first + 1 until ids.size) {
            if ((mask and (1 shl second)) != 0) continue
            val edge = cost.cost(ids[first], ids[second])
            if (!edge.isFinite() || edge < 0.0) continue
            val rest = pairingCost(ids, cost, mask or (1 shl first) or (1 shl second), memo, choices)
            if (edge + rest < best) {
                best = edge + rest
                bestSecond = second
            }
        }
        memo[mask] = best
        choices[mask] = bestSecond
        return best
    }

    private fun inside(cell: Cell?, width: Int, height: Int): Boolean =
        cell != null && cell.x >= 0 && cell.y >= 0 && cell.x < width && cell.y < height

    private fun unwind(previous: Map<Cell, Cell?>, goal: Cell): List<Cell> {
        val result = ArrayList<Cell>()
        var at: Cell? = goal
        while (at != null) {
            result.add(at)
            at = previous[at]
        }
        result.reverse()
        return java.util.List.copyOf(result)
    }

    private fun iceFillDfs(
        cells: List<Cell>,
        indices: Map<Cell, Int>,
        at: Int,
        goal: Int,
        used: BooleanArray,
        route: MutableList<Cell>,
        budget: IntArray,
    ): Boolean {
        budget[0]--
        if (budget[0] < 0) return false
        if (used[at]) return false
        used[at] = true
        route.add(cells[at])
        if (route.size == cells.size) {
            if (at == goal) return true
        } else if (at != goal) {
            val cell = cells[at]
            val neighbors = listOf(
                Cell(cell.x, cell.y - 1),
                Cell(cell.x + 1, cell.y),
                Cell(cell.x, cell.y + 1),
                Cell(cell.x - 1, cell.y),
            )
            for (next in neighbors) {
                val index = indices[next]
                if (index != null && !used[index] &&
                    iceFillDfs(cells, indices, index, goal, used, route, budget)
                ) {
                    return true
                }
            }
        }
        used[at] = false
        route.removeAt(route.lastIndex)
        return false
    }

    private fun unwindBoulder(previous: Map<BoulderState, PreviousPush?>, goal: BoulderState): List<PushStep> {
        val reverse = ArrayList<PushStep>()
        var state = goal
        while (true) {
            val step = previous[state] ?: break
            val prior = step.previous!!
            val afterPush = step.boulderAfterPush
            if (afterPush != null) {
                reverse.add(PushStep(prior.player, prior.boulder, afterPush))
            }
            state = prior
        }
        reverse.reverse()
        return java.util.List.copyOf(reverse)
    }

    private fun yawDifference(from: Float, to: Float): Double {
        var difference = (to - from) % 360.0f
        if (difference >= 180.0f) difference -= 360.0f
        if (difference < -180.0f) difference += 360.0f
        return kotlin.math.abs(difference).toDouble()
    }

    fun interface StateGoal {
        fun accepts(state: Int): Boolean
    }

    fun interface PairCost {
        fun cost(firstEndpointId: Int, secondEndpointId: Int): Double
    }

    @JvmRecord
    data class BlazeTarget(val stableId: String?, val health: Int)

    @JvmRecord
    data class Cell(val x: Int, val y: Int)

    @JvmRecord
    data class Portal(val id: Int, val group: Int, val x: Double, val z: Double)

    @JvmRecord
    data class Pair(val firstEndpointId: Int, val secondEndpointId: Int)

    @JvmRecord
    data class PushStep(val playerBeforePush: Cell?, val boulderBeforePush: Cell?, val boulderAfterPush: Cell?)

    @JvmRecord
    private data class BoulderState(val player: Cell?, val boulder: Cell?)

    @JvmRecord
    private data class PreviousPush(val previous: BoulderState?, val boulderAfterPush: Cell?)
}
