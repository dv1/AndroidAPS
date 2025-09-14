package app.aaps.plugins.sync.asteroidOS

import android.util.Log
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Utility functions to write message data according to the BG data format.

// Sometimes, it is essential to preserve the Int's 32 bits as they are
// when converting to Long, without interpreting the 32nd bit as a sign
// bit, and without any bits beyond the 32nd one set in the resulting
// Long value. toPosLong() exists for this purpose.
internal fun Int.toPosLong() = toLong() and 0xFFFFFFFFL

// See the "Prerequisites" section in bg-data-binary-format-spec.md
// for an explanation why the multiplications by 10 and 100 are
// present in toInsulinQuantity() and toGlucoseQuantity().

internal fun Double.toInsulinQuantity(): Short {
    return (if (this.isNaN()) UNKNOWN_INTEGER_QUANTITY else (this * 100).roundToInt()).toShort()
}

internal fun Double.toGlucoseQuantity(unit: GlucoseUnit): Short {

    return if (this.isNaN()) {
        UNKNOWN_INTEGER_QUANTITY.toShort()
    } else {
        when (unit) {
            GlucoseUnit.MGDL -> (this * 10).roundToInt()
            GlucoseUnit.MMOL -> (this * 100).roundToInt()
        }.toShort()
    }
}

internal fun MutableList<Byte>.addNumeric(f: Float): MutableList<Byte> {
    val floatBits = f.toBits().toPosLong()

    return this.apply {
        add(((floatBits and 0x000000FFL) ushr 0).toByte())
        add(((floatBits and 0x0000FF00L) ushr 8).toByte())
        add(((floatBits and 0x00FF0000L) ushr 16).toByte())
        add(((floatBits and 0xFF000000L) ushr 24).toByte())
    }
}

internal fun MutableList<Byte>.addNumeric(d: Double) =
    addNumeric(d.toFloat())

internal fun MutableList<Byte>.addNumeric(s: Short): MutableList<Byte> {
    return this.apply {
        add(((s.toInt() ushr 0) and 0xFF).toByte())
        add(((s.toInt() ushr 8) and 0xFF).toByte())
    }
}

internal fun MutableList<Byte>.addNumeric(l: Long): MutableList<Byte> {
    return this.apply {
        add(((l ushr 0) and 0xFF).toByte())
        add(((l ushr 8) and 0xFF).toByte())
        add(((l ushr 16) and 0xFF).toByte())
        add(((l ushr 24) and 0xFF).toByte())
        add(((l ushr 32) and 0xFF).toByte())
        add(((l ushr 40) and 0xFF).toByte())
        add(((l ushr 48) and 0xFF).toByte())
        add(((l ushr 56) and 0xFF).toByte())
    }
}

// Helper class for storing normalized time series data points. This is also
// needed for the time series simplification and reduction algorithm.
internal data class NormalizedDataPoint(var timestamp: Double, var glucose: Double) {
    operator fun minus(other: NormalizedDataPoint) =
        NormalizedDataPoint(
            this.timestamp - other.timestamp,
            this.glucose - other.glucose
        )

    infix fun distanceTo(other: NormalizedDataPoint): Double {
        val delta = this - other
        return sqrt(delta.timestamp * delta.timestamp + delta.glucose * delta.glucose)
    }
}

