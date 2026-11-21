package geiler.addons.client.dungeon.helpers

/**
 * Keeps the best O move on screen until a new best move replaces it. The board scan briefly loses the
 * suggestion while X thinks, item frames update, or chunks stream; clearing then made the marker flash.
 */
class DungeonTicTacToeSuggestionHold<T : Any> {
    private var held: T? = null

    fun current(): T? = held

    /** A fresh READY scan: the new best move replaces whatever was held. */
    fun suggest(target: T) {
        held = target
    }

    /** The board scan could not produce a suggestion (waiting for X, rejected frame state, unloaded chunk): keep the old one. */
    fun scanWithoutSuggestion() = Unit

    /** The game ended or the room was left: nothing may be shown any more. */
    fun clear() {
        held = null
    }
}
