package geiler.addons.client.module.impl

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Pure grid rules shared by the editor input and offline layout checks. */
object InventoryButtonLayout {
    const val CELL_SIZE = 18
    const val FIRST_SLOT_X = 8
    const val FIRST_SLOT_Y = 18
    const val EDITOR_PANEL_WIDTH = 204
    const val EDITOR_PANEL_HEIGHT = 218

    /** Grid cells share the exact 18px lattice used by vanilla inventory slots. */
    @JvmStatic
    fun gridX(cursorX: Double, inventoryLeft: Int): Int =
        Math.round((cursorX - (inventoryLeft + FIRST_SLOT_X) - CELL_SIZE / 2.0) / CELL_SIZE).toInt()

    @JvmStatic
    fun gridY(cursorY: Double, inventoryTop: Int): Int =
        Math.round((cursorY - (inventoryTop + FIRST_SLOT_Y) - CELL_SIZE / 2.0) / CELL_SIZE).toInt()

    @JvmStatic
    fun pixelX(gridX: Int, inventoryLeft: Int): Int = inventoryLeft + FIRST_SLOT_X + gridX * CELL_SIZE

    @JvmStatic
    fun pixelY(gridY: Int, inventoryTop: Int): Int = inventoryTop + FIRST_SLOT_Y + gridY * CELL_SIZE

    /** Pixel location for a persisted placement, including its pre-slot-grid compatibility anchor. */
    @JvmStatic
    fun placementPixelX(placement: InventoryButtonPlacement, inventoryLeft: Int): Int =
        inventoryLeft + (if (placement.slotAligned()) FIRST_SLOT_X else 0) + placement.gridX() * CELL_SIZE

    @JvmStatic
    fun placementPixelY(placement: InventoryButtonPlacement, inventoryTop: Int): Int =
        inventoryTop + (if (placement.slotAligned()) FIRST_SLOT_Y else 0) + placement.gridY() * CELL_SIZE

    @JvmStatic
    fun inventoryBounds(left: Int, top: Int, width: Int, height: Int): Bounds = Bounds(left, top, width, height)

    /** Places the editor at the clear right side when possible, then the left, then a fitted overlay. */
    @JvmStatic
    fun editorPanel(
        screenWidth: Int,
        screenHeight: Int,
        inventoryLeft: Int,
        inventoryTop: Int,
        inventoryWidth: Int,
        occupiedBounds: List<Bounds?>?,
    ): EditorPanel {
        val viewportWidth = screenWidth.coerceAtLeast(0)
        val viewportHeight = screenHeight.coerceAtLeast(0)
        val panelWidth = maxOf(1, minOf(EDITOR_PANEL_WIDTH, viewportWidth - 8))
        val panelHeight = maxOf(1, minOf(EDITOR_PANEL_HEIGHT, viewportHeight - 8))
        val maxX = (viewportWidth - panelWidth).coerceAtLeast(0)
        val maxY = (viewportHeight - panelHeight).coerceAtLeast(0)
        val horizontalMargin = minOf(8, maxX / 2)
        val verticalMargin = minOf(4, maxY / 2)
        val leftX = horizontalMargin
        val rightX = maxX - horizontalMargin
        val y = inventoryTop.coerceIn(verticalMargin, maxY - verticalMargin)
        val leftClear = panelLocationClear(leftX, y, panelWidth, panelHeight, occupiedBounds)
        val rightClear = panelLocationClear(rightX, y, panelWidth, panelHeight, occupiedBounds)
        if (rightClear) return EditorPanel(rightX, y, panelWidth, panelHeight, EditorPanelSide.RIGHT)
        if (leftClear) return EditorPanel(leftX, y, panelWidth, panelHeight, EditorPanelSide.LEFT)

        val preferredX = inventoryLeft.toLong() + inventoryWidth + 6L
        val fittedX = preferredX.coerceIn(leftX.toLong(), rightX.toLong()).toInt()
        return EditorPanel(fittedX, y, panelWidth, panelHeight, EditorPanelSide.FITTED_OVERLAY)
    }

    private fun panelLocationClear(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        occupiedBounds: List<Bounds?>?,
    ): Boolean {
        for (bounds in occupiedBounds.orEmpty()) {
            if (bounds != null && bounds.intersects(x, y, width, height)) return false
        }
        return true
    }

