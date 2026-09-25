package xyz.mederi.browser.bidi

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal object HumanMouse {

    data class TrajectoryPoint(val x: Int, val y: Int, val durationMs: Int)

    fun bezierPath(
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        steps: Int = 30
    ): List<TrajectoryPoint> {
        if (steps <= 0) return emptyList()

        val dist = hypot(x1 - x0, y1 - y0)
        if (dist < 1.0) {
            return listOf(TrajectoryPoint(x1.roundToInt(), y1.roundToInt(), 50))
        }

        val midX = (x0 + x1) / 2.0
        val midY = (y0 + y1) / 2.0
        val perpAngle = Math.atan2(y1 - y0, x1 - x0) + Math.PI / 2
        val curvature = dist * 0.18 * (if (Random.nextBoolean()) 1 else -1)
        val cp1x = x0 + (midX - x0) * 0.6 + cos(perpAngle) * curvature
        val cp1y = y0 + (midY - y0) * 0.6 + sin(perpAngle) * curvature
        val cp2x = x1 - (x1 - midX) * 0.6 + cos(perpAngle) * curvature * 0.4
        val cp2y = y1 - (y1 - midY) * 0.6 + sin(perpAngle) * curvature * 0.4

        val points = ArrayList<TrajectoryPoint>(steps + 2)
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val mt = 1.0 - t
            val px = mt * mt * mt * x0 + 3 * mt * mt * t * cp1x + 3 * mt * t * t * cp2x + t * t * t * x1
            val py = mt * mt * mt * y0 + 3 * mt * mt * t * cp1y + 3 * mt * t * t * cp2y + t * t * t * y1
            val jitterX = if (i != 0 && i != steps) (Random.nextDouble() - 0.5) * 1.5 else 0.0
            val jitterY = if (i != 0 && i != steps) (Random.nextDouble() - 0.5) * 1.5 else 0.0
            val duration = 30 + Random.nextInt(50)
            points.add(TrajectoryPoint((px + jitterX).roundToInt(), (py + jitterY).roundToInt(), duration))
        }
        val last = points.last()
        if (abs(last.x - x1.roundToInt()) > 0 || abs(last.y - y1.roundToInt()) > 0) {
            points.add(TrajectoryPoint(x1.roundToInt(), y1.roundToInt(), 40))
        }
        return points
    }

    fun microJitter(offset: Int = 5): Pair<Int, Int> {
        return Pair(Random.nextInt(-offset, offset + 1), Random.nextInt(-offset, offset + 1))
    }
}
