package com.trailback.app.ui.compass
import com.trailback.app.data.repository.NorthMode

/**
 * НОВОЕ (предрелизный аудит): вынесена из CompassActivity.recomputeArrow() —
 * чистая арифметика (NorthMode — обычный Kotlin-enum без Android-зависимостей,
 * AzimuthNormalizer уже был чистым) без единого теста, несмотря на то, что
 * именно здесь легко перепутать знак поправки на склонение или забыть
 * нормализацию при смене магнитный/истинный север.
 */
object ArrowBearingCalculator {
    /**
     * Считает угол стрелки относительно текущего курса устройства
     * (bearing - heading, нормализованный в [0, 360)) — с поправкой на
     * магнитное склонение, если выбран режим "магнитный север".
     *
     * [trueBearingDegrees] — Location.bearingTo() ВСЕГДА относительно
     * истинного севера (таков контракт Android API); если циферблат сейчас
     * в магнитном режиме, нужно привести азимут к той же системе отсчёта —
     * иначе стрелка и циферблат рассинхронизированы на величину склонения.
     *
     * NaN на любом входном параметре распространяется на результат (а не
     * бросает исключение) — вызывающий код сам решает, что делать с NaN
     * (см. CompassActivity.recomputeArrow — там это return из функции).
     */
    fun computeArrowAngle(
        trueBearingDegrees: Float,
        northMode: NorthMode,
        declinationDegrees: Float,
        headingDegrees: Float
    ): Float {
        val adjustedBearing = if (northMode == NorthMode.MAGNETIC) {
            trueBearingDegrees - declinationDegrees
        } else {
            trueBearingDegrees
        }
        if (adjustedBearing.isNaN() || headingDegrees.isNaN()) return Float.NaN
        val bearingDeg = AzimuthNormalizer.normalize(adjustedBearing)
        return AzimuthNormalizer.normalize(bearingDeg - headingDegrees)
    }
}
