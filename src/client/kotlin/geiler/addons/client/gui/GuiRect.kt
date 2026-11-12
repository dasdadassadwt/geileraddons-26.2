package geiler.addons.client.gui

/** The one rectangle type shared by every GUI screen (replaces eleven private copies). */
data class GuiRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(px: Int, py: Int): Boolean = px >= x && px < x + w && py >= y && py < y + h

    fun contains(px: Double, py: Double): Boolean = px >= x && px < x + w && py >= y && py < y + h

    fun intersection(other: GuiRect?): GuiRect? {
        if (other == null) return this
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val right = minOf(x + w, other.x + other.w)
        val bottom = minOf(y + h, other.y + other.h)
        return if (right <= left || bottom <= top) null else GuiRect(left, top, right - left, bottom - top)
    }
}
