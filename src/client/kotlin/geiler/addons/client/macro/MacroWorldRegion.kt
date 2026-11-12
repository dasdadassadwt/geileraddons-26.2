package geiler.addons.client.macro

/** Persisted world placement and trigger/render settings for one macro event stack. */
class MacroWorldRegion {
    enum class Shape { SQUARE, CIRCLE, RING }

    private var placedState = false
    private var worldKeyValue = ""
    private var xValue = 0.0
    private var yValue = 0.0
    private var zValue = 0.0
    private var shapeValue = Shape.CIRCLE
    private var sizeValue = 8.0
    private var innerSizeValue = 4.0
    private var yToleranceValue = 3.0
    private var repeatDelayMillisValue = 1_000
    private var oncePerWorldValue = false
    private var colorValue = 0xFF43D9C4.toInt()
    private var fillOpacityValue = 22
    private var lineWidthValue = 2.5f

    fun placed(): Boolean = placedState
    fun worldKey(): String = worldKeyValue
    fun x(): Double = xValue
    fun y(): Double = yValue
    fun z(): Double = zValue
    fun shape(): Shape = shapeValue
    fun size(): Double = sizeValue
    fun innerSize(): Double = innerSizeValue
    fun yTolerance(): Double = yToleranceValue
    fun repeatDelayMillis(): Int = repeatDelayMillisValue
    fun oncePerWorld(): Boolean = oncePerWorldValue
    fun color(): Int = colorValue
    fun fillOpacity(): Int = fillOpacityValue
    fun lineWidth(): Float = lineWidthValue

    fun place(ownerWorldKey: String?, centerX: Double, centerY: Double, centerZ: Double) {
        if (ownerWorldKey == null || MacroJavaStrings.isBlank(ownerWorldKey) || !centerX.isFinite() ||
            !centerY.isFinite() || !centerZ.isFinite()) {
            clearPlacement()
            return
        }
        placedState = true
        worldKeyValue = ownerWorldKey.substring(0, minOf(256, ownerWorldKey.length))
        xValue = centerX
        yValue = centerY
        zValue = centerZ
    }

    fun restorePlacement(isPlaced: Boolean, ownerWorldKey: String?, centerX: Double, centerY: Double, centerZ: Double) {
        place(ownerWorldKey, centerX, centerY, centerZ)
        if (!isPlaced) clearPlacement()
    }

    fun clearPlacement() {
        placedState = false
        worldKeyValue = ""
        xValue = 0.0
        yValue = 0.0
        zValue = 0.0
    }

    fun setShape(value: Shape?) {
        shapeValue = value ?: Shape.CIRCLE
    }

    fun setSize(value: Double) {
        sizeValue = if (value.isFinite()) clamp(value, 1.0, 128.0) else 8.0
        innerSizeValue = minOf(innerSizeValue, sizeValue - 0.25)
    }

    fun setInnerSize(value: Double) {
        innerSizeValue = if (value.isFinite()) clamp(value, 0.0, sizeValue - 0.25) else 4.0
    }

    fun setYTolerance(value: Double) {
        yToleranceValue = if (value.isFinite()) clamp(value, 0.0, 64.0) else 3.0
    }

    fun setRepeatDelayMillis(value: Int) {
        repeatDelayMillisValue = clamp(value, 50, 3_600_000)
    }

    fun setOncePerWorld(value: Boolean) {
        oncePerWorldValue = value
    }

    fun setColor(value: Int) {
        colorValue = value or 0xFF000000.toInt()
    }

    fun setFillOpacity(value: Int) {
        fillOpacityValue = clamp(value, 0, 180)
    }

    fun setLineWidth(value: Float) {
        lineWidthValue = if (value.isFinite()) clamp(value.toDouble(), 1.0, 8.0).toFloat() else 2.5f
    }

    /** Shape sizes are radii for round shapes and half-widths for squares. */
    fun contains(currentWorldKey: String?, px: Double, py: Double, pz: Double): Boolean {
        if (!placedState || currentWorldKey == null || worldKeyValue != currentWorldKey ||
            !px.isFinite() || !py.isFinite() || !pz.isFinite() || kotlin.math.abs(py - yValue) > yToleranceValue
        ) return false
        val dx = kotlin.math.abs(px - xValue)
        val dz = kotlin.math.abs(pz - zValue)
        return when (shapeValue) {
            Shape.SQUARE -> dx <= sizeValue && dz <= sizeValue
            Shape.CIRCLE -> dx * dx + dz * dz <= sizeValue * sizeValue
            Shape.RING -> {
                val distanceSquared = dx * dx + dz * dz
                distanceSquared <= sizeValue * sizeValue && distanceSquared >= innerSizeValue * innerSizeValue
            }
        }
    }

    private companion object {
        @JvmStatic
        private fun clamp(value: Double, min: Double, max: Double): Double = maxOf(min, minOf(max, value))

        @JvmStatic
        private fun clamp(value: Int, min: Int, max: Int): Int = maxOf(min, minOf(max, value))
    }
}
