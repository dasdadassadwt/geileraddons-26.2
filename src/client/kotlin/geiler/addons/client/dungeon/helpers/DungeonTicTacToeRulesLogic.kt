package geiler.addons.client.dungeon.helpers

/** Pure board recognition, legal-turn validation, minimax, and room-transform logic. */
object DungeonTicTacToeRulesLogic {
    private const val X_MAP_COLOR = 114
    private const val O_MAP_COLOR = 33
    private const val BOARD_SIZE = 3
    private const val CELL_COUNT = BOARD_SIZE * BOARD_SIZE

    /** Accepts only the nine calibrated source-local frame coordinates used by the room board. */
    @JvmStatic
    fun cellAt(x: Int, y: Int, z: Int): DungeonTicTacToeRules.Cell? {
        if (x != 8 || y !in 70..72 || z !in 15..17) return null
        return DungeonTicTacToeRules.Cell(72 - y, 17 - z)
    }

    /** Returns the calibrated room-local frame coordinate for one board cell, or null if invalid. */
    @JvmStatic
    fun positionForCell(cell: DungeonTicTacToeRules.Cell?): DungeonTicTacToeRules.Position? {
        if (cell == null || !cell.inBounds()) return null
        return DungeonTicTacToeRules.Position(8, 72 - cell.row, 17 - cell.column)
    }

    /** Converts the calibrated cell coordinate through the same room rotation used by puzzle visuals. */
    @JvmStatic
    fun toWorld(
        roomPosition: DungeonTicTacToeRules.Position?,
        clayPosition: DungeonTicTacToeRules.Position?,
        rotation: DungeonRoomRotation.Rotation?,
    ): DungeonTicTacToeRules.Position? {
        if (roomPosition == null || clayPosition == null || rotation == null) return null
        val rotated = DungeonRoomRotationLogic.rotateAroundNorth(rotation, roomPosition.x, roomPosition.z)
        return DungeonTicTacToeRules.Position(clayPosition.x + rotated[0], roomPosition.y, clayPosition.z + rotated[1])
    }

    /** Inverse of [toWorld]; used to map observed frame positions into source-local cells. */
    @JvmStatic
    fun toRoom(
        worldPosition: DungeonTicTacToeRules.Position?,
        clayPosition: DungeonTicTacToeRules.Position?,
        rotation: DungeonRoomRotation.Rotation?,
    ): DungeonTicTacToeRules.Position? {
        if (worldPosition == null || clayPosition == null || rotation == null) return null
        val relative = DungeonRoomRotationLogic.rotateToNorth(
            rotation,
            worldPosition.x - clayPosition.x,
            worldPosition.z - clayPosition.z,
        )
        return DungeonTicTacToeRules.Position(relative[0], worldPosition.y, relative[1])
    }

    @JvmStatic
    fun worldPositionForCell(
        cell: DungeonTicTacToeRules.Cell?,
        clayPosition: DungeonTicTacToeRules.Position?,
        rotation: DungeonRoomRotation.Rotation?,
    ): DungeonTicTacToeRules.Position? {
        val roomPosition = positionForCell(cell)
        return if (roomPosition == null) null else toWorld(roomPosition, clayPosition, rotation)
    }

    /** Every occupied frame must be a unique, recognized board cell; empty squares need no frame. */
    @JvmStatic
    fun analyze(pieces: List<DungeonTicTacToeRules.Piece?>?): DungeonTicTacToeRules.Analysis {
        if (pieces == null) return rejected(DungeonTicTacToeRules.Rejection.UNAVAILABLE, 0, 0, 0)
        val board = CharArray(CELL_COUNT) { ' ' }
        var observed = 0
        var xCount = 0
        var oCount = 0
        for (piece in pieces) {
            observed++
            if (piece == null) return rejected(DungeonTicTacToeRules.Rejection.OUT_OF_RANGE, observed, xCount, oCount)
            val cell = cellAt(piece.x, piece.y, piece.z)
                ?: return rejected(DungeonTicTacToeRules.Rejection.OUT_OF_RANGE, observed, xCount, oCount)
            val index = cell.row * BOARD_SIZE + cell.column
            if (board[index] != ' ') {
                return rejected(DungeonTicTacToeRules.Rejection.DUPLICATE_SQUARE, observed, xCount, oCount)
            }
            val mark = markForColor(piece.mapColor)
            if (mark == '?') return rejected(DungeonTicTacToeRules.Rejection.UNKNOWN_MARK, observed, xCount, oCount)
            board[index] = mark
            if (mark == 'X') xCount++ else oCount++
        }

        if (xCount != oCount && xCount != oCount + 1) {
            return rejected(DungeonTicTacToeRules.Rejection.ILLEGAL_COUNTS, observed, xCount, oCount)
        }
        val xWins = hasWon(board, 'X')
        val oWins = hasWon(board, 'O')
        if (xWins && oWins) return rejected(DungeonTicTacToeRules.Rejection.BOTH_WINNERS, observed, xCount, oCount)
        if (xWins && xCount != oCount + 1 || oWins && xCount != oCount) {
            return rejected(DungeonTicTacToeRules.Rejection.ILLEGAL_WINNER_TURN, observed, xCount, oCount)
        }
        if (xWins || oWins || observed == CELL_COUNT) {
            return DungeonTicTacToeRules.Analysis(
                DungeonTicTacToeRules.Status.FINISHED,
                DungeonTicTacToeRules.Rejection.FINISHED,
                observed,
                xCount,
                oCount,
                DungeonTicTacToeRules.Turn.DONE,
                null,
            )
        }
        if (xCount != oCount + 1) {
            return DungeonTicTacToeRules.Analysis(
                DungeonTicTacToeRules.Status.NOT_O_TURN,
                DungeonTicTacToeRules.Rejection.NOT_O_TURN,
                observed,
                xCount,
                oCount,
                DungeonTicTacToeRules.Turn.X,
                null,
            )
        }

        val move = chooseMove(board)
        if (move < 0) {
            return DungeonTicTacToeRules.Analysis(
                DungeonTicTacToeRules.Status.FINISHED,
                DungeonTicTacToeRules.Rejection.FINISHED,
                observed,
                xCount,
                oCount,
                DungeonTicTacToeRules.Turn.DONE,
                null,
            )
        }
        return DungeonTicTacToeRules.Analysis(
            DungeonTicTacToeRules.Status.READY,
            DungeonTicTacToeRules.Rejection.NONE,
            observed,
            xCount,
            oCount,
            DungeonTicTacToeRules.Turn.O,
            DungeonTicTacToeRules.Cell(move / BOARD_SIZE, move % BOARD_SIZE),
        )
    }

    /** Predicts a unique minimax-optimal X reply after O's recommended move, then O's reply. */
    @JvmStatic
    fun predict(
        pieces: List<DungeonTicTacToeRules.Piece?>?,
        analysis: DungeonTicTacToeRules.Analysis?,
    ): DungeonTicTacToeRules.Prediction {
        if (analysis == null || analysis.status() != DungeonTicTacToeRules.Status.READY ||
            analysis.suggestedCell() == null
        ) {
            return DungeonTicTacToeRules.Prediction(DungeonTicTacToeRules.PredictionStatus.UNAVAILABLE, null, null)
        }
        val board = boardFor(pieces)
            ?: return DungeonTicTacToeRules.Prediction(DungeonTicTacToeRules.PredictionStatus.UNAVAILABLE, null, null)
        val suggested = analysis.suggestedCell()
            ?: return DungeonTicTacToeRules.Prediction(DungeonTicTacToeRules.PredictionStatus.UNAVAILABLE, null, null)
        board[suggested.row * BOARD_SIZE + suggested.column] = 'O'
        if (hasWon(board, 'O') || isFull(board)) {
            return DungeonTicTacToeRules.Prediction(DungeonTicTacToeRules.PredictionStatus.TERMINAL_AFTER_O, null, null)
        }

        var bestScore = Int.MAX_VALUE
        var bestMove = -1
        var bestMoveCount = 0
        for (index in 0 until CELL_COUNT) {
            if (board[index] != ' ') continue
            board[index] = 'X'
            val score = minimax(board, true, 2, Int.MIN_VALUE, Int.MAX_VALUE)
            board[index] = ' '
            if (score < bestScore) {
                bestScore = score
                bestMove = index
                bestMoveCount = 1
            } else if (score == bestScore) {
                bestMoveCount++
            }
        }
        if (bestMoveCount != 1) {
            return DungeonTicTacToeRules.Prediction(
                DungeonTicTacToeRules.PredictionStatus.AMBIGUOUS_X_REPLY,
                null,
                null,
            )
        }

        val xReply = DungeonTicTacToeRules.Cell(bestMove / BOARD_SIZE, bestMove % BOARD_SIZE)
        board[bestMove] = 'X'
        if (hasWon(board, 'X') || isFull(board)) {
            return DungeonTicTacToeRules.Prediction(DungeonTicTacToeRules.PredictionStatus.TERMINAL_AFTER_X, xReply, null)
        }
        val nextO = chooseMove(board)
        return if (nextO < 0) {
            DungeonTicTacToeRules.Prediction(DungeonTicTacToeRules.PredictionStatus.TERMINAL_AFTER_X, xReply, null)
        } else {
            DungeonTicTacToeRules.Prediction(
                DungeonTicTacToeRules.PredictionStatus.READY,
                xReply,
                DungeonTicTacToeRules.Cell(nextO / BOARD_SIZE, nextO % BOARD_SIZE),
            )
        }
    }

