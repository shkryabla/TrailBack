package com.trailback.app.data.db
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
@Dao
interface TrackPointDao {
    @Insert
    suspend fun insert(point: TrackPoint)
    @Query("SELECT * FROM track_points WHERE entryPointId = :entryPointId ORDER BY timestamp ASC")
    fun observeForEntryPoint(entryPointId: Long): Flow<List<TrackPoint>>
    @Query("SELECT * FROM track_points WHERE entryPointId = :entryPointId ORDER BY timestamp ASC")
    suspend fun getForEntryPoint(entryPointId: Long): List<TrackPoint>
    @Query("DELETE FROM track_points WHERE entryPointId = :entryPointId")
    suspend fun deleteForEntryPoint(entryPointId: Long)
    /** НОВОЕ: для BackupManager — разовый снимок ВСЕХ точек трека (по всем
     * точкам входа сразу, не только активной записи). */
    @Query("SELECT * FROM track_points ORDER BY entryPointId ASC, timestamp ASC")
    suspend fun getAllOnce(): List<TrackPoint>
    /** ИЗМЕНЕНО: раньше здесь был deleteAllExceptEntryPoint(activeId) — удалял
     * всё, что не относится к текущей активной записи. При постоянном
     * хранении треков (см. TrackingRepository.purgeOrphanedTrackPoints) это
     * стирало бы всю историю маршрутов. Новый запрос удаляет только реальных
     * "сирот" — точки трека, чей entryPointId не существует в entry_points. */
    @Query("DELETE FROM track_points WHERE entryPointId NOT IN (SELECT id FROM entry_points)")
    suspend fun deleteOrphaned()
    @Query("DELETE FROM track_points")
    suspend fun deleteAll()
}
