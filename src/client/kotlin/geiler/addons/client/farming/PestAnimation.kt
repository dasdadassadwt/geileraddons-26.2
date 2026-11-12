package geiler.addons.client.farming

/** Pure geometry helpers for the Pest Highlighter's smooth vertical ring animation. */
object PestAnimation {
    /** A world-space point on the ring selected as the best tracer endpoint. */
    @JvmRecord
    data class RingTarget(val x: Double, val y: Double, val z: Double, val ringIndex: Int) {
        override fun toString(): String = "RingTarget[x=$x, y=$y, z=$z, ringIndex=$ringIndex]"
    }

    /**
     * Returns a triangular-wave position between the supplied feet and head heights.
     *
     * @param feetY lower endpoint in world coordinates
     * @param headY upper endpoint in world coordinates
     * @param elapsedTicks client game time including the current partial tick
     * @param cyclesPerSecond animation speed
     */
    @JvmStatic
    fun verticalPosition(feetY: Double, headY: Double, elapsedTicks: Double, cyclesPerSecond: Double): Double {
        if (!feetY.isFinite() || !headY.isFinite()) return 0.0
        if (!elapsedTicks.isFinite() || !cyclesPerSecond.isFinite()) return feetY

        var lower = feetY
        var upper = headY
        if (upper < lower) {
            val swapped = lower
            lower = upper
            upper = swapped
        }
        if (upper == lower) return lower

        val phase = elapsedTicks * cyclesPerSecond / 20.0
        return lower + (upper - lower) * triangleWave(phase)
    }

    /**
     * Returns one animated height per ring. The phase offset is stable per index, so adding a ring
     * does not make the other rings jump to a new endpoint.
     */
    @JvmStatic
    fun ringPositions(
        feetY: Double,
        headY: Double,
        elapsedTicks: Double,
        cyclesPerSecond: Double,
        ringCount: Int,
    ): DoubleArray {
        val count = Math.max(1, Math.min(8, ringCount))
        return DoubleArray(count) { index ->
            verticalPosition(
                feetY,
                headY,
                elapsedTicks + (20.0 / Math.max(0.0001, cyclesPerSecond)) * index / count,
                cyclesPerSecond,
            )
        }
    }

    /**
     * Chooses the point on the nearest ring circumference that faces the camera. Using the same
     * selected point for the tracer and ring renderer keeps the line attached while the ring moves.
     */
    @JvmStatic
    fun closestRingTarget(
        centerX: Double,
        centerZ: Double,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        radius: Float,
        ringHeights: DoubleArray?,
    ): RingTarget {
        if (ringHeights == null || ringHeights.isEmpty()) {
            return RingTarget(centerX, cameraY, centerZ, -1)
        }

        var dx = cameraX - centerX
        var dz = cameraZ - centerZ
        var horizontal = Math.sqrt(dx * dx + dz * dz)
        if (!(horizontal > 1.0e-8) || !horizontal.isFinite()) {
            dx = 1.0
            dz = 0.0
            horizontal = 1.0
        }

        val targetX = centerX + dx / horizontal * Math.max(0.0, radius.toDouble())
        val targetZ = centerZ + dz / horizontal * Math.max(0.0, radius.toDouble())
        var best = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (index in ringHeights.indices) {
            val y = ringHeights[index]
            if (!y.isFinite()) continue
            val distance = squaredDistance(cameraX, cameraY, cameraZ, targetX, y, targetZ)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }

        val bestHeight = ringHeights[best]
        val y = if (bestHeight.isFinite()) bestHeight else cameraY
        return RingTarget(targetX, y, targetZ, best)
    }

    private fun squaredDistance(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return dx * dx + dy * dy + dz * dz
    }

    /** Repeats 0 -> 1 -> 0 once per unit phase. */
    private fun triangleWave(phase: Double): Double {
        if (!phase.isFinite()) return 0.0
        val cycle = phase - Math.floor(phase)
        return if (cycle < 0.5) cycle * 2.0 else 2.0 - cycle * 2.0
    }
}