    /** Tests a proposed cell against the viewport, vanilla UI, and other button placements. */
    @JvmStatic
    fun canPlace(
        placements: List<InventoryButtonPlacement>,
        movingId: Int,
        gridX: Int,
        gridY: Int,
        screenWidth: Int,
        screenHeight: Int,
        inventoryLeft: Int,
        inventoryTop: Int,
        blockedBounds: List<Bounds?>?,
    ): Boolean {
        if (!fitsViewportAndBounds(gridX, gridY, screenWidth, screenHeight, inventoryLeft, inventoryTop, blockedBounds)) {
            return false
        }
        val candidateX = pixelX(gridX, inventoryLeft)
        val candidateY = pixelY(gridY, inventoryTop)
        val candidate = Bounds(candidateX, candidateY, CELL_SIZE, CELL_SIZE)
        for (placement in placements) {
            if (placement.id() == movingId) continue
            if (candidate.intersects(
                    placementPixelX(placement, inventoryLeft), placementPixelY(placement, inventoryTop),
                    CELL_SIZE, CELL_SIZE,
                )
            ) return false
        }
        return true
    }

    @JvmStatic
    fun isPlacementValid(
        placements: List<InventoryButtonPlacement>,
        placement: InventoryButtonPlacement?,
        screenWidth: Int,
        screenHeight: Int,
        inventoryLeft: Int,
        inventoryTop: Int,
        blockedBounds: List<Bounds?>?,
    ): Boolean {
        if (placement == null) return false
        val x = placementPixelX(placement, inventoryLeft)
        val y = placementPixelY(placement, inventoryTop)
        if (!fitsPixelAndBounds(x, y, screenWidth, screenHeight, blockedBounds)) return false
        val rectangle = Bounds(x, y, CELL_SIZE, CELL_SIZE)
        for (other in placements) {
            if (other.id() == placement.id()) continue
            if (rectangle.intersects(
                    placementPixelX(other, inventoryLeft), placementPixelY(other, inventoryTop),
                    CELL_SIZE, CELL_SIZE,
                )
            ) return false
        }
        return true
    }

    @JvmStatic
    fun invalidCount(
        placements: List<InventoryButtonPlacement>,
        screenWidth: Int,
        screenHeight: Int,
        inventoryLeft: Int,
        inventoryTop: Int,
        blockedBounds: List<Bounds?>?,
    ): Int {
        var invalid = 0
        for (placement in placements) {
            if (!isPlacementValid(placements, placement, screenWidth, screenHeight, inventoryLeft, inventoryTop, blockedBounds)) {
                invalid++
            }
        }
        return invalid
    }

    /**
     * Moves only currently invalid placements, preserving every valid cell. Reflow order and
     * equal-distance tie breaks are stable so the same layout produces the same result.
     */
    @JvmStatic
    fun reflowInvalid(
        placements: List<InventoryButtonPlacement>,
        screenWidth: Int,
        screenHeight: Int,
        inventoryLeft: Int,
        inventoryTop: Int,
        blockedBounds: List<Bounds?>?,
    ): ReflowResult {
        val ordered = placements.sortedBy { it.id() }
        val occupied = ArrayList<Bounds>()
        val invalid = ArrayList<InventoryButtonPlacement>()
        for (placement in ordered) {
            val x = placementPixelX(placement, inventoryLeft)
            val y = placementPixelY(placement, inventoryTop)
            val rectangle = Bounds(x, y, CELL_SIZE, CELL_SIZE)
            if (fitsPixelAndBounds(x, y, screenWidth, screenHeight, blockedBounds) && !overlaps(occupied, x, y)) {
                occupied.add(rectangle)
                continue
            }
            invalid.add(placement)
        }

        var moved = 0
        var remaining = 0
        for (placement in invalid) {
            val next = nearestFreeCell(placement, occupied, screenWidth, screenHeight, inventoryLeft, inventoryTop, blockedBounds)
            if (next == null) {
                remaining++
                continue
            }
            placement.setGrid(next.x, next.y)
            occupied.add(Bounds(pixelX(next.x, inventoryLeft), pixelY(next.y, inventoryTop), CELL_SIZE, CELL_SIZE))
            moved++
        }
        return ReflowResult(moved, remaining)
    }

    /** The cell under the cursor, ignoring any saved placements that are currently invalid. */
    @JvmStatic
    fun hit(
        placements: List<InventoryButtonPlacement>,
        cursorX: Double,
        cursorY: Double,
        inventoryLeft: Int,
        inventoryTop: Int,
        screenWidth: Int,
        screenHeight: Int,
        blockedBounds: List<Bounds?>?,
    ): InventoryButtonPlacement? {
        for (index in placements.indices.reversed()) {
            val placement = placements[index]
            if (!isPlacementValid(placements, placement, screenWidth, screenHeight, inventoryLeft, inventoryTop, blockedBounds)) {
                continue
            }
            val x = placementPixelX(placement, inventoryLeft)
            val y = placementPixelY(placement, inventoryTop)
            if (cursorX >= x && cursorX < x + CELL_SIZE && cursorY >= y && cursorY < y + CELL_SIZE) return placement
        }
        return null
    }

    /** Finds a saved placement even when its cell has become invalid, provided it is still visible. */
    @JvmStatic
    fun hitSaved(
        placements: List<InventoryButtonPlacement>,
        cursorX: Double,
        cursorY: Double,
        inventoryLeft: Int,
        inventoryTop: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): InventoryButtonPlacement? {
        for (index in placements.indices.reversed()) {
            val placement = placements[index]
            val x = placementPixelX(placement, inventoryLeft)
            val y = placementPixelY(placement, inventoryTop)
            if (x >= 0 && y >= 0 && x + CELL_SIZE <= screenWidth && y + CELL_SIZE <= screenHeight &&
                cursorX >= x && cursorX < x + CELL_SIZE && cursorY >= y && cursorY < y + CELL_SIZE
            ) return placement
        }
        return null
    }

    /** Whether a grid cell is a visible exterior cell, without considering button occupancy. */
    @JvmStatic
    fun fitsViewportAndBounds(
        gridX: Int,
        gridY: Int,
        screenWidth: Int,
        screenHeight: Int,
        inventoryLeft: Int,
        inventoryTop: Int,
        blockedBounds: List<Bounds?>?,
    ): Boolean {
        val x = pixelX(gridX, inventoryLeft)
        val y = pixelY(gridY, inventoryTop)
        if (x < 0 || y < 0 || x + CELL_SIZE > screenWidth || y + CELL_SIZE > screenHeight) return false
        if (blockedBounds == null) return true
        for (bounds in blockedBounds) {
            if (bounds != null && bounds.intersects(x, y, CELL_SIZE, CELL_SIZE)) return false
        }
        return true
    }

    private fun nearestFreeCell(
        placement: InventoryButtonPlacement,
        occupied: List<Bounds>,
        screenWidth: Int,
        screenHeight: Int,
        inventoryLeft: Int,
        inventoryTop: Int,
        blockedBounds: List<Bounds?>?,
    ): Cell? {
        val originX = inventoryLeft + FIRST_SLOT_X
        val originY = inventoryTop + FIRST_SLOT_Y
        val minX = kotlin.math.ceil(-originX / CELL_SIZE.toDouble()).toInt()
        val minY = kotlin.math.ceil(-originY / CELL_SIZE.toDouble()).toInt()
        val maxX = kotlin.math.floor((screenWidth - CELL_SIZE - originX) / CELL_SIZE.toDouble()).toInt()
        val maxY = kotlin.math.floor((screenHeight - CELL_SIZE - originY) / CELL_SIZE.toDouble()).toInt()
        val desiredPixelX = placementPixelX(placement, inventoryLeft).toLong()
        val desiredPixelY = placementPixelY(placement, inventoryTop).toLong()
        var best: Cell? = null
        var bestDistance = Long.MAX_VALUE
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val cellX = pixelX(x, inventoryLeft)
                val cellY = pixelY(y, inventoryTop)
                if (!fitsPixelAndBounds(cellX, cellY, screenWidth, screenHeight, blockedBounds) || overlaps(occupied, cellX, cellY)) {
                    continue
                }
                val dx = cellX.toLong() - desiredPixelX
                val dy = cellY.toLong() - desiredPixelY
                val distance = dx * dx + dy * dy
                if (distance < bestDistance || (distance == bestDistance && precedes(x, y, best))) {
                    best = Cell(x, y)
                    bestDistance = distance
                }
            }
        }
        return best
    }

    private fun fitsPixelAndBounds(x: Int, y: Int, screenWidth: Int, screenHeight: Int, blockedBounds: List<Bounds?>?): Boolean {
        if (x < 0 || y < 0 || x + CELL_SIZE > screenWidth || y + CELL_SIZE > screenHeight) return false
        if (blockedBounds == null) return true
        for (bounds in blockedBounds) {
            if (bounds != null && bounds.intersects(x, y, CELL_SIZE, CELL_SIZE)) return false
        }
        return true
    }

    private fun overlaps(occupied: List<Bounds>, x: Int, y: Int): Boolean =
        occupied.any { it.intersects(x, y, CELL_SIZE, CELL_SIZE) }

    private fun precedes(x: Int, y: Int, other: Cell?): Boolean =
        other == null || y < other.y || (y == other.y && x < other.x)

    @JvmRecord
    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun intersects(otherX: Int, otherY: Int, otherWidth: Int, otherHeight: Int): Boolean =
            otherX < x + width && otherX + otherWidth > x && otherY < y + height && otherY + otherHeight > y
    }

    @JvmRecord
    data class ReflowResult(val moved: Int, val remaining: Int)

    @JvmRecord
    data class EditorPanel(val x: Int, val y: Int, val width: Int, val height: Int, val side: EditorPanelSide)

    enum class EditorPanelSide {
        RIGHT,
        LEFT,
        FITTED_OVERLAY,
    }

    private data class Cell(val x: Int, val y: Int)
}