// Implementation of the Ramer-Douglas-Peucker (RDP) line downsampling
// algorithm for reducing the number of data points when visualizing
// time series with a polyline. Note that this is not used for dot
// visualization on purpose; the requirements are different there, and
// RDP does not meet them well. See the reduceToMaxNumDataPointsForDots()
// documentation for more.
private fun ramerDouglasPeucker(dataPoints: List<NormalizedDataPoint>, epsilon: Double): List<NormalizedDataPoint> {
    if (dataPoints.size < 3)
        return dataPoints

    val first = dataPoints.first()
    val last = dataPoints.last()

    // The for loop below computes the distances between the line defined by "first" and "last"
    // and each point within those two points. The following formula is used to compute the distance.
    // x1 is first's timestamp, y1 is first's glucose, x2 is last's timestamp, y2 is last's glucose.
    // x0 and y0 are the timestamp and glucose value of the point whose distance shall be computed.
    // (In graphs, timestamp is the X coordinate, and glucose is the Y coordinate.)
    //
    // distance = abs((y2-y1)*x0 - (x2-x1)*y0 + x2*y1 - y2*x1) / sqrt((x2-x1)^2 + (y2-y1)^2)
    //
    // (The denominator is the length, calculated by the Pythagorean theorem, between first and last.)
    //
    // Since (x2-x1), (y2-y1), the Pythagorean length, and the (x2*y1 - y2*x1) part are all invariants
    // in the for loop, they are precomputed.

    val x1 = first.timestamp
    val y1 = first.glucose
    val x2 = last.timestamp
    val y2 = last.glucose
    val xd = x2 - x1
    val yd = y2 - y1
    val lineLength = sqrt(xd * xd + yd * yd)
    val invariant = x2 * y1 - y2 * x1

    if (lineLength < 1e-10) {
        // Handle degenerate case where the line is actually a point
        // (since first and last data points are pretty much the same,
        // and, consequently, so are any and all points in between.)
        return listOf(first)
    }

    var maxDistance = Double.NEGATIVE_INFINITY
    var farthestPointIndex: Int = -1

    for (pointIndex in 1..(dataPoints.size-2)) {
        val point = dataPoints[pointIndex]
        val x0 = point.timestamp
        val y0 = point.glucose

        val distance = (yd * x0 - xd * y0 + invariant).absoluteValue / lineLength

        if (distance > maxDistance) {
            maxDistance = distance
            farthestPointIndex = pointIndex
        }
    }

    require(farthestPointIndex >= 0)

    // If the farthest point is far away enough such that its distance exceeds
    // the epsilon value, then this farthest point is significant, and must be
    // marked as such. Subdivision shall continue in subregions that border on
    // that point. Otherwise, the farthest point is considered to be insignificant,
    // and thus all the other points between first and last are considered
    // insignificant as well, and are to be marked as such.
    if (maxDistance > epsilon) {
        // Note that both sublists contain the farthest point. This is important
        // for the algorithm to work correctly (otherwise, point-line distance
        // calculation will yield incorrect results). To avoid that point being
        // added more than once, the last item in the result from processing the
        // first sublist is omitted from the overall output.
        val firstDataPointSubList = dataPoints.subList(0, farthestPointIndex + 1)
        val secondDataPointSubList = dataPoints.subList(farthestPointIndex, dataPoints.size)

        val firstResult = ramerDouglasPeucker(firstDataPointSubList, epsilon)
        val secondResult = ramerDouglasPeucker(secondDataPointSubList, epsilon)

        return firstResult.subList(0, firstResult.size - 1) + secondResult
    } else {
        // Only the first and last data points are relevant; the rest is insignificant.
        return listOf(first, last)
    }
}

// TODO: These are temporary. 175 is the length in pixels of the time series
// display on the smartwatch. 5.0 is the diameter of dots on that display.
//
// The RPD_EPSILON is picked such that said epsilon equals 3 pixels; that is,
// if a data point is more than 3 pixels away from a line that RPD checks
// against, it is considered significant.
//
// INSIGNIFICANT_DATA_POINT_MAX_DIAMETER is picked such that the "surroundings"
// around an insignificant data point equal 1.5 times the diameter of a dot.
// This means that if there is no significant data point in that vicinity,
// then that formerly insignificant data point is made significant again.
// (See the comments in reduceToMaxNumDataPointsForDots() for details about
// significant/insignificant data points).
//
// These thresholds are empirically picked for a suitable compromise between
// visual quality and amount of data points sent to the smartwatch.
//
// In future, these constants will be replaced by calculations based on feedback
// from the watch containing the display length and the dot diameter. Currently,
// these are 175 and 5 pixels, but could change in the future, so hardcoding
// them here is not ideal.
private const val RPD_EPSILON = 3.0 / 175.0
private const val INSIGNIFICANT_DATA_POINT_MAX_DIAMETER = 2 * 5.0 / 175.0

