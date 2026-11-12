package geiler.addons.client.scoreboard

import java.util.UUID

/** One user-owned row in the single custom scoreboard layout. */
class ScoreboardLine() {
    enum class Type {
        OBJECTIVE_TITLE,
        SCORE_ENTRY,
        CUSTOM_TEXT,
        SPACER,
    }

    // Keep these backing-field names stable: Gson uses them as the persisted layout schema.
    private var id: String? = UUID.randomUUID().toString()
    private var type: Type? = Type.CUSTOM_TEXT
    private var text: String? = "New line"
    private var sourceIndex = 0
    private var color = 0xFFFFFFFF.toInt()
    private var bold = false
    private var enabled = true

    constructor(type: Type?, text: String?, sourceIndex: Int, color: Int, bold: Boolean) : this() {
        this.type = type ?: Type.CUSTOM_TEXT
        this.text = text ?: ""
        this.sourceIndex = sourceIndex
        this.color = color
        this.bold = bold
    }

    fun id(): String? = id

    fun type(): Type? = type

    fun setType(type: Type?) {
        this.type = type ?: Type.CUSTOM_TEXT
    }

    fun text(): String? = text

    fun setText(text: String?) {
        this.text = text ?: ""
    }

    fun sourceIndex(): Int = sourceIndex

    fun setSourceIndex(sourceIndex: Int) {
        this.sourceIndex = sourceIndex.coerceIn(0, 14)
    }

    fun color(): Int = color

    fun setColor(color: Int) {
        this.color = color
    }

    fun bold(): Boolean = bold

    fun setBold(bold: Boolean) {
        this.bold = bold
    }

    fun enabled(): Boolean = enabled

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun normalize() {
        if (id.isNullOrBlank() || id!!.length > 64) id = UUID.randomUUID().toString()
        if (type == null) type = Type.CUSTOM_TEXT
        if (text == null) text = ""
        if (text!!.length > 256) text = text!!.substring(0, 256)
        sourceIndex = sourceIndex.coerceIn(0, 14)
    }

    fun regenerateId() {
        id = UUID.randomUUID().toString()
    }

    companion object {
        @JvmStatic
        fun title(): ScoreboardLine = ScoreboardLine(Type.OBJECTIVE_TITLE, "", 0, 0xFF55FFFF.toInt(), true)

        @JvmStatic
        fun scoreEntry(sourceIndex: Int): ScoreboardLine =
            ScoreboardLine(Type.SCORE_ENTRY, "", sourceIndex, 0xFFFFFFFF.toInt(), false)

        @JvmStatic
        fun customText(text: String?): ScoreboardLine =
            ScoreboardLine(Type.CUSTOM_TEXT, text, 0, 0xFFFFFFFF.toInt(), false)
    }
}
