package com.trailback.app.ui.compass
import com.trailback.app.data.repository.NorthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * НОВОЕ (предрелизный аудит): ArrowBearingCalculator вынесен из
 * CompassActivity.recomputeArrow() — именно здесь проще всего перепутать
 * знак поправки на магнитное склонение или забыть нормализацию при смене
 * режима истинный/магнитный север, а тестов на это не было вообще.
 */
class ArrowBearingCalculatorTest {
    @Test
    fun `true north mode ignores declination entirely`() {
        val result = ArrowBearingCalculator.computeArrowAngle(
            trueBearingDegrees = 90f,
            northMode = NorthMode.TRUE,
            declinationDegrees = 15f, // не должно повлиять на результат в режиме TRUE
            headingDegrees = 0f
        )
        assertEquals(90f, result, 0.01f)
    }
    @Test
    fun `magnetic north mode subtracts declination from bearing`() {
        val result = ArrowBearingCalculator.computeArrowAngle(
            trueBearingDegrees = 90f,
            northMode = NorthMode.MAGNETIC,
            declinationDegrees = 15f,
            headingDegrees = 0f
        )
        assertEquals(75f, result, 0.01f) // 90 - 15
    }
    @Test
    fun `result subtracts current device heading`() {
        val result = ArrowBearingCalculator.computeArrowAngle(
            trueBearingDegrees = 90f,
            northMode = NorthMode.TRUE,
            declinationDegrees = 0f,
            headingDegrees = 30f
        )
        assertEquals(60f, result, 0.01f) // 90 - 30
    }
    @Test
    fun `result wraps negative values into 0-360 range`() {
        val result = ArrowBearingCalculator.computeArrowAngle(
            trueBearingDegrees = 10f,
            northMode = NorthMode.TRUE,
            declinationDegrees = 0f,
            headingDegrees = 350f // 10 - 350 = -340 -> должно стать 20
        )
        assertEquals(20f, result, 0.01f)
    }
    @Test
    fun `negative declination (west) adds to bearing instead of subtracting`() {
        val result = ArrowBearingCalculator.computeArrowAngle(
            trueBearingDegrees = 90f,
            northMode = NorthMode.MAGNETIC,
            declinationDegrees = -10f, // западное склонение — отрицательное значение
            headingDegrees = 0f
        )
        assertEquals(100f, result, 0.01f) // 90 - (-10) = 100
    }
    @Test
    fun `NaN bearing propagates to NaN result`() {
        val result = ArrowBearingCalculator.computeArrowAngle(
            trueBearingDegrees = Float.NaN,
            northMode = NorthMode.TRUE,
            declinationDegrees = 0f,
            headingDegrees = 0f
        )
        assertTrue(result.isNaN())
    }
    @Test
    fun `NaN heading propagates to NaN result`() {
        val result = ArrowBearingCalculator.computeArrowAngle(
            trueBearingDegrees = 90f,
            northMode = NorthMode.TRUE,
            declinationDegrees = 0f,
            headingDegrees = Float.NaN
        )
        assertTrue(result.isNaN())
    }
    @Test
    fun `NaN declination in magnetic mode propagates to NaN result`() {
        val result = ArrowBearingCalculator.computeArrowAngle(
            trueBearingDegrees = 90f,
            northMode = NorthMode.MAGNETIC,
            declinationDegrees = Float.NaN,
            headingDegrees = 0f
        )
        assertTrue(result.isNaN())
    }
    @Test
    fun `NaN declination in true mode is ignored - does not propagate`() {
        // В режиме TRUE declinationDegrees вообще не участвует в расчёте —
        // NaN в неиспользуемом параметре не должен портить результат.
        val result = ArrowBearingCalculator.computeArrowAngle(
            trueBearingDegrees = 90f,
            northMode = NorthMode.TRUE,
            declinationDegrees = Float.NaN,
            headingDegrees = 0f
        )
        assertEquals(90f, result, 0.01f)
    }
}
