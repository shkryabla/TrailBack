package com.trailback.app.data.backup
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.trailback.app.data.db.EntryPointDao
import com.trailback.app.data.db.MarkedPlaceDao
import com.trailback.app.data.db.TrackPointDao
import com.trailback.app.data.repository.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Автоматический бэкап точек входа, отмеченных мест и треков — по решению:
 * складывается в ту же SAF-папку, что выбрана под офлайн-карты
 * (SettingsStore.offlineMapsUri), в подпапку "trailback_backup". Смысл —
 * данные должны пережить удаление приложения, а внутреннее хранилище Room
 * (и даже getExternalFilesDir()!) при удалении приложения стирается Android
 * целиком, независимо от настроек; папка карт выбрана пользователем через
 * SAF и приложению не принадлежит — она переживает удаление приложения.
 *
 * Формат — обычный JSON через org.json (часть Android SDK, новой
 * зависимости не требует). Сознательно не копируем сырой .db файл Room
 * напрямую — Room по умолчанию может использовать WAL-журналирование
 * (значит, часть свежих данных физически лежит в отдельном -wal файле, а
 * не в основном .db), и наивное копирование только .db рискует потерять
 * последние изменения. Чтение через DAO (getAllOnce) всегда отдаёт
 * консистентное состояние независимо от режима журнала.
 *
 * "Автоматически" означает — без отдельной кнопки: вызывается из мест,
 * где меняются точки входа/отмеченные места (см. вызовы backupNow() в
 * TrackingRepository, MapActivity, MarkedPlacesActivity, SettingsActivity),
 * а не по расписанию. WorkManager сознательно не подключаем — это новая
 * зависимость ради фоново-периодического запуска, которого сама фича не
 * требует: события изменения данных в этом приложении и так происходят
 * нечасто (не на каждый GPS-тик), а любое из них — естественный повод
 * тут же обновить бэкап.
 */
class BackupManager(
    private val context: Context,
    private val entryPointDao: EntryPointDao,
    private val markedPlaceDao: MarkedPlaceDao,
    private val trackPointDao: TrackPointDao,
    private val settingsStore: SettingsStore
) {
    /**
     * Перезаписывает ОДИН файл фиксированного имени (не копит версии/историю
     * снимков) — самое простое поведение, отвечающее "актуальный бэкап
     * всегда лежит в одном известном месте". Тихо ничего не делает, если
     * папка офлайн-карт ещё не выбрана — это нормальное состояние на первом
     * запуске, не ошибка (как только пользователь выберет папку в
     * настройках, оттуда же вызывается backupNow() — см. SettingsActivity).
     */
    suspend fun backupNow() = withContext(Dispatchers.IO) {
        val treeUriString = settingsStore.offlineMapsUri ?: return@withContext
        try {
            val treeUri = Uri.parse(treeUriString)
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
            val backupDir = root.findFile(BACKUP_FOLDER_NAME)?.takeIf { it.isDirectory }
                ?: root.createDirectory(BACKUP_FOLDER_NAME)
                ?: return@withContext
            val entryPoints = entryPointDao.getAllOnce()
            val markedPlaces = markedPlaceDao.getAllOnce()
            val trackPoints = trackPointDao.getAllOnce()
            val json = buildBackupJson(entryPoints, markedPlaces, trackPoints)
            // Пересоздаём файл, а не дописываем — createFile() у SAF-провайдеров
            // при коллизии имени может создать "file (1).json" вместо перезаписи,
            // поэтому старый файл явно удаляется перед созданием нового с тем
            // же именем.
            backupDir.findFile(BACKUP_FILE_NAME)?.delete()
            val file = backupDir.createFile("application/json", BACKUP_FILE_NAME) ?: return@withContext
            context.contentResolver.openOutputStream(file.uri)?.use { output ->
                output.write(json.toString(2).toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // Бэкап — вспомогательная функция; сбой SAF/диска не должен
            // ронять основной сценарий (запись маршрута, отметка места).
        }
    }

    private fun buildBackupJson(
        entryPoints: List<com.trailback.app.data.db.EntryPoint>,
        markedPlaces: List<com.trailback.app.data.db.MarkedPlace>,
        trackPoints: List<com.trailback.app.data.db.TrackPoint>
    ): JSONObject {
        val entryPointsArray = JSONArray()
        entryPoints.forEach { ep ->
            entryPointsArray.put(JSONObject().apply {
                put("id", ep.id)
                put("latitude", ep.latitude)
                put("longitude", ep.longitude)
                put("timestamp", ep.timestamp)
                put("name", ep.name)
            })
        }
        val markedPlacesArray = JSONArray()
        markedPlaces.forEach { mp ->
            markedPlacesArray.put(JSONObject().apply {
                put("id", mp.id)
                put("name", mp.name)
                put("latitude", mp.latitude)
                put("longitude", mp.longitude)
                put("timestamp", mp.timestamp)
            })
        }
        val trackPointsArray = JSONArray()
        trackPoints.forEach { tp ->
            trackPointsArray.put(JSONObject().apply {
                put("id", tp.id)
                put("entryPointId", tp.entryPointId)
                put("latitude", tp.latitude)
                put("longitude", tp.longitude)
                put("timestamp", tp.timestamp)
                put("accuracyMeters", tp.accuracyMeters)
            })
        }
        return JSONObject().apply {
            put("backupFormatVersion", BACKUP_FORMAT_VERSION)
            put("createdAtMillis", System.currentTimeMillis())
            put("entryPoints", entryPointsArray)
            put("markedPlaces", markedPlacesArray)
            put("trackPoints", trackPointsArray)
        }
    }

    companion object {
        private const val BACKUP_FOLDER_NAME = "trailback_backup"
        private const val BACKUP_FILE_NAME = "trailback_backup.json"
        // НОВОЕ: версия формата бэкапа — если структура JSON изменится в
        // будущем (например, появится импорт), по этому полю можно будет
        // понять, какой парсер применять к файлу.
        private const val BACKUP_FORMAT_VERSION = 1
    }
}
