package com.trailback.app.data.repository
import com.trailback.app.data.backup.BackupManager
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.data.db.EntryPointDao
import com.trailback.app.data.db.TrackPoint
import com.trailback.app.data.db.TrackPointDao
import kotlinx.coroutines.flow.Flow
/**
 * Инкапсулирует логику п.7 ТЗ: точки входа хранятся всегда, точки трека —
 * тоже постоянно (см. решение по ТЗ о хранении треков вместе с точками
 * входа), кроме недописанных после краша/просрочки восстановления.
 */
class TrackingRepository(
    private val entryPointDao: EntryPointDao,
    private val trackPointDao: TrackPointDao,
    private val stateStore: TrackingStateStore,
    private val backupManager: BackupManager
) {
    fun observeEntryPoints(): Flow<List<EntryPoint>> = entryPointDao.observeAll()
    fun observeTrackForEntryPoint(entryPointId: Long): Flow<List<TrackPoint>> =
        trackPointDao.observeForEntryPoint(entryPointId)
    /**
     * Нажатие "Старт": создаёт новую точку входа, сбрасывает счётчик
     * дистанции. Трек ПРЕДЫДУЩЕЙ точки входа больше не удаляется — треки
     * хранятся постоянно вместе с точками входа (см. решение по ТЗ), чтобы
     * при повторном выборе старой точки из истории можно было увидеть путь,
     * который был от неё пройден.
     */
    suspend fun startNewRoute(latitude: Double, longitude: Double, name: String): Long {
        val entryPoint = EntryPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = System.currentTimeMillis(),
            name = name
        )
        val newId = entryPointDao.insert(entryPoint)
        stateStore.activeEntryPointId = newId
        stateStore.distanceMeters = 0f
        stateStore.mode = TrackingMode.RECORDING
        stateStore.lastUpdateTimestamp = System.currentTimeMillis()
        stateStore.lastLatitude = latitude
        stateStore.lastLongitude = longitude
        backupManager.backupNow()
        return newId
    }
    /** Добавляет точку трека и обновляет накопленную дистанцию (счётчик пути). */
    suspend fun appendTrackPoint(latitude: Double, longitude: Double, accuracyMeters: Float) {
        val entryPointId = stateStore.activeEntryPointId
        if (entryPointId < 0) return
        val deltaMeters = distanceBetween(
            stateStore.lastLatitude, stateStore.lastLongitude,
            latitude, longitude
        )
        stateStore.distanceMeters += deltaMeters
        stateStore.lastLatitude = latitude
        stateStore.lastLongitude = longitude
        stateStore.lastUpdateTimestamp = System.currentTimeMillis()
        trackPointDao.insert(
            TrackPoint(
                entryPointId = entryPointId,
                latitude = latitude,
                longitude = longitude,
                timestamp = System.currentTimeMillis(),
                accuracyMeters = accuracyMeters
            )
        )
    }
    suspend fun enterReturningMode() {
        stateStore.mode = TrackingMode.RETURNING
        stateStore.lastUpdateTimestamp = System.currentTimeMillis()
        // НОВОЕ: "Домой" всегда главнее "взятия направления" — по решению
        // отменяем произвольную цель и переключаем стрелку на точку входа.
        stateStore.clearNavigationTarget()
    }
    // === НОВОЕ: "взятие направления" ===
    /** Долгий тап на карте → "Взять направление сюда". Независимо от
     * TrackingMode (может работать одновременно с RECORDING). */
    suspend fun setNavigationTarget(latitude: Double, longitude: Double) {
        stateStore.navigationTargetLatitude = latitude
        stateStore.navigationTargetLongitude = longitude
        stateStore.navigationTargetActive = true
    }
    /** Отмена вручную (кнопка) или после подтверждения прибытия к цели. */
    suspend fun clearNavigationTarget() {
        stateStore.clearNavigationTarget()
    }
    /**
     * Подтверждено "Вы вернулись!" — сброс состояния трекинга в исходное.
     * Точки трека БОЛЬШЕ НЕ УДАЛЯЮТСЯ (см. решение по ТЗ о постоянном
     * хранении треков вместе с точками входа) — trackPointDao.deleteForEntryPoint
     * здесь раньше вызывался, теперь этот путь убран сознательно.
     */
    suspend fun confirmArrivedHome() {
        stateStore.reset()
        backupManager.backupNow()
    }
    /**
     * Пользователь отказался восстанавливать трек после краша (диалог
     * "продолжить/отменить"). Точка входа остаётся в истории (постоянное
     * хранение по п.7.1), но точки самого трека удаляются, состояние — в IDLE.
     */
    suspend fun cancelRecovery() {
        val entryPointId = stateStore.activeEntryPointId
        if (entryPointId >= 0) {
            trackPointDao.deleteForEntryPoint(entryPointId)
        }
        stateStore.reset()
    }
    suspend fun getActiveEntryPoint(): EntryPoint? {
        val id = stateStore.activeEntryPointId
        return if (id >= 0) entryPointDao.getById(id) else null
    }
    /**
     * Выбор другой сохранённой точки входа как активной цели навигации
     * (см. решение по ТЗ — доступно только вне режима "Домой").
     */
    suspend fun selectActiveEntryPoint(entryPointId: Long) {
        stateStore.activeEntryPointId = entryPointId
    }
    /**
     * Удаляет точки трека, у которых entryPointId ссылается на уже
     * несуществующую точку входа — реальные "сироты" (например, если
     * точка входа была удалена через "Очистить все точки входа" мимо
     * каскадного удаления, или после какого-то более старого бага).
     *
     * ИЗМЕНЕНО: раньше эта функция удаляла ВСЕ точки трека кроме текущей
     * активной записи (deleteAllExceptEntryPoint) — при постоянном хранении
     * треков (см. решение по ТЗ) это стирало бы всю историю пройденных
     * маршрутов при каждом запуске приложения (вызывается из
     * TrailBackApp.onCreate). Теперь чистит только записи-сироты, историю
     * не трогает.
     */
    suspend fun purgeOrphanedTrackPoints() {
        trackPointDao.deleteOrphaned()
    }
    /**
     * Если после краша прошло больше 72 часов (см. RECOVERY_WINDOW_MILLIS),
     * трек считается брошенным: точки удаляются, состояние сбрасывается в IDLE.
     * Вызывается при старте приложения, до показа диалога восстановления.
     * @return true, если трек был признан брошенным и сброшен.
     */
    suspend fun expireStaleSessionIfNeeded(nowMillis: Long): Boolean {
        val mode = stateStore.mode
        if (mode != TrackingMode.RECORDING && mode != TrackingMode.RETURNING) return false
        if (stateStore.hasRecoverableTrack(nowMillis)) return false
        val entryPointId = stateStore.activeEntryPointId
        if (entryPointId >= 0) {
            trackPointDao.deleteForEntryPoint(entryPointId)
        }
        stateStore.reset()
        return true
    }
    /**
     * Массовая очистка точек входа (тройное подтверждение в UI) теперь
     * каскадно удаляет и все точки треков — при постоянном хранении треков
     * (см. решение по ТЗ) иначе они остались бы в базе точками-сиротами
     * навсегда (entryPointId будет указывать в никуда).
     */
    suspend fun clearAllEntryPoints() {
        entryPointDao.deleteAll()
        trackPointDao.deleteAll()
        backupManager.backupNow()
    }
    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        if (lat1 == 0.0 && lon1 == 0.0) return 0f
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
}
