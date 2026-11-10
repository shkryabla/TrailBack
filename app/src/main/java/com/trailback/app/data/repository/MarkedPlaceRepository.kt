package com.trailback.app.data.repository
import com.trailback.app.data.backup.BackupManager
import com.trailback.app.data.db.MarkedPlace
import com.trailback.app.data.db.MarkedPlaceDao
import kotlinx.coroutines.flow.Flow

/**
 * НОВОЕ (предрелизный аудит): правило "сохранить/удалить отмеченное место и
 * сразу обновить бэкап" было написано ТРИЖДЫ — в MarkedPlacesViewModel и
 * дважды в MapActivity (showMarkPlaceDialog, showSavePlaceAtDialog), причём
 * Activity ходила в MarkedPlaceDao напрямую. Достаточно было забыть
 * backupNow() в одной из копий — и новое место не попало бы в бэкап, а
 * данные, ради сохранности которых бэкап и делался, тихо потерялись бы.
 * Теперь правило живёт здесь и больше нигде.
 *
 * Тот же приём, что у TrackingRepository (который тоже зовёт
 * backupManager.backupNow() после изменений) — единообразие слоя данных.
 */
class MarkedPlaceRepository(
    private val markedPlaceDao: MarkedPlaceDao,
    private val backupManager: BackupManager
) {
    fun observeAll(): Flow<List<MarkedPlace>> = markedPlaceDao.observeAll()

    suspend fun add(name: String, latitude: Double, longitude: Double) {
        markedPlaceDao.insert(
            MarkedPlace(
                name = name,
                latitude = latitude,
                longitude = longitude,
                timestamp = System.currentTimeMillis()
            )
        )
        backupManager.backupNow()
    }

    suspend fun delete(place: MarkedPlace) {
        markedPlaceDao.delete(place)
        backupManager.backupNow()
    }
}
