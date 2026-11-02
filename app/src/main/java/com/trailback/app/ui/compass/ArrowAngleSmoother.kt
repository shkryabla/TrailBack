package com.trailback.app.ui.compass
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * EMA-сглаживание угла стрелки на точку старта/направления — через sin/cos,
 * а не напрямую по градусам: иначе переход через 0°/360° давал бы скачок
 * почти на 360° вместо плавного поворота в одну сторону. Первый кадр после
 * reset() — мгновенная установка без сглаживания, чтобы стрелка не
 * "доплывала" из замороженной за время паузы позиции (холодный старт после
 * сна/разблокировки).
 *
 * НОВОЕ (предрелизный аудит): вынесена из CompassActivity.recomputeArrow() —
 * чистая математика без единой Android-зависимости (раньше жила как три
 * приватных var-поля прямо в Activity и не была протестирована ни разу).
 * Та же логика извлечения, что уже применена в проекте к HomeArrivalDetector.
 */
class ArrowAngleSmoother(
    private val oldWeight: Float = DEFAULT_OLD_WEIGHT,
    private val newWeight: Float = DEFAULT_NEW_WEIGHT
) {
    private var smoothedSin: Float? = null
    private var smoothedCos: Float? = null
    private var isFirstFrame = true

    /**
     * @param rawAngleDegrees сырой (несглаженный) угол в градусах — вызывающий
     * код сам отвечает за NaN-guard ДО вызова (см. CompassActivity.recomputeArrow,
     * там это уже сделано для bearing/declination/heading по отдельности).
     * @return сглаженный угол, нормализованный в [0, 360).
     */
    fun smooth(rawAngleDegrees: Float): Float {
        val rawRad = Math.toRadians(rawAngleDegrees.toDouble())
        val rawSin = sin(rawRad).toFloat()
        val rawCos = cos(rawRad).toFloat()
        val previousSin = smoothedSin
        val previousCos = smoothedCos
        val newSin: Float
        val newCos: Float
        if (isFirstFrame || previousSin == null || previousCos == null) {
            // Холодный старт — мгновенная установка без сглаживания.
            newSin = rawSin
            newCos = rawCos
            isFirstFrame = false
        } else {
            newSin = oldWeight * previousSin + newWeight * rawSin
            newCos = oldWeight * previousCos + newWeight * rawCos
        }
        smoothedSin = newSin
        smoothedCos = newCos
        val smoothedRad = atan2(newSin, newCos)
        val smoothedDeg = Math.toDegrees(smoothedRad.toDouble()).toFloat()
        return AzimuthNormalizer.normalize(smoothedDeg)
    }

    /** Сбрасывает фильтр — следующий smooth() снова будет холодным стартом
     * (мгновенная установка). Вызывать при onPause/onResume экрана, как это
     * уже делает CompassActivity. */
    fun reset() {
        smoothedSin = null
        smoothedCos = null
        isFirstFrame = true
    }

    companion object {
        const val DEFAULT_OLD_WEIGHT = 0.92f
        const val DEFAULT_NEW_WEIGHT = 0.08f
    }
}