internal fun List<NormalizedDataPoint>.reduceToMaxNumDataPointsForDots(aapsLogger: AAPSLogger, timestampDiscontinuityThreshold: Double): List<NormalizedDataPoint> {
    // This algorithm's basic principle is to make the reduced result
    // visually consistent by picking every Nth data point and skipping
    // the others. The N is called the "skip factor" here. Calculating
    // it is done this way:
    //
    // actual distance between data points = path length / num path data points
    // skip factor = ideal distance between data points /
    //     actual distance between data points
    //
    // Suppose for example that the actual average distance between data
    // points is 100 / 50 = 2 , but the ideal distance is 6. This means
    // that if from the start, points are skipped all the way to the
    // third data point, then this sums up to a distance of:
    //
    // actual distance between data points * num skipped data points =
    //     ideal distance between data points
    //
    // Inserting the values, the result is:
    //
    // 2 * num skipped data points = 6
    //
    // Transforming the equation to calculate the number of skipped data
    // points, and renaming that number to "skip factor", the original
    // formula results
    //
    // But, since only whole data points can be clipped, the skip factor
    // must be an integer. It is therefore rounded.
    //
    // Then, the task is to use modulo arithmetic to check which data
    // points along the path is to be retained. The most basic approach
    // is to calculate ((data point index) modulo (skip factor)), and
    // retain the data point if the modulo result is 0. However, this
    // has one drawback: This will always retain the very first data
    // point, while towards the end, more data points are skipped.
    // This creates a visual asymmetry. The fix is easy: Instead of
    // comparing the modulo result with 0, compare it with half the
    // skip factor.
    //
    // Furthermore, data points may not be contiguous, and instead be
    // a sequence of contiguous subpaths. Those are  subsets of the list
    // of data points where neighbouring data points are in a distance to
    // each other that is no farther than timestampDiscontinuityThreshold.
    // In other words, if two data points A and B are neighbouring, and the
    // distance from A to B exceeds timestampDiscontinuityThreshold, then
    // this is where a subpath ends and another begins. These discontinuities
    // are detected, and the result is a list of subpaths. The "path length"
    // and "num path data points" factors mentioned above go across all subpaths,
    // but skip the discontinuities. For this reason, "path length" must be
    // the sum of all subpath lengths, and the "num path data points" must be
    // the sum of all number of data points inside each subpath.
    //
    // Also, in cases where the actual distance between data points is _larger_
    // than the ideal distance between data points, the skip factor would be
    // less than 1. The original path is retained then; it is already lower
    // sampled than what this subsampling would accomplish.

    if (this.size < 3) {
        aapsLogger.debug(LTag.WEAR, "Time series contains only ${this.size} data point(s); no need to reduce")
        return this
    }

    val self = this

    val contiguousSubpaths = buildList<List<NormalizedDataPoint>> {
        var currentSegment = mutableListOf(self.first())

        for (dataPointIndex in 1..<self.size) {
            val previousDataPoint = self[dataPointIndex - 1]
            val currentDataPoint = self[dataPointIndex]

            if ((currentDataPoint.timestamp - previousDataPoint.timestamp) > timestampDiscontinuityThreshold) {
                add(currentSegment)
                currentSegment = mutableListOf(currentDataPoint)
            } else {
                currentSegment.add(currentDataPoint)
            }
        }

        add(currentSegment)
    }

    aapsLogger.debug(LTag.WEAR, "Got ${contiguousSubpaths.size} subpath(s)")

    // Sum up the length of each subpath, and also the number of data points
    // in each subpaths. These values will be necessary for the skip factor.
    val totalSubpathsLength =
        contiguousSubpaths.sumOf { contiguousSubpath ->
            contiguousSubpath
                .zipWithNext { dataPointA, dataPointB -> dataPointA distanceTo dataPointB }
                .sum()
        }
    val totalNumPointsInSubpaths = contiguousSubpaths.sumOf { contiguousSubpath -> contiguousSubpath.size }
    if (totalNumPointsInSubpaths < 1)
        return this

    val distanceBetweenDataPoints = totalSubpathsLength / totalNumPointsInSubpaths
    // If there are so few data points that the spacing between them already
    // exceeds the minimum spacing, just return the data points as they are.
    if (distanceBetweenDataPoints > INSIGNIFICANT_DATA_POINT_MAX_DIAMETER)
        return this

    val result = mutableListOf<NormalizedDataPoint>()
    val skipFactor = (INSIGNIFICANT_DATA_POINT_MAX_DIAMETER / distanceBetweenDataPoints).let { factor ->
        // This can happen when totalNumPointsInSubpaths is extremely large
        // and/or totalSubpathsLength is extremely small. This is highly
        // unlikely to happen, but not fundamentally impossible, so use
        // a skip factor of 1 in that exceedingly unlikely case.
        if (factor == Double.POSITIVE_INFINITY) 1.0 else factor
    }.roundToInt().let { factor ->
        // Catch very unlikely corner cases where skipFactor would otherwise be 0.
        return@let if (factor < 1) {
            aapsLogger.warn(LTag.WEAR, "Skip factor is $factor , must be at least 1; " +
                "INSIGNIFICANT_DATA_POINT_MAX_DIAMETER = $INSIGNIFICANT_DATA_POINT_MAX_DIAMETER; " +
                "distanceBetweenDataPoints = $distanceBetweenDataPoints; returning skip factor 1"
            )
            1
        } else
            factor
    }
    var indexOffset = 0

    for (contiguousSubpath in contiguousSubpaths) {
        result += if (contiguousSubpath.size >= 3) {
            contiguousSubpath.filterIndexed { index, _ ->
                ((index + indexOffset).mod(skipFactor)) == (skipFactor / 2)
            }
        } else {
            contiguousSubpath
        }
        indexOffset += contiguousSubpath.size
    }

    return result.toList()
}

internal fun List<NormalizedDataPoint>.reduceToMaxNumDataPointsForLines() = ramerDouglasPeucker(this, RPD_EPSILON)
