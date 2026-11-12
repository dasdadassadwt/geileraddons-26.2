package geiler.addons.client.dungeon.odin

/** Exact-room gates for AUTO puzzle selection; unknown room names never activate a solver. */
object DungeonPuzzleAutoSelectionRules {
    /** Returns the matching solver id for the exact room names in the bundled reference catalog. */
    @JvmStatic
    fun selectedMode(roomName: String?, ignoredLegacyCreeperToggle: Boolean): String? =
        when (roomName) {
            "Water Board" -> "WATER_BOARD"
            "Teleport Maze" -> "TP_MAZE"
            "Ice Path" -> "ICE_PATH"
            "Ice Fill" -> "ICE_FILL"
            "Higher Blaze", "Lower Blaze" -> "BLAZE"
            "Creeper Beams" -> "CREEPER_BEAMS"
            "Three Weirdos" -> "THREE_WEIRDOS"
            "Quiz" -> "QUIZ"
            "Boulder" -> "BOULDER"
            "Tic Tac Toe" -> "TIC_TAC_TOE"
            else -> null
        }

    /** Uses the exact confirmed room plus the solver toggle, independent of a previously published HUD line. */
    @JvmStatic
    fun solverActiveForRoom(roomName: String?, solverId: String?, solverEnabled: Boolean): Boolean =
        solverEnabled && solverId != null && solverId == selectedMode(roomName, true)
}
