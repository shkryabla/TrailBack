package com.trailback.app.ui.map
import android.content.Context
import android.graphics.Paint as AndroidPaint
import androidx.core.content.ContextCompat
import com.trailback.app.R
import org.mapsforge.core.graphics.Canvas as MapsforgeCanvas
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Point
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.model.DisplayModel
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow

/**
 * Квадратная сетка привязки к масштабу — показывается ТОЛЬКО на заглушке
 * (см. MapController.showEmptyMap), когда офлайн-карта не загружена: сама
 * заглушка даёт курсор и зум, но без вообще каких-либо ориентиров расстояния
 * на глаз. Сетка — не декоративный узор поверх экрана, а слой, рисующий
 * линии по РЕАЛЬНЫМ гео-координатам (тот же приём, что и у
 * RotatingUserMarkerLayer) — при панорамировании/зуме Mapsforge сам
 * вызывает draw() заново на каждый кадр, свой таймер/слушатель не нужен.
 *
 * Шаг сетки — не фиксированный список порогов (хрупко, нужно вручную
 * подгонять под конкретные зум-уровни), а стандартный картографический ряд
 * "1-2-5" (1, 2, 5, 10, 20, 50, 100, 200, 500 м, 1, 2, 5 км...) — берётся
 * наименьший шаг, при котором ячейка на экране не мельче CELL_TARGET_DP.
 * Подробности и обоснование — см. обсуждение с пользователем.
 *
 * Для перевода "метров в пиксели" берётся стандартная формула разрешения
 * Web Mercator на широте центра экрана (та же проекция, в которой уже
 * работает весь Mapsforge-рендеринг в проекте) — на масштабах одного
 * экрана и широтах средней полосы (целевая аудитория — грибники/туристы)
 * локальное искажение по сторонам экрана пренебрежимо мало, честная
 * геодезия для каждой отдельной ячейки не нужна.
 */
