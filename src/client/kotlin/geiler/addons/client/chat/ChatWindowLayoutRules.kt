package geiler.addons.client.chat

/** Legacy panel dimensions retained only to migrate saved sizes into native chat settings. */
object ChatWindowLayoutRules {
    const val MIN_WIDTH = 300
    const val MIN_HEIGHT = 150
    const val MAX_WIDTH = 760
    const val MAX_HEIGHT = 560

    @JvmRecord
    data class Settings(
        val hudEnabled: Boolean,
        val focusedX: Int?,
        val focusedY: Int?,
        val width: Int?,
        val height: Int?,
    )
}
