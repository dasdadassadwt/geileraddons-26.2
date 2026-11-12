package geiler.addons.client.scoreboard

import java.util.ArrayList
import java.util.HashSet

/** The one client-wide, editable custom scoreboard layout. */
class ScoreboardLayout {
    enum class BackgroundStyle {
        NONE,
        SOLID,
        GLASS,
        ROUNDED,
        GLASS_ROUNDED,
    }

    // Keep these backing-field names stable: Gson uses them as the persisted layout schema.
    private var lines: MutableList<ScoreboardLine?>? = defaultLines()
    private var background: BackgroundStyle? = BackgroundStyle.GLASS_ROUNDED
    private var fontId: String? = "geileraddons:scoreboard/noto_sans"
    private var backgroundColor = 0xAA000000.toInt()
    private var borderColor = 0x66000000
    private var width = 0
    private var height = 0
    private var cornerRadius = 7
    private var borderWidth = 1
    private var padding = 5
    private var lineSpacing = 1
    private var scale = 1.0f

    @Suppress("UNCHECKED_CAST")
    fun lines(): MutableList<ScoreboardLine> = lines as MutableList<ScoreboardLine>

    fun setLines(lines: List<ScoreboardLine?>?) {
        this.lines = if (lines == null) ArrayList() else ArrayList(lines)
        normalize()
    }

    fun background(): BackgroundStyle? = background

    fun setBackground(background: BackgroundStyle?) {
        this.background = background ?: BackgroundStyle.GLASS
    }

    fun fontId(): String? = fontId

    fun setFontId(fontId: String?) {
        if (fontId.isNullOrBlank() || fontId.length > 128) return
        this.fontId = fontId
    }

    fun backgroundColor(): Int = backgroundColor

    fun setBackgroundColor(backgroundColor: Int) {
        this.backgroundColor = backgroundColor
    }

    fun borderColor(): Int = borderColor

    fun setBorderColor(borderColor: Int) {
        this.borderColor = borderColor
    }

    /** Zero means size to the current visible rows. */
    fun width(): Int = width

    fun setWidth(width: Int) {
        this.width = width.coerceIn(0, 600)
    }

    /** Zero means size to the current visible rows. */
    fun height(): Int = height

    fun setHeight(height: Int) {
        this.height = height.coerceIn(0, 600)
    }

    fun cornerRadius(): Int = cornerRadius

    fun setCornerRadius(cornerRadius: Int) {
        this.cornerRadius = cornerRadius.coerceIn(0, 64)
    }

    fun borderWidth(): Int = borderWidth

    fun setBorderWidth(borderWidth: Int) {
        this.borderWidth = borderWidth.coerceIn(0, 8)
    }

    fun padding(): Int = padding

    fun setPadding(padding: Int) {
        this.padding = padding.coerceIn(0, 32)
    }

    fun lineSpacing(): Int = lineSpacing

    fun setLineSpacing(lineSpacing: Int) {
        this.lineSpacing = lineSpacing.coerceIn(0, 16)
    }

    fun scale(): Float = scale

    fun setScale(scale: Float) {
        if (java.lang.Float.isFinite(scale)) this.scale = scale.coerceIn(0.5f, 2.5f)
    }

    fun normalize() {
        if (lines == null) lines = ArrayList()
        val currentLines = lines!!
        if (currentLines.size > 64) lines = ArrayList(currentLines.subList(0, 64))
        val normalizedLines = lines!!
        normalizedLines.removeAll { it == null }
        val ids = HashSet<String>()
        for (candidate in normalizedLines) {
            val line = candidate ?: continue
            line.normalize()
            val lineId = line.id()!!
            if (!ids.add(lineId)) {
                line.regenerateId()
                ids.add(line.id()!!)
            }
        }
        if (background == null) background = BackgroundStyle.GLASS_ROUNDED
        if (fontId.isNullOrBlank() || fontId!!.length > 128) fontId = "geileraddons:scoreboard/noto_sans"
        width = width.coerceIn(0, 600)
        height = height.coerceIn(0, 600)
        cornerRadius = cornerRadius.coerceIn(0, 64)
        borderWidth = borderWidth.coerceIn(0, 8)
        padding = padding.coerceIn(0, 32)
        lineSpacing = lineSpacing.coerceIn(0, 16)
        setScale(scale)
    }

    companion object {
        private fun defaultLines(): MutableList<ScoreboardLine?> {
            val defaults = ArrayList<ScoreboardLine?>()
            defaults.add(ScoreboardLine.title())
            for (i in 0 until 15) defaults.add(ScoreboardLine.scoreEntry(i))
            return defaults
        }
    }
}
