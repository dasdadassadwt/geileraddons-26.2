package geiler.addons.client.enchanting

/** Constructor rules and value interpretation behind the Java record's stable API. */
object ExperimentCellLogic {
    @JvmStatic
    fun token(slotId: Int, value: String?, highlighted: Boolean): ExperimentCell =
        ExperimentCell(slotId, value, -1, true, highlighted, false)

    @JvmStatic
    fun number(slotId: Int, number: Int): ExperimentCell =
        ExperimentCell(slotId, number.toString(), number, true, false, false)

    @JvmStatic
    fun validateSlotId(slotId: Int) {
        if (slotId < 0) throw IllegalArgumentException("slotId must be non-negative")
    }

    @JvmStatic
    fun normalizeValue(value: String?): String? {
        val trimmed = value?.trim { it <= '\u0020' } ?: return null
        return trimmed.ifEmpty { null }
    }

    @JvmStatic
    fun hasValue(value: String?): Boolean = value != null && value.any { !Character.isWhitespace(it) }

    @JvmStatic
    fun numericValue(value: String?, number: Int): Int {
        if (number > 0) return number
        val text = value ?: return -1
        if (!hasValue(text)) return -1
        return try {
            Integer.parseInt(text)
        } catch (_: NumberFormatException) {
            -1
        }
    }
}
