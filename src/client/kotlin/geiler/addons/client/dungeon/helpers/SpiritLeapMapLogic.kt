package geiler.addons.client.dungeon.helpers

/** Pure geometry for the dungeon map shown above the Spirit Leap menu: marker placement and nearest-pick. */
class SpiritLeapMapLogic private constructor() {
    @JvmRecord
    data class Marker(val name: String, val x: Float, val y: Float)

    companion object {
        const val TOP_MARGIN = 4
        const val CARD_GAP = 8

        /** The requested [scale] as given; 0 hides the map. The map may overlap the cards. */
        @JvmStatic
        fun scale(requested: Float, mapHeight: Float): Float =
            if (!requested.isFinite() || requested <= 0f || mapHeight <= 0f) 0f else requested

        /** Map top: just above the cards at [cardsTop], pinned to the top margin when it is taller than the room. */
        @JvmStatic
        fun top(mapHeight: Float, cardsTop: Int): Float =
            maxOf(TOP_MARGIN.toFloat(), cardsTop - CARD_GAP - mapHeight)

        /** The Spirit Leap copy draws only when enabled and the normal HUD dungeon map is not already on screen. */
        @JvmStatic
        fun showsLeapMap(menuMapOn: Boolean, hudMapVisible: Boolean): Boolean = menuMapOn && !hudMapVisible

        /** Inside the Spirit Leap menu the clickable leap copy replaces the HUD map, so only one map shows. */
        @JvmStatic
        fun hudMapHiddenInLeapMenu(leapMenuOpen: Boolean, leapMapOn: Boolean): Boolean = leapMenuOpen && leapMapOn

        /** Half-open rectangle test; an empty map contains nothing. */
        @JvmStatic
        fun contains(left: Float, top: Float, width: Float, height: Float, x: Float, y: Float): Boolean =
            width > 0f && height > 0f && x >= left && x < left + width && y >= top && y < top + height

        /** Screen position of a teammate marker: grid cell plus in-cell offset, scaled from the map origin. */
        @JvmStatic
        fun markerPosition(
            originX: Float, originY: Float, scale: Float, gridX: Int, gridY: Int, stride: Int,
            cellX: Int, cellZ: Int, offsetX: Double, offsetZ: Double,
        ): FloatArray = floatArrayOf(
            originX + (gridX + ((cellX + offsetX) * stride).toFloat()) * scale,
            originY + (gridY + ((cellZ + offsetZ) * stride).toFloat()) * scale,
        )

        /** Name of the marker closest to the cursor within [maxDistance], first marker on an exact tie. */
        @JvmStatic
        fun nearestName(markers: List<Marker>, x: Float, y: Float, maxDistance: Float): String? {
            var best: Marker? = null
            var bestDistance = maxDistance.toDouble() * maxDistance
            for (marker in markers) {
                val dx = (marker.x - x).toDouble()
                val dy = (marker.y - y).toDouble()
                val distance = dx * dx + dy * dy
                if (distance <= bestDistance && (best == null || distance < bestDistance)) {
                    best = marker
                    bestDistance = distance
                }
            }
            return best?.name
        }
    }
}
