package com.trailback.app.ui.compass
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import com.trailback.app.data.repository.NorthMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ЕДИНЫЙ на всё приложение экземпляр (создаётся в TrailBackApp, а не в
 * каждой Activity отдельно — см. решение по ТЗ). Раньше и CompassActivity,
 * и MapActivity создавали СВОИ независимые CompassSensorManager и каждый
 * сам регистрировал/дерегистрировал датчик в своих onResume/onPause. Из-за
 * этого при КАЖДОМ переключении карта<->компас происходил полный цикл
 * unregister->register — то есть повторный "разогрев" фьюжн-алгоритма
 * TYPE_ROTATION_VECTOR (то же явление, что при реальной блокировке экрана,
 * но срабатывающее гораздо чаще, чем нужно, и без всякой пользы).
 *
 * Вместо жёсткого start()/stop() — подсчёт активных потребителей:
 * acquire()/release(). Датчик реально включается/выключается только когда
 * счётчик переходит 0<->1, т.е. когда ВСЕ экраны, использующие компас,
 * одновременно ушли из foreground (равносильно полному сворачиванию
 * приложения). Переход между самими экранами компас теперь не трогает —
 * фьюжн остаётся "тёплым" непрерывно, одной из причин ложного направления
 * после блокировки/переключения экранов стало меньше.
 *
 * Курс отдаётся через StateFlow, а не разовый колбэк в конструкторе — так
 * несколько экранов независимо подписываются на один и тот же поток данных.
 *
 * remapCoordinateSystem больше не зависит от конкретной Activity: обе
 * Activity, использующие компас (CompassActivity, MapActivity), теперь
 * жёстко зафиксированы в портретной ориентации (см. AndroidManifest.xml),
 * поэтому подстановка осей всегда тождественная и Activity-контекст (со
 * связанными рисками неверного/устаревшего значения rotation в момент
 * разблокировки) для этого больше не нужен — как раз это было кандидатом
 * №3 на баг с неверным севером, который мы разбирали.
 */
class CompassSensorManager(context: Context) : SensorEventListener {
    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var magneticDeclination = 0f
    var northMode: NorthMode = NorthMode.TRUE

    private val _heading = MutableStateFlow(0f)
    val heading: StateFlow<Float> = _heading.asStateFlow()

    /** Нужно снаружи (CompassActivity) для приведения GPS-азимута к той же системе отсчёта. */
    val currentDeclination: Float
        get() = magneticDeclination

    fun updateLocationForDeclination(location: Location) {
        val field = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis()
        )
        magneticDeclination = field.declination
    }

    // НОВОЕ: подсчёт активных потребителей — см. комментарий класса.
    private var activeUsers = 0

    fun acquire() {
        activeUsers++
        if (activeUsers == 1) {
            sensorManager.registerListener(this, rotationVectorSensor, SENSOR_DELAY_MICROS)
        }
    }

    fun release() {
        if (activeUsers == 0) return
        activeUsers--
        if (activeUsers == 0) {
            sensorManager.unregisterListener(this)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        // Защита от битого/пустого пакета (те же случаи, что были и раньше:
        // отдельные чипсеты присылают NaN на первом кадре после сна).
        if (event.values.isEmpty() || event.values.any { it.isNaN() }) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // НОВОЕ: обе Activity, использующие компас, жёстко в портрете —
        // подстановка осей всегда тождественная, запрос текущего поворота
        // экрана через Activity больше не нужен (и не может устареть/сбиться
        // в переходный момент разблокировки, как раньше).
        val remapOk = SensorManager.remapCoordinateSystem(
            rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remappedMatrix
        )
        if (!remapOk) return

        SensorManager.getOrientation(remappedMatrix, orientation)
        val azimuthRad = orientation[0]
        if (azimuthRad.isNaN()) return

        val magneticHeadingDeg = AzimuthNormalizer.normalize(Math.toDegrees(azimuthRad.toDouble()).toFloat())
        val heading = if (northMode == NorthMode.TRUE) {
            AzimuthNormalizer.normalize(magneticHeadingDeg + magneticDeclination)
        } else {
            magneticHeadingDeg
        }
        _heading.value = heading
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val SENSOR_DELAY_MICROS = 20_000
    }
}
