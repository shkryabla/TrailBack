package com.trailback.app.ui.compass
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
/**
 * Мини-компас в левом верхнем углу карты (см. решение по ТЗ):
 * только одна вращающаяся стрелка, указывающая на север — без циферблата,
 * делений, цифр и букв. По тапу открывает полноэкранный компас
 * (обработка тапа — в контейнере, см. MapActivity).
 *
 * ИЗМЕНЕНО: стрелка теперь классического вида магнитной стрелки —
 * ромб-игла из двух половин: красная (север) сверху, белая (юг) снизу,
 * резкая граница ровно по центру, БЕЗ кружка в центре (см. референс-
 * картинку, обсуждение). Каждая половина дополнительно разбита на левый и
 * правый плоский треугольник-фасет с чуть разным оттенком — простая
 * имитация объёма без градиентов, тот же low-poly приём, что уже
 * используется в drawable-иконках проекта (например, ic_user_position.xml).
 * Северная половина — фирменный оранжевый акцент приложения (#E65100, тот
 * же тон, что и accent_orange/кнопка Старт-Домой), южная — белый/светло-
 * серый; изначально пробовали классический красный/белый компаса, но
 * заменили красный на акцентный оранжевый для единообразия с остальным UI.
 *
 * Геометрия (4 треугольника) считается один раз в onSizeChanged(), а не на
 * каждый кадр вращения — canvas.rotate() в onDraw() крутит уже готовые
 * Path вокруг центра, пересчёта координат на каждый тик датчика (~50 Гц)
 * не происходит (тот же принцип, что в RotatingUserMarkerLayer/CompassView:
 * дорогая пересборка — только на редкое событие, не на каждый кадр).
 */
class MiniCompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    /** Текущий курс устройства, градусы 0..360. Стрелка поворачивается на -heading. */
    var headingDegrees: Float = 0f
        set(value) { field = value; invalidate() }

    private val northLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NORTH_DARK_COLOR
        style = Paint.Style.FILL
    }
    private val northRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NORTH_LIGHT_COLOR
        style = Paint.Style.FILL
    }
    private val southLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SOUTH_SHADE_COLOR
        style = Paint.Style.FILL
    }
    private val southRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SOUTH_LIGHT_COLOR
        style = Paint.Style.FILL
    }

    // Четыре треугольника-фасета иглы — пересобираются только в onSizeChanged.
    private val northLeftPath = Path()
    private val northRightPath = Path()
    private val southLeftPath = Path()
    private val southRightPath = Path()
    private var pivotX = 0f
    private var pivotY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildNeedlePaths(w, h)
    }

    /**
     * Ромб-игла: верхняя точка (север), нижняя точка (юг), левая/правая
     * точки "пояса" ровно на уровне центра — граница красный/белый проходит
     * точно по горизонтали через центр View, без промежуточного кружка.
     */
    private fun rebuildNeedlePaths(w: Int, h: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val length = minOf(w, h) / 2f * 0.9f
        val armWidth = length * 0.4f
        val northY = cy - length
        val southY = cy + length
        val leftX = cx - armWidth
        val rightX = cx + armWidth

        northLeftPath.reset()
        northLeftPath.moveTo(cx, northY)
        northLeftPath.lineTo(leftX, cy)
        northLeftPath.lineTo(cx, cy)
        northLeftPath.close()

        northRightPath.reset()
        northRightPath.moveTo(cx, northY)
        northRightPath.lineTo(cx, cy)
        northRightPath.lineTo(rightX, cy)
        northRightPath.close()

        southLeftPath.reset()
        southLeftPath.moveTo(cx, southY)
        southLeftPath.lineTo(leftX, cy)
        southLeftPath.lineTo(cx, cy)
        southLeftPath.close()

        southRightPath.reset()
        southRightPath.moveTo(cx, southY)
        southRightPath.lineTo(cx, cy)
        southRightPath.lineTo(rightX, cy)
        southRightPath.close()

        pivotX = cx
        pivotY = cy
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        canvas.save()
        canvas.rotate(-headingDegrees, pivotX, pivotY)
        canvas.drawPath(northLeftPath, northLeftPaint)
        canvas.drawPath(northRightPath, northRightPaint)
        canvas.drawPath(southLeftPath, southLeftPaint)
        canvas.drawPath(southRightPath, southRightPaint)
        canvas.restore()
    }

    companion object {
        // ИЗМЕНЕНО: север теперь фирменным оранжевым приложения (тот же
        // тон, что accent_orange/#E65100 — используется для кнопки
        // Старт/Домой, стрелки компаса и т.д.), а не классическим красным —
        // для единообразия с остальным UI вместо буквального следования
        // референсу магнитного компаса.
        private const val NORTH_DARK_COLOR = 0xFFBF360C.toInt()  // тёмный оттенок (тень левого фасета)
        private const val NORTH_LIGHT_COLOR = 0xFFE65100.toInt() // ровно accent_orange
        private const val SOUTH_SHADE_COLOR = 0xFFE0E0E0.toInt() // светло-серый (тень белой половины)
        private const val SOUTH_LIGHT_COLOR = 0xFFFFFFFF.toInt() // белый
    }
}
