package app.aaps.plugins.sync.asteroidOS

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AsteroidOSUtilsTest : TestBase() {
    @Test
    fun toPosLongTest() {
        // Check that toPosLong() works as expected.
        // When the value 0xFAA01234 is converted to an
        // int, its most significant bit is interpreted
        // as a sign bit. Consequently, the resulting
        // Long value is negative (-90172876L). When
        // interpreting the bits to convert this to an
        // unsigned hex value, the result would be
        // 0xFFFFFFFFFAA01234.
        val originalValue = 0xFAA01234.toInt()
        assertEquals(-90172876L, originalValue.toLong())
        assertEquals(0xFAA01234L, originalValue.toPosLong())
    }

    @Test
    fun toInsulinQuantityTest() {
        assertEquals(UNKNOWN_INTEGER_QUANTITY.toShort(), Double.NaN.toInsulinQuantity())
        assertEquals(250.toShort(), 2.5.toInsulinQuantity())
    }

    @Test
    fun toGlucoseQuantityTest() {
        assertEquals(UNKNOWN_INTEGER_QUANTITY.toShort(), Double.NaN.toGlucoseQuantity(GlucoseUnit.MGDL))
        assertEquals(25.toShort(), 2.5.toGlucoseQuantity(GlucoseUnit.MGDL))
        assertEquals(250.toShort(), 2.5.toGlucoseQuantity(GlucoseUnit.MMOL))
    }

    @Test
    fun addNumericToByteListTest() {
        val bytes = mutableListOf<Byte>()
        bytes.addNumeric(1.toFloat())
        assert(bytes.size == 4)
        bytes[0] = 0x3F.toByte()
        bytes[1] = 0x80.toByte()
        bytes[2] = 0x00.toByte()
        bytes[3] = 0x00.toByte()
        bytes.clear()

        // Double is implicitly downcast to Float before adding its bits to the list.
        bytes.addNumeric(1.toDouble())
        assert(bytes.size == 4)
        bytes[0] = 0x3F.toByte()
        bytes[1] = 0x80.toByte()
        bytes[2] = 0x00.toByte()
        bytes[3] = 0x00.toByte()
        bytes.clear()

        bytes.addNumeric(0xAABB.toShort())
        assert(bytes.size == 2)
        bytes[0] = 0xBB.toByte()
        bytes[1] = 0xAA.toByte()
        bytes.clear()

        bytes.addNumeric(0x1122334455667788L)
        assert(bytes.size == 8)
        bytes[0] = 0x88.toByte()
        bytes[1] = 0x77.toByte()
        bytes[2] = 0x66.toByte()
        bytes[3] = 0x55.toByte()
        bytes[4] = 0x44.toByte()
        bytes[5] = 0x33.toByte()
        bytes[6] = 0x22.toByte()
        bytes[7] = 0x11.toByte()
        bytes.clear()
    }

    @Test
    fun reduceToMaxNumDataPointsTestSmallTimeSeries() {
        val smallTimeSeriesBelowThreshold = listOf(
            NormalizedDataPoint(0.0, 1.0),
            NormalizedDataPoint(1.0, 1.0),
        )
        val smallTimeSeriesBelowThresholdResult = smallTimeSeriesBelowThreshold.reduceToMaxNumDataPointsForDots(12)
        assertContentEquals(smallTimeSeriesBelowThreshold, smallTimeSeriesBelowThresholdResult)

        val smallestAcceptableTimeSeries = listOf(
            NormalizedDataPoint(0.0, 1.0),
            NormalizedDataPoint(0.5, 0.0),
            NormalizedDataPoint(1.0, 1.0),
        )
        val smallestAcceptableTimeSeriesResult = smallestAcceptableTimeSeries.reduceToMaxNumDataPoints(12)
        assertContentEquals(smallestAcceptableTimeSeries, smallestAcceptableTimeSeriesResult)
    }

    @Test
    fun reduceToMaxNumDataPointsSparseTimeSeries() {
        // Check the result of reduction when the source data series is sparse,
        // that is, large sections of the timespan are unpopulated. In here,
        // between timestamps 0.0 and 0.7, no data points exist. For this
        // reason, even though the max num data points is set to 10, only
        // 5 are produced. The first and last one are special (they are
        // always copies of the first and last data points from the source
        // time series).

        val sparseSourceTimeSeries = listOf(
            NormalizedDataPoint(0.0, 0.0),
            NormalizedDataPoint(0.7, 0.04),
            NormalizedDataPoint(0.80, 0.0),
            NormalizedDataPoint(0.81, 0.0),
            NormalizedDataPoint(0.82, 0.0),
            NormalizedDataPoint(0.83, 0.0),
            NormalizedDataPoint(0.84, 0.0),
            NormalizedDataPoint(0.85, 0.9),
            NormalizedDataPoint(0.86, 0.0),
            NormalizedDataPoint(0.87, 0.0),
            NormalizedDataPoint(0.88, 0.0),
            NormalizedDataPoint(0.89, 0.0),
            NormalizedDataPoint(0.90, 0.0),
            NormalizedDataPoint(0.91, 0.0),
            NormalizedDataPoint(0.92, 0.0),
            NormalizedDataPoint(0.93, 0.0),
            NormalizedDataPoint(0.94, 0.0),
            NormalizedDataPoint(0.95, 0.87),
            NormalizedDataPoint(0.96, 0.0),
            NormalizedDataPoint(0.97, 0.0),
            NormalizedDataPoint(0.98, 0.0),
            NormalizedDataPoint(1.0, 0.0),
        )
        val sparseSourceTimeSeriesResult = sparseSourceTimeSeries.reduceToMaxNumDataPoints(10)
        assertEquals(5, sparseSourceTimeSeriesResult.size)
        assertEquals(sparseSourceTimeSeries.first(), sparseSourceTimeSeriesResult.first())
        assertEquals(sparseSourceTimeSeries.last(), sparseSourceTimeSeriesResult.last())
        // These are selected by the algorithm as the data points
        // that best represent the original time series in reduced
        // form. The one at timestamp 0.7 is selected because in
        // the bucket it represents, it is the only data point
        // present. The other two are selected by the algorithm
        // in the expected manner.
        assertEquals(NormalizedDataPoint(0.7, 0.04), sparseSourceTimeSeriesResult[1])
        assertEquals(NormalizedDataPoint(0.85, 0.9), sparseSourceTimeSeriesResult[2])
        assertEquals(NormalizedDataPoint(0.88, 0.0), sparseSourceTimeSeriesResult[3])
    }

    @Test
    fun reduceToMaxNumDataPointsFilledTimeSeries() {
        // The source time series is well populated this time - it is not sparse.
        // A full set of reduced time series data points is expected.
        val sourceTimeSeries = listOf(
            NormalizedDataPoint(0.0, 0.0),
            NormalizedDataPoint(0.1, 0.0),
            NormalizedDataPoint(0.2, 0.5),
            NormalizedDataPoint(0.3, 0.0),
            NormalizedDataPoint(0.4, 0.01),
            NormalizedDataPoint(0.5, 0.0),
            NormalizedDataPoint(0.6, 0.8),
            NormalizedDataPoint(0.7, 0.0),
            NormalizedDataPoint(0.8, 0.02),
            NormalizedDataPoint(0.9, 0.0),
        )
        val sourceTimeSeriesResult = sourceTimeSeries.reduceToMaxNumDataPoints(5)
        assertEquals(5, sourceTimeSeriesResult.size)
        assertEquals(sourceTimeSeries.first(), sourceTimeSeriesResult.first())
        assertEquals(sourceTimeSeries.last(), sourceTimeSeriesResult.last())
        assertEquals(NormalizedDataPoint(0.2, 0.5), sourceTimeSeriesResult[1])
        assertEquals(NormalizedDataPoint(0.6, 0.8), sourceTimeSeriesResult[2])
        assertEquals(NormalizedDataPoint(0.7, 0.0), sourceTimeSeriesResult[3])
    }

}