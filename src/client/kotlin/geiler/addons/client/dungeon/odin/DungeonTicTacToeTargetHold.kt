package geiler.addons.client.dungeon.odin

/**
 * Remembers the prediction made on O's turn so it stays visible while X is still thinking.
 *
 * The plan is valid only while the board is exactly "board at planning time + the suggested O". Any
 * other piece (an unexpected X reply, a reset) drops it, so a stale marker is never shown.
 */
class DungeonTicTacToeTargetHold {
    private class Plan(val before: Map<DungeonTicTacToeRules.Cell, Boolean>, val suggested: DungeonTicTacToeRules.Cell,
                       val prediction: DungeonTicTacToeRules.Prediction)

    private var plan: Plan? = null

    fun clear() {
        plan = null
    }

    /** Records the plan on O's turn; boards without a usable prediction leave nothing to hold. */
    fun plan(
        pieces: List<DungeonTicTacToeRules.Piece>,
        analysis: DungeonTicTacToeRules.Analysis,
        prediction: DungeonTicTacToeRules.Prediction,
    ) {
        val suggested = analysis.suggestedCell()
        val board = cells(pieces)
        plan = if (suggested != null && board != null && prediction.xReply() != null) Plan(board, suggested, prediction) else null
    }

    /** Returns the held prediction while X's reply is pending, or null (clearing the plan) otherwise. */
    fun hold(
        pieces: List<DungeonTicTacToeRules.Piece>,
        analysis: DungeonTicTacToeRules.Analysis,
    ): DungeonTicTacToeRules.Prediction? {
        val current = plan ?: return null
        val board = cells(pieces)
        val expected = HashMap(current.before)
        expected[current.suggested] = false
        if (analysis.status() != DungeonTicTacToeRules.Status.NOT_O_TURN || board == null || board != expected) {
            plan = null
            return null
        }
        return current.prediction
    }

    /** Cell → isX, or null if a piece lies outside the board or two pieces share a cell. */
    private fun cells(pieces: List<DungeonTicTacToeRules.Piece>): Map<DungeonTicTacToeRules.Cell, Boolean>? {
        val result = HashMap<DungeonTicTacToeRules.Cell, Boolean>()
        for (piece in pieces) {
            val cell = DungeonTicTacToeRules.cellAt(piece.x, piece.y, piece.z) ?: return null
            if (result.put(cell, piece.mapColor == DungeonTicTacToeRules.X_MAP_COLOR) != null) return null
        }
        return result
    }
}
