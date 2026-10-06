package com.trailback.app.ui.compass
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.trailback.app.R
/**
 * Полноэкранный компас (п.6.2 ТЗ), реализующий подтверждённую механику:
 * - вращающийся диск (градусы + буквы N/E/S/W или С/Ю/З/В) — угол = -heading;
 * - фиксированный треугольник курса статичен сверху в обоих режимах;
 * - в обычном режиме в центре текст курса+буквы, в режиме "Домой" центр пуст;
 * - красная стрелка на точку старта — только в режиме "Домой", независимый угол
 *   (bearingToHome - heading), исчезает по достижении точки.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    // DIRECTION — НОВОЕ: "взятие направления" (см. решение по ТЗ). Рисуется
    // тем же drawHomeArrow(), что и RETURNING, отличие только в цвете
    // стрелки (arrowAccentColor) — сам режим "Домой" не меняется.
    enum class Mode { NORMAL, RETURNING, DIRECTION }
    var mode: Mode = Mode.NORMAL
        set(value) { field = value; invalidate() }
    /** Текущий курс устройства, градусы 0..360. */
    var headingDegrees: Float = 0f
        set(value) { field = value; invalidate() }
    /**
     * Финальный угол поворота стрелки на экране (уже с учётом курса и
     * повторного сглаживания перехода через 0°/360° — см. CompassActivity).
     * Актуально только в режиме RETURNING.
     */
    var arrowScreenAngleDegrees: Float = 0f
        set(value) { field = value; invalidate() }
    // НОВОЕ: цвет стрелки настраиваемый — оранжевый по умолчанию ("Домой"),
    // фиолетовый для DIRECTION ("взятие направления"), см. CompassActivity.
    var arrowAccentColor: Int = ACCENT_COLOR
        set(value) { field = value; homeArrowPaint.color = value; invalidate() }
    private val isRussian = context.resources.configuration.locales[0].language == "ru"
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = 3f
    }
    // Крупные засечки каждые 30° — толще мелких (см. решение по ТЗ).
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = 5f
    }
    private val degreeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
        textSize = 32f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 85f
    }
    private val headingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 125f
        isFakeBoldText = true
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
        textSize = 65f
    }
    private val courseTrianglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // НОВОЕ (п.6 правок, по референсу): вертикальная черта над треугольником
    // указателя курса — на референсе указатель составной (треугольник + линия),
    // а не голый треугольник, как было у нас.
    private val courseTriangleStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    // ИЗМЕНЕНО: по второму референсу (скрин с буквой N) акцент на 0° — это
    // НЕ окрашенная засечка и НЕ окрашенная буква (так было криво — см.
    // "красная буква С"), а отдельный маленький треугольник-маркер рядом
    // с засечкой 0°. Засечка и буква на 0° теперь рисуются обычным цветом
    // наравне со всеми остальными (accentTickPaint/accentLabelPaint убраны
    // из использования в drawDial), а этот Paint — только для маркера.
    private val accentMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_COLOR
        style = Paint.Style.FILL
    }
    private val homeArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_COLOR
        style = Paint.Style.FILL
    }
    private val homeArrowShadedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 139, 0, 0)
        style = Paint.Style.FILL
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f * 0.85f
        canvas.save()
        canvas.rotate(-headingDegrees, cx, cy)
        drawDial(canvas, cx, cy, radius)
        canvas.restore()
        drawFixedCourseTriangle(canvas, cx, cy, radius)
        if (mode == Mode.NORMAL) {
            drawCenterHeadingText(canvas, cx, cy)
        } else {
            drawHomeArrow(canvas, cx, cy, radius)
        }
    }
    private fun drawDial(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, dialPaint)
        // ИЗМЕНЕНО: засечки раньше шли каждые 5°, теперь каждые 2° (по
        // просьбе) — 30 всё так же кратно 2, крупные засечки (каждые 30°)
        // не съезжают. Критично: шаг 2° означает четыре "обычных" тика между
        // соседними крупными — на 5°-сетке было по пять, но важна не
        // кратность пяти, а то, что 30 делится на 2 нацело (делится).
        for (deg in 0 until 360 step 2) {
            val isMajor = deg % 30 == 0
            val angleRad = Math.toRadians((deg - 90).toDouble())
            val outer = radius
            val inner = if (isMajor) radius - 30f else radius - 15f
            val tickLinePaint = if (isMajor) majorTickPaint else tickPaint
            val x1 = cx + (outer * Math.cos(angleRad)).toFloat()
            val y1 = cy + (outer * Math.sin(angleRad)).toFloat()
            val x2 = cx + (inner * Math.cos(angleRad)).toFloat()
            val y2 = cy + (inner * Math.sin(angleRad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickLinePaint)
            // ИЗМЕНЕНО: цифра рисуется на КАЖДОЙ крупной отметке, включая
            // 0/90/180/270 — раньше там цифра пропускалась в пользу буквы
            // стороны света; теперь (по просьбе) дублируем — и буква, и
            // цифра одновременно, как и на остальных крупных засечках.
            if (isMajor) {
                val labelRadius = radius - 55f
                val lx = cx + (labelRadius * Math.cos(angleRad)).toFloat()
                val ly = cy + (labelRadius * Math.sin(angleRad)).toFloat() + 11f
                canvas.save()
                canvas.rotate(headingDegrees, lx, ly) // цифры остаются читаемыми
                canvas.drawText(deg.toString(), lx, ly, degreeLabelPaint)
                canvas.restore()
            }
        }
        // Буквы сторон света — ближе к центру, чем засечки/цифры (не наезжают).
        // ИЗМЕНЕНО: акцентная окраска буквы на 0° убрана — все четыре буквы
        // равноправны (см. комментарий у accentMarkerPaint выше), акцент
        // теперь — отдельный маленький треугольник-маркер (drawZeroAccentMarker).
        val labels = if (isRussian) listOf("С", "В", "Ю", "З") else listOf("N", "E", "S", "W")
        val labelAngles = listOf(0, 90, 180, 270)
        val cardinalLabelRadius = radius * 0.55f
        for (i in labelAngles.indices) {
            val angleRad = Math.toRadians((labelAngles[i] - 90).toDouble())
            val x = cx + (cardinalLabelRadius * Math.cos(angleRad)).toFloat()
            val y = cy + (cardinalLabelRadius * Math.sin(angleRad)).toFloat() + 14f
            canvas.save()
            canvas.rotate(headingDegrees, x, y) // буквы остаются читаемыми (не переворачиваются)
            canvas.drawText(labels[i], x, y, labelPaint)
            canvas.restore()
        }
        drawZeroAccentMarker(canvas, cx, cy, radius)
    }
    /**
     * НОВОЕ: маленький акцентный треугольник у засечки 0° — по второму
     * референсу (скрин с буквой N), где ровно такой же компактный красный
     * треугольник сидит между кольцом шкалы и буквой "N", а не сама засечка
     * или буква окрашены. У нас используется фирменный оранжевый вместо
     * красного — проект уже унифицирован на этот акцентный цвет (см.
     * ACCENT_COLOR, используется в стрелке "Домой" и т.д.).
     *
     * Рисуется ВНУТРИ того же повёрнутого (-headingDegrees) канваса, что и
     * остальная шкала в drawDial — поэтому, как и засечки, вращается вместе
     * с диском и не требует встречного поворота (в отличие от букв/цифр,
     * которым встречный поворот нужен, чтобы оставаться читаемыми).
     */
    private fun drawZeroAccentMarker(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val outer = radius - 32f
        val inner = radius - 48f
        val halfWidth = 8f
        val path = Path().apply {
            moveTo(cx, cy - outer)
            lineTo(cx - halfWidth, cy - inner)
            lineTo(cx + halfWidth, cy - inner)
            close()
        }
        canvas.drawPath(path, accentMarkerPaint)
    }
    private fun drawFixedCourseTriangle(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // ИЗМЕНЕНО: геометрия подогнана точно под референс (скрин с буквой N) —
        // треугольник у́же (halfWidth 20->14) и компактнее по высоте, апекс
        // снаружи кольца, основание ровно на кромке (было — на 25px внутрь,
        // слишком глубоко перекрывало зону засечек). Стебель — тонкая черта
        // от основания треугольника вниз ровно до глубины крупных засечек
        // (radius-30, та же величина, что и inner у isMajor в drawDial),
        // чтобы визуально продолжать треугольник до кольца шкалы, как на
        // референсе, а не уходить глубже или мельче этой отметки.
        val apexY = cy - radius - 16f
        val baseY = cy - radius + 2f
        val halfWidth = 14f
        canvas.drawLine(cx, baseY, cx, cy - radius + 30f, courseTriangleStemPaint)
        val path = Path().apply {
            moveTo(cx, apexY)
            lineTo(cx - halfWidth, baseY)
            lineTo(cx + halfWidth, baseY)
            close()
        }
        canvas.drawPath(path, courseTrianglePaint)
    }
    private fun drawCenterHeadingText(canvas: Canvas, cx: Float, cy: Float) {
        val roundedHeading = ((headingDegrees + 0.5f).toInt()) % 360
        canvas.drawText(roundedHeading.toString(), cx, cy, headingTextPaint)
        canvas.drawText(compassPointLabel(headingDegrees), cx, cy + 80f, subLabelPaint)
    }
    private fun drawHomeArrow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.save()
        canvas.rotate(arrowScreenAngleDegrees, cx, cy)
        val length = radius * 0.75f
        val width = radius * 0.35f
        val path = Path().apply {
            moveTo(cx, cy - length)
            lineTo(cx - width, cy + length * 0.4f)
            lineTo(cx, cy + length * 0.15f)
            lineTo(cx + width, cy + length * 0.4f)
            close()
        }
        canvas.drawPath(path, homeArrowShadedPaint)
        canvas.drawPath(path, homeArrowPaint)
        canvas.restore()
    }
    private fun compassPointLabel(heading: Float): String {
        val pointsRu = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
        val pointsEn = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val points = if (isRussian) pointsRu else pointsEn
        val index = (((heading + 22.5f) / 45f).toInt()) % 8
        return points[index]
    }
    companion object {
        private const val ACCENT_COLOR = 0xFFE65100.toInt()
    }
}