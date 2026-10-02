package com.trailback.app.data.db
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
@Dao
interface EntryPointDao {
    @Insert
    suspend fun insert(entryPoint: EntryPoint): Long
    /** НОВОЕ: для BackupManager.restoreIfPresent() — восстановление из
     * бэкапа с сохранением исходных id (чтобы TrackPoint.entryPointId не
     * потерял связь). IGNORE — тихий merge: если запись с таким id уже
     * есть в БД (повторное открытие той же папки, не свежая установка),
     * она остаётся как есть, конфликтующая строка из бэкапа просто
     * пропускается, ничего не падает и не дублируется. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoreConflict(entryPoints: List<EntryPoint>)
    @Query("SELECT * FROM entry_points ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<EntryPoint>>
    @Query("SELECT * FROM entry_points WHERE id = :id")
    suspend fun getById(id: Long): EntryPoint?
    /** НОВОЕ: для BackupManager — разовый снимок всех записей, в отличие
     * от observeAll() (Flow, для подписки в UI). */
    @Query("SELECT * FROM entry_points ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<EntryPoint>
    /** Массовая очистка — единственный способ удаления, с тройным подтверждением в UI. */
    @Query("DELETE FROM entry_points")
    suspend fun deleteAll()
}