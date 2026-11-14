package geiler.addons.client.macros.area

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.util.Locale

/** What a painted cell is. */
enum class Paint(val code: Char, val label: String) {
    /** Walkable. */
    ALLOWED('A', "allowed"),

    /** Walkable, and cheaper to walk than the rest: the route leans toward it. */
    PREFERRED('P', "preferred"),

    /** Not walkable, but the rest of the ground is not affected. */
    FORBIDDEN('F', "forbidden"),

    /** Not walkable at any height of its column: a barrier. */
    WALL('W', "wall"),

    /** Walkable, and where the macro waits when there is nothing to hit: it walks to one of these cells and loiters. */
    REST('R', "rest"),
    ;

    companion object {
        @JvmStatic
        fun ofCode(code: Char): Paint? = entries.firstOrNull { it.code == code }

        @JvmStatic
        fun ofLabel(label: String): Paint? = entries.firstOrNull { it.label == label.lowercase(Locale.ROOT) }
    }
}

/**
 * Where the macro may walk, set by brush: cells that are the stands of the ground - the block the feet are in - each with a [Paint].
 * Plain data, free of game state so checks can run it.
 *
 * Once any ground is painted allowed or preferred, the macro stands on painted ground only. With none, only what is painted
 * forbidden or wall is shut and the rest is open. A wall shuts every height of its column. Each cell also remembers how high its
 * floor is above the feet cell (in eighths of a block, so a slab is drawn at its top), for drawing.
 */
class PaintedArea(val name: String) {
    private val cells = Long2ByteOpenHashMap()
    private val walls = LongOpenHashSet()
    private var walkable = 0
    private var rests = 0
    private var preferred = 0
    private var revision = 0

    /** Changes with every edit: a map of the ground made for one revision is not good for another. */
    fun revision(): Int = revision

    fun size(): Int = cells.size

    /** Whether any ground is painted allowed or preferred, which is what limits the macro to painted ground. */
    fun restrictive(): Boolean = walkable > 0

    /** How many cells are painted as places to rest. */
    fun restCount(): Int = rests

    /** The [index]th rest cell (x, y, z of the feet cell), in no particular order, or null past the last. */
    fun restCell(index: Int): IntArray? {
        if (index < 0 || index >= rests) return null
        var seen = 0
        val iterator = cells.long2ByteEntrySet().fastIterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (decode(entry.byteValue) != Paint.REST) continue
            if (seen++ == index) return intArrayOf(unpackX(entry.longKey), unpackY(entry.longKey), unpackZ(entry.longKey))
        }
        return null
    }

    fun typeAt(x: Int, y: Int, z: Int): Paint? = decode(cells.getOrDefault(cell(x, y, z), NONE))

    /** How far above the feet cell's bottom the floor is, in eighths of a block. */
    fun heightOffset(x: Int, y: Int, z: Int): Int = (cells.getOrDefault(cell(x, y, z), NONE).toInt() shr OFFSET_SHIFT) and OFFSET_MASK

    /** Whether the macro may stand in this feet cell. */
    fun allows(x: Int, y: Int, z: Int): Boolean {
        if (walls.contains(column(x, z))) return false
        return when (typeAt(x, y, z)) {
            Paint.ALLOWED, Paint.PREFERRED, Paint.REST -> true
            Paint.FORBIDDEN, Paint.WALL -> false
            null -> walkable == 0
        }
    }

    /** Whether this cell is one a body may not pass through: a wall at any height of its column, or a cell painted forbidden. */
    fun shutAt(x: Int, y: Int, z: Int): Boolean = walls.contains(column(x, z)) || typeAt(x, y, z) == Paint.FORBIDDEN

    /** How many cells are painted preferred. */
    fun preferredCount(): Int = preferred

    /** The cheapest step this paint offers, relative to the plain cost: what a search estimate must not exceed. */
    fun minCostFactor(): Double = if (preferred > 0) PREFERRED_COST else 1.0

    /** What a step into this cell costs, relative to the plain cost: preferred ground is cheaper. */
    fun costFactor(x: Int, y: Int, z: Int): Double = if (typeAt(x, y, z) == Paint.PREFERRED) PREFERRED_COST else 1.0

    /** Paints a cell; [heightEighths] is how far above the feet cell's bottom the floor is. */
    fun put(x: Int, y: Int, z: Int, type: Paint, heightEighths: Int) {
        val key = cell(x, y, z)
        val value = encode(type, heightEighths)
        val old = cells.get(key)
        if (cells.containsKey(key) && old == value) return
        if (cells.containsKey(key)) forget(key, decode(old)!!)
        cells.put(key, value)
        if (type == Paint.ALLOWED || type == Paint.PREFERRED) walkable++
        if (type == Paint.REST) rests++
        if (type == Paint.PREFERRED) preferred++
        if (type == Paint.WALL) walls.add(column(x, z))
        revision++
    }

    fun erase(x: Int, y: Int, z: Int) {
        val key = cell(x, y, z)
        if (!cells.containsKey(key)) return
        forget(key, decode(cells.get(key))!!)
        cells.remove(key)
        revision++
    }

    fun clear() {
        if (cells.isEmpty()) return
        cells.clear()
        walls.clear()
        walkable = 0
        rests = 0
        preferred = 0
        revision++
    }

    private fun forget(key: Long, type: Paint) {
        if (type == Paint.ALLOWED || type == Paint.PREFERRED) walkable--
        if (type == Paint.REST) rests--
        if (type == Paint.PREFERRED) preferred--
        if (type == Paint.WALL) {
            val x = unpackX(key)
            val z = unpackZ(key)
            walls.remove(column(x, z))
            // Another wall in the same column, at another height, keeps it shut.
            val stillShut = LongArrayList()
            for (other in cells.keys) {
                if (other != key && decode(cells.get(other)) == Paint.WALL && unpackX(other) == x && unpackZ(other) == z) stillShut.add(other)
            }
            if (!stillShut.isEmpty()) walls.add(column(x, z))
        }
    }

    /** Calls [visit] with the cells within [radius] blocks of ([px], [pz]) - flat distance - up to [limit] of them, nearest not guaranteed. */
    fun forEachNear(px: Double, pz: Double, radius: Double, limit: Int, visit: (x: Int, y: Int, z: Int, type: Paint, heightEighths: Int) -> Unit) {
        var shown = 0
        val limitSquared = radius * radius
        val iterator = cells.long2ByteEntrySet().fastIterator()
        while (iterator.hasNext() && shown < limit) {
            val entry = iterator.next()
            val x = unpackX(entry.longKey)
            val z = unpackZ(entry.longKey)
            val dx = x + 0.5 - px
            val dz = z + 0.5 - pz
            if (dx * dx + dz * dz > limitSquared) continue
            val type = decode(entry.byteValue) ?: continue
            visit(x, unpackY(entry.longKey), z, type, (entry.byteValue.toInt() shr OFFSET_SHIFT) and OFFSET_MASK)
            shown++
        }
    }

    /** The file text: a header and a line `x y z T eighths` for each cell. */
    fun serialize(): String {
        val out = StringBuilder(HEADER).append('\n')
        for (entry in cells.long2ByteEntrySet()) {
            val type = decode(entry.byteValue) ?: continue
            out.append(unpackX(entry.longKey)).append(' ').append(unpackY(entry.longKey)).append(' ').append(unpackZ(entry.longKey))
                .append(' ').append(type.code).append(' ').append((entry.byteValue.toInt() shr OFFSET_SHIFT) and OFFSET_MASK).append('\n')
        }
        return out.toString()
    }

    companion object {
        const val HEADER = "geileraddons-area 1"

        /** Preferred ground costs this share of the plain cost. */
        const val PREFERRED_COST = 0.55

        /** The most cells one area keeps: a brush that would add more is stopped. */
        const val MAX_CELLS = 400_000

        private const val NONE: Byte = 0
        private const val OFFSET_SHIFT = 3
        private const val OFFSET_MASK = 15
        private const val TYPE_MASK = 7

        private fun encode(type: Paint, heightEighths: Int): Byte =
            ((type.ordinal + 1) or (heightEighths.coerceIn(0, 8) shl OFFSET_SHIFT)).toByte()

        private fun decode(value: Byte): Paint? {
            val index = (value.toInt() and TYPE_MASK) - 1
            return if (index < 0 || index >= Paint.entries.size) null else Paint.entries[index]
        }

        private fun cell(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or ((y + 2048).toLong() and 0xFFF)

        private fun column(x: Int, z: Int): Long = ((x.toLong() and 0x3FFFFFF) shl 26) or (z.toLong() and 0x3FFFFFF)

        private fun unpackX(key: Long): Int = ((key shr 38).toInt() shl 6) shr 6

        private fun unpackZ(key: Long): Int = (((key shr 12) and 0x3FFFFFF).toInt() shl 6) shr 6

        private fun unpackY(key: Long): Int = (key and 0xFFF).toInt() - 2048

        /** Reads the file text; bad lines are skipped, and null is returned for text that is not an area at all. */
        @JvmStatic
        fun parse(name: String, text: String): PaintedArea? {
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext() || lines.next().trim() != HEADER) return null
            val area = PaintedArea(name)
            while (lines.hasNext() && area.size() < MAX_CELLS) {
                val parts = lines.next().trim().split(' ')
                if (parts.size != 5 || parts[3].length != 1) continue
                val type = Paint.ofCode(parts[3][0]) ?: continue
                val x = parts[0].toIntOrNull() ?: continue
                val y = parts[1].toIntOrNull() ?: continue
                val z = parts[2].toIntOrNull() ?: continue
                val height = parts[4].toIntOrNull() ?: continue
                area.put(x, y, z, type, height)
            }
            return area
        }
    }
}
