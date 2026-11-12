package geiler.addons.client.enchanting

/** Constructor validation and Java-compatible string normalization for the event record. */
object ChronomatronEventLogic {
    @JvmStatic
    fun board(slotId: Int, value: String?, highlighted: Boolean): ChronomatronEvent =
        ChronomatronEvent(ChronomatronEvent.Kind.BOARD, slotId, value, highlighted, "")

    @JvmStatic
    fun status(status: String?): ChronomatronEvent =
        ChronomatronEvent(ChronomatronEvent.Kind.STATUS, -1, null, false, status)

    @JvmStatic
    fun validate(kind: ChronomatronEvent.Kind?, slotId: Int) {
        if (kind == null) throw IllegalArgumentException("kind must not be null")
        if (kind == ChronomatronEvent.Kind.BOARD && slotId < 0) {
            throw IllegalArgumentException("board events require a non-negative slot id")
        }
    }

    @JvmStatic
    fun normalizeValue(value: String?): String? {
        val trimmed = value?.trim { it <= '\u0020' } ?: return null
        return trimmed.ifEmpty { null }
    }

    @JvmStatic
    fun normalizeStatus(status: String?): String = status?.trim { it <= '\u0020' } ?: ""
}