    private fun boardFor(pieces: List<DungeonTicTacToeRules.Piece?>?): CharArray? {
        if (pieces == null) return null
        val board = CharArray(CELL_COUNT) { ' ' }
        for (piece in pieces) {
            if (piece == null) return null
            val cell = cellAt(piece.x, piece.y, piece.z) ?: return null
            val index = cell.row * BOARD_SIZE + cell.column
            val mark = markForColor(piece.mapColor)
            if (board[index] != ' ' || mark == '?') return null
            board[index] = mark
        }
        return board
    }

    private fun rejected(
        reason: DungeonTicTacToeRules.Rejection,
        observed: Int,
        xCount: Int,
        oCount: Int,
    ): DungeonTicTacToeRules.Analysis = DungeonTicTacToeRules.Analysis(
        DungeonTicTacToeRules.Status.REJECTED,
        reason,
        observed,
        xCount,
        oCount,
        DungeonTicTacToeRules.Turn.UNKNOWN,
        null,
    )

    private fun markForColor(color: Int): Char = when (color) {
        X_MAP_COLOR -> 'X'
        O_MAP_COLOR -> 'O'
        else -> '?'
    }

    private fun chooseMove(board: CharArray): Int {
        var bestScore = Int.MIN_VALUE
        var bestMove = -1
        for (index in 0 until CELL_COUNT) {
            if (board[index] != ' ') continue
            board[index] = 'O'
            val score = minimax(board, false, 1, Int.MIN_VALUE, Int.MAX_VALUE)
            board[index] = ' '
            if (score > bestScore) {
                bestScore = score
                bestMove = index
            }
        }
        return bestMove
    }

    private fun minimax(board: CharArray, maximizeForO: Boolean, depth: Int, alpha: Int, beta: Int): Int {
        if (hasWon(board, 'O')) return 10 - depth
        if (hasWon(board, 'X')) return depth - 10
        if (isFull(board)) return 0
        var currentAlpha = alpha
        var currentBeta = beta
        if (maximizeForO) {
            var best = Int.MIN_VALUE
            for (index in 0 until CELL_COUNT) {
                if (board[index] != ' ') continue
                board[index] = 'O'
                best = maxOf(best, minimax(board, false, depth + 1, currentAlpha, currentBeta))
                board[index] = ' '
                currentAlpha = maxOf(currentAlpha, best)
                if (currentBeta <= currentAlpha) break
            }
            return best
        }
        var best = Int.MAX_VALUE
        for (index in 0 until CELL_COUNT) {
            if (board[index] != ' ') continue
            board[index] = 'X'
            best = minOf(best, minimax(board, true, depth + 1, currentAlpha, currentBeta))
            board[index] = ' '
            currentBeta = minOf(currentBeta, best)
            if (currentBeta <= currentAlpha) break
        }
        return best
    }

    private fun hasWon(board: CharArray, mark: Char): Boolean {
        for (row in 0 until BOARD_SIZE) {
            val start = row * BOARD_SIZE
            if (board[start] == mark && board[start + 1] == mark && board[start + 2] == mark) return true
        }
        for (column in 0 until BOARD_SIZE) {
            if (board[column] == mark && board[column + BOARD_SIZE] == mark &&
                board[column + BOARD_SIZE * 2] == mark
            ) return true
        }
        return board[0] == mark && board[4] == mark && board[8] == mark ||
            board[2] == mark && board[4] == mark && board[6] == mark
    }

    private fun isFull(board: CharArray): Boolean = board.none { it == ' ' }
}