class ScaleGridLayer(
    private val displayModel: DisplayModel,
    context: Context
) : Layer() {

    private val density = context.resources.displayMetrics.density
    // НОВОЕ: цвет заглушки (фон под сеткой) — раньше задавался через
    // newMapView.setBackgroundColor() на самом Android View, но MapView из
    // Mapsforge рендерит слои в свой внутренний буфер и перед этим сам
    // закрашивает канвас, не считаясь с обычным View-фоном — тот был
    // попросту не виден. Теперь заливка — первое, что рисует этот слой, тем
    // же проверенно рабочим путём (androidCanvas), каким уже рисуются линии
    // сетки и подпись — единый источник правды, конфликтов по порядку
    // отрисовки с подписью/линиями нет, т.к. заливка всегда идёт ДО них в
    // одном и том же draw().
    private val backgroundColor = ContextCompat.getColor(context, R.color.info_panel_bg)
    private val gridPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scale_grid_line)
        strokeWidth = 1f * density
        style = AndroidPaint.Style.STROKE
    }
    // ИЗМЕНЕНО: цвет подписи был info_panel_label (тёмный, #2A2A2A —
    // рассчитан на светлый фон инфопанели) — по просьбе теперь тот же
    // цвет, что и у линий сетки (scale_grid_line), подпись и линии читаются
    // как единый, согласованный по цвету элемент.
    private val labelPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scale_grid_line)
        textSize = 14f * density
        textAlign = AndroidPaint.Align.LEFT
    }
    private val labelMarginPx = 12f * density
    private val isRussian = context.resources.configuration.locales[0].language == "ru"

    override fun draw(boundingBox: BoundingBox, zoomLevel: Byte, canvas: MapsforgeCanvas, topLeftPoint: Point) {
        val androidCanvas = AndroidGraphicFactory.getCanvas(canvas)
        val canvasWidth = androidCanvas.width.toFloat()
        val canvasHeight = androidCanvas.height.toFloat()
        if (canvasWidth <= 0f || canvasHeight <= 0f) return

        // Заливка фона — ПЕРВЫМ действием, до линий и подписи (иначе залила
        // бы их сверху и "стёрла" визуально — см. обсуждение).
        androidCanvas.drawColor(backgroundColor)

        val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)
        // ИСПРАВЛЕНО (диагональный снос сетки при панорамировании): широта
        // округляется до целого градуса, а не берётся "как есть" на каждый
        // кадр. При чистом вертикальном жесте boundingBox.minLatitude/
        // maxLatitude непрерывно меняются — без округления metersPerPixel
        // (а с ним и pixelsPerCell) дрейфовал бы на каждый кадр, а ниже это
        // же значение используется как ЗНАМЕНАТЕЛЬ квантования (floor(...)),
        // из-за чего вертикальные линии "гуляли" по горизонтали даже при
        // чисто вертикальном жесте без реального смещения по долготе —
        // отсюда и диагональный снос. Один градус широты — это ~111 км,
        // внутри одного жеста панорамирования эта граница не пересекается,
        // так что pixelsPerCell становится стабильным на весь жест (меняется
        // только на зуме или при реальном перемещении на сотни км), а
        // точность самой величины шага в метрах теряется пренебрежимо мало.
        val centerLat = Math.round((boundingBox.minLatitude + boundingBox.maxLatitude) / 2.0).toDouble()
        val metersPerPixel = cos(Math.toRadians(centerLat)) * EARTH_CIRCUMFERENCE_METERS / mapSize
        if (metersPerPixel <= 0.0 || metersPerPixel.isNaN()) return

        val targetPx = CELL_TARGET_DP * density
        val stepMeters = chooseStepMeters(metersPerPixel, targetPx)
        val pixelsPerCell = (stepMeters / metersPerPixel).toFloat()
        if (pixelsPerCell <= 0f || pixelsPerCell.isNaN()) return

        var x = floor(topLeftPoint.x / pixelsPerCell) * pixelsPerCell
        while (x < topLeftPoint.x + canvasWidth) {
            val screenX = (x - topLeftPoint.x).toFloat()
            androidCanvas.drawLine(screenX, 0f, screenX, canvasHeight, gridPaint)
            x += pixelsPerCell
        }
        var y = floor(topLeftPoint.y / pixelsPerCell) * pixelsPerCell
        while (y < topLeftPoint.y + canvasHeight) {
            val screenY = (y - topLeftPoint.y).toFloat()
            androidCanvas.drawLine(0f, screenY, canvasWidth, screenY, gridPaint)
            y += pixelsPerCell
        }

        val label = formatGridLabel(stepMeters)
        androidCanvas.drawText(label, labelMarginPx, canvasHeight - labelMarginPx, labelPaint)
    }

    /** Наименьший шаг из ряда 1-2-5 (на все степени десяти), при котором
     * ячейка на экране не мельче targetPx. */
    private fun chooseStepMeters(metersPerPixel: Double, targetPx: Float): Double {
        val sequence = doubleArrayOf(1.0, 2.0, 5.0)
        var magnitude = 1.0
        while (true) {
            for (multiplier in sequence) {
                val candidate = multiplier * magnitude
                if (candidate / metersPerPixel >= targetPx) return candidate
            }
            magnitude *= 10.0
        }
    }

    private fun formatGridLabel(stepMeters: Double): String {
        val distanceText = if (stepMeters >= 1000.0) {
            val km = stepMeters / 1000.0
            val kmText = if (km == km.toInt().toDouble()) km.toInt().toString() else "%.1f".format(km)
            "$kmText км"
        } else {
            "${stepMeters.toInt()} м"
        }
        val prefix = if (isRussian) "Сетка" else "Grid"
        return "$prefix $distanceText"
    }

    companion object {
        // Стандартная длина экватора WGS84/Web Mercator в метрах — та же
        // проекция, в которой уже работает весь Mapsforge-рендеринг здесь.
        private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686
        // Целевой размер ячейки на экране — согласовано с пользователем.
        private const val CELL_TARGET_DP = 90f
    }
}
