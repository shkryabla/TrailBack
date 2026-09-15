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
 * ромб-игла из двух половин: оранжевая (север) сверху, тёмно-серая (юг)
 * снизу, резкая граница ровно по центру, БЕЗ кружка в центре (см.
 * референс-картинку, обсуждение). Каждая половина дополнительно разбита на
 * левый и правый плоский треугольник-фасет с чуть разным оттенком —
 * простая имитация объёма без градиентов, тот же low-poly приём, что уже
 * используется в drawable-иконках проекта (например, ic_user_position.xml).
 *
 * ИЗМЕНЕНО ЕЩЁ РАЗ (после проверки на реальном тёмном фоне мини-компаса):
 * изначально юг был белым — на тёмном круглом фоне (panel_dark, почти
 * чёрный) белый даёт максимально возможный контраст и визуально "перетягивал"
 * внимание на себя, хотя смыслово главный — север. Решение (как у настоящих
 * магнитных компасов — там юг обычно тёмная воронёная сталь, а не белый):
 * 1) юг сделан тёмно-серым, а не белым — перестаёт конкурировать за
 *    внимание с тёмным фоном;
 * 2) север сделан ярче (#FF6F00 вместо #E65100) — контрастнее на тёмном
 *    фоне, читается как главный элемент;
 * 3) добавлена тонкая светло-серая обводка по ВНЕШНЕМУ силуэту иглы
 *    (outlinePath/outlinePaint) — чёткость формы на тёмном фоне без
 *    дополнительного цветового шума (обводка нейтральная, не конкурирует
 *    ни с севером, ни с югом).
 *
 * Геометрия (4 треугольника + внешний контур) считается один раз в
 * onSizeChanged(), а не на каждый кадр вращения — canvas.rotate() в
 * onDraw() крутит уже готовые Path вокруг центра, пересчёта координат на
 * каждый тик датчика (~50 Гц) не происходит (тот же принцип, что в
 * RotatingUserMarkerLayer/CompassView: дорогая пересборка — только на
 * редкое событие, не на каждый кадр).
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
    // НОВОЕ: тонкая обводка внешнего силуэта иглы (не отдельных фасетов —
    // иначе были бы лишние линии на стыках север/юг слева-справа и по
    // центральному "ребру") — рисуется поверх заливки последним штрихом.
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OUTLINE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = OUTLINE_STROKE_WIDTH_PX
        strokeJoin = Paint.Join.ROUND
    }

    // Четыре треугольника-фасета иглы + внешний контур — пересобираются
    // только в onSizeChanged.
    private val northLeftPath = Path()
    private val northRightPath = Path()
    private val southLeftPath = Path()
    private val southRightPath = Path()
    private val outlinePath = Path()
    private var pivotX = 0f
    private var pivotY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildNeedlePaths(w, h)
    }

    /**
     * Ромб-игла: верхняя точка (север), нижняя точка (юг), левая/правая
     * точки "пояса" ровно на уровне центра — граница север/юг проходит
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

        // Внешний силуэт — просто ромб по четырём крайним точкам, без
        // центрального ребра (оно остаётся только в заливке фасетов выше).
        outlinePath.reset()
        outlinePath.moveTo(cx, northY)
        outlinePath.lineTo(rightX, cy)
        outlinePath.lineTo(cx, southY)
        outlinePath.lineTo(leftX, cy)
        outlinePath.close()

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
        canvas.drawPath(outlinePath, outlinePaint)
        canvas.restore()
    }

    companion object {
        // Север — фирменный оранжевый приложения, но ЯРЧЕ базового
        // accent_orange (#E65100) — на тёмном фоне мини-компаса обычный
        // accent_orange проигрывал по контрасту белой/светлой половине юга
        // (см. обсуждение и комментарий класса выше).
        private const val NORTH_DARK_COLOR = 0xFFBF360C.toInt()  // тень левого фасета (ближе к базовому accent_orange)
        private const val NORTH_LIGHT_COLOR = 0xFFFF6F00.toInt() // яркий оранжевый — основной тон севера
        // Юг — тёмно-серый (не белый!) — как воронёная сталь у настоящих
        // компасов, чтобы не перетягивать внимание с севера на тёмном фоне.
        private const val SOUTH_SHADE_COLOR = 0xFF4A4A4A.toInt() // тень левого фасета
        private const val SOUTH_LIGHT_COLOR = 0xFF6B6B6B.toInt() // основной тон юга
        // Тонкая нейтральная обводка внешнего силуэта — чёткость формы на
        // тёмном фоне, не конкурирует цветом ни с севером, ни с югом.
        private const val OUTLINE_COLOR = 0xFFBDBDBD.toInt()
        private const val OUTLINE_STROKE_WIDTH_PX = 2f
    }
}
