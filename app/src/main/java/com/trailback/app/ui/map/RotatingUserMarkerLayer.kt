package com.trailback.app.ui.map
import android.content.Context
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.core.content.ContextCompat
import org.mapsforge.core.graphics.Canvas as MapsforgeCanvas
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.model.DisplayModel

/**
 * Слой значка положения пользователя с поворотом стрелки по курсу.
 *
 * В отличие от прежнего подхода (MapController.createRotatedMarkerBitmap +
 * Marker.setBitmap), здесь исходный битмап рисуется ОДИН РАЗ при создании
 * слоя и больше никогда не пересоздаётся. Поворот применяется прямо к
 * android.graphics.Canvas на этапе draw() — та же техника, что уже
 * используется в MiniCompassView/CompassView для стрелок компаса.
 *
 * ВАЖНО: позиция и угол поворота обновляются НЕЗАВИСИМО друг от друга
 * (updatePosition/updateRotation, а не единый updatePositionAndRotation) —
 * это устраняет "рывки" поворота, которые были при первой версии слоя.
 * Причина рывков: связка position+rotation обновлялась только при новом
 * GPS-фиксе (см. MapController.updateUserPosition, вызывается из
 * MapActivity.onLocationUpdated), а фиксы приходят редко — раз в 5-10с
 * (см. TrackingService.RECORDING_INTERVAL_MILLIS/RETURNING_INTERVAL_MILLIS,
 * MapActivity.foregroundLocationClient). Курс же от датчика приходит
 * гораздо чаще (~50 Гц, см. CompassSensorManager.SENSOR_DELAY_MICROS), но
 * раньше не мог "долететь" до перерисовки стрелки раньше следующего
 * GPS-фикса. Теперь updateRotation() дёргается из того же heading-коллектора,
 * что обновляет MiniCompassView (см. MapActivity.onResume) — стрелка на
 * карте вращается с той же частотой и так же плавно, как мини-компас.
 *
 * Касается ТОЛЬКО значка текущей позиции пользователя. Маркер цели
 * "взятия направления" (ic_navigation_target_marker) и маркер точки входа
 * (ic_entry_point_marker) — статичные пины без направления, поворот им не
 * нужен и они по-прежнему рисуются обычным Marker (см. updateNavigationTargetMarker/
 * updateEntryPointMarker в MapController) — эта фича не трогается.
 *
 * НЕ РЕШАЕТ: кратковременное "зумирование вместе с картой" во время
 * pinch/double-tap жеста — это отдельный механизм Mapsforge (масштабирование
 * всего уже отрисованного кадра целиком для мгновенного отклика, пока новые
 * тайлы не дорендерились под новый zoom), затрагивает ВСЕ layers одинаково,
 * включая штатный mapsforge Marker. Частота requestRedraw() тут не влияет —
 * фикс потребовал бы выноса маркера в отдельный View-оверлей поверх MapView.
 *
 * РИСК: AndroidGraphicFactory.getCanvas() — предположительное имя метода
 * для получения нативного android.graphics.Canvas из mapsforge-обёртки в
 * версии 0.20.0. Если сборка не пройдёт именно на этой строке — единственное
 * место для правки под точную сигнатуру установленной версии
 * org.mapsforge:mapsforge-map-android (тот же класс риска, что и с
 * MapView.mapViewProjection.fromPixels() в MapController.screenToLatLong).
 */
class RotatingUserMarkerLayer(
    private val displayModel: DisplayModel,
    context: Context,
    drawableRes: Int,
    private val sizePx: Int
) : Layer() {

    @Volatile private var position: LatLong? = null
    @Volatile private var rotationDegrees: Float = 0f

    /** true, если уже была хотя бы одна геопозиция — используется вызывающим
     * кодом (MapController.updateUserPosition), чтобы понять, что это
     * первый фикс и карту нужно принудительно центрировать (иначе она может
     * стартовать на "null island" (0,0), как это было со старым Marker). */
    val hasPosition: Boolean
        get() = position != null

    // Статичный (неповёрнутый) битмап — рисуется один раз при создании слоя,
    // живёт вместе с ним, пересоздаётся только при пересоздании MapView
    // (offline/online переключение — см. MapController.setupMap).
    private val sourceBitmap: AndroidBitmap = run {
        val drawable = ContextCompat.getDrawable(context, drawableRes)!!
        val bmp = AndroidBitmap.createBitmap(sizePx, sizePx, AndroidBitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        bmp
    }

    override fun draw(boundingBox: BoundingBox, zoomLevel: Byte, canvas: MapsforgeCanvas, topLeftPoint: Point) {
        val pos = position ?: return
        // Та же формула перевода гео-координат в экранные пиксели, что
        // внутри себя использует штатный Marker.draw().
        val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)
        val pixelX = (MercatorProjection.longitudeToPixelX(pos.longitude, mapSize) - topLeftPoint.x).toFloat()
        val pixelY = (MercatorProjection.latitudeToPixelY(pos.latitude, mapSize) - topLeftPoint.y).toFloat()

        val androidCanvas = AndroidGraphicFactory.getCanvas(canvas)
        androidCanvas.save()
        androidCanvas.rotate(rotationDegrees, pixelX, pixelY)
        androidCanvas.drawBitmap(sourceBitmap, pixelX - sizePx / 2f, pixelY - sizePx / 2f, null)
        androidCanvas.restore()
    }

    /** Вызывается на каждое обновление геопозиции (GPS-фикс, редко). */
    fun updatePosition(latLong: LatLong) {
        position = latLong
        requestRedraw()
    }

    /** Вызывается на каждый тик датчика курса (часто, ~50 Гц) — независимо
     * от updatePosition(), чтобы вращение не было привязано к редким
     * GPS-фиксам. */
    fun updateRotation(headingDegrees: Float) {
        rotationDegrees = headingDegrees
        requestRedraw()
    }

    fun destroy() {
        sourceBitmap.recycle()
    }
}
