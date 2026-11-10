package com.trailback.app.ui.menu
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.trailback.app.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Визуальная подсказка для экрана калибровки компаса (SettingsActivity,
 * SECTION_CALIBRATION) — рисует траекторию "восьмёрки" (символ ∞) и
 * анимированный значок телефона, движущийся по ней в такт с рекомендуемым
 * жестом калибровки магнитометра.
 *
 * У Mapsforge и у самого Android нет системного/библиотечного UI для
 * калибровки компаса (см. обсуждение) — это чистый прикладной виджет,
 * без какой-либо связи с сенсорами или картой, только иллюстрация жеста.
 *
 * Реализация — тот же подход, что уже используется в CompassView/
 * MiniCompassView (canvas + матрица поворота), без новых зависимостей:
 * Lottie/GIF-библиотеки не подключаем, офлайн-приложению лишний вес APK
 * ни к чему.
 *
 * Траектория строится параметрически (лемниската Джероно), а не через
 * android.graphics.Path.addCircle()+PathMeasure — два круга дают ДВА
 * отдельных контура пути, и PathMeasure не умеет плавно "перетекать"
 * между ними без ручного переключения nextContour() на стыке, что
 * усложняет бесшовную бесконечную анимацию. Прямая формула проще и
 * надёжнее для этой конкретной кривой.
 */
class CompassCalibrationHintView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val guidePathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF666666.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val guidePath = Path()
    private val phoneIcon = ContextCompat.getDrawable(context, R.drawable.ic_calibration_phone)

    // Половина "ширины" восьмёрки в пикселях — пересчитывается в onSizeChanged
    // под фактический размер View, чтобы траектория всегда вписывалась в
    // отведённое место независимо от размеров экрана/шрифта.
    private var amplitudePx = 0f
    private var centerX = 0f
    private var centerY = 0f

    /** Параметр вдоль кривой, 0..2π за один цикл анимации. */
    private var t = 0f

    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = ANIMATION_DURATION_MILLIS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        amplitudePx = minOf(w, h) * 0.32f
        rebuildGuidePath()
    }

    /** Лемниската Джероно: x = A·sin(t), y = A·sin(t)·cos(t) — классическая
     * параметрическая "восьмёрка", т.к. при t от 0 до 2π обходит обе доли
     * ровно один раз без разрывов. */
    private fun pointAt(angle: Float): Pair<Float, Float> {
        val x = centerX + amplitudePx * sin(angle)
        val y = centerY + amplitudePx * sin(angle) * cos(angle)
        return x to y
    }

    /** Касательная (для ориентации значка телефона по направлению движения):
     * x'(t) = A·cos(t), y'(t) = A·cos(2t). */
    private fun tangentAngleAt(angle: Float): Float {
        val dx = cos(angle)
        val dy = cos(2f * angle)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun rebuildGuidePath() {
        guidePath.reset()
        for (i in 0..GUIDE_PATH_SEGMENTS) {
            val angle = (i / GUIDE_PATH_SEGMENTS.toFloat()) * (Math.PI * 2).toFloat()
            val (x, y) = pointAt(angle)
            if (i == 0) guidePath.moveTo(x, y) else guidePath.lineTo(x, y)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitudePx <= 0f) return
        canvas.drawPath(guidePath, guidePathPaint)
        val (x, y) = pointAt(t)
        val angleDeg = tangentAngleAt(t)
        val iconSizePx = (minOf(width, height) * 0.22f).toInt()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angleDeg + 90f) // "верх" иконки телефона смотрит по направлению движения
        phoneIcon?.setBounds(-iconSizePx / 2, -iconSizePx / 2, iconSizePx / 2, iconSizePx / 2)
        phoneIcon?.draw(canvas)
        canvas.restore()
    }

    companion object {
        private const val ANIMATION_DURATION_MILLIS = 3200L
        // Число отрезков для аппроксимации гладкой кривой ломаной линией —
        // 96 достаточно, чтобы "восьмёрка" на глаз выглядела гладкой даже на
        // крупных экранах, но не нагружает onDraw() заметным числом точек.
        private const val GUIDE_PATH_SEGMENTS = 96
    }
}
