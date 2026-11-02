package com.trailback.app.ui.common
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.trailback.app.R
import com.trailback.app.TrailBackApp
import kotlinx.coroutines.launch

/**
 * НОВОЕ (предрелизный аудит, рефакторинг MapActivity): весь флоу первого
 * запуска "выбрать/создать папку бэкапа" раньше жил прямо в MapActivity —
 * ActivityResultLauncher + три приватные функции + поле, ~110 строк
 * в Activity, которая и без того отвечала за карту, сервис, геопозицию,
 * диалоги прибытия и долгий тап. Здесь — единственная ответственность:
 * провести пользователя через SAF-пикер и два диалога.
 *
 * Решения о данных (что значит "принять папку как папку бэкапа") живут не
 * здесь, а в BackupManager.adoptFolder() — контроллер только дёргает их и
 * показывает UI. Поиск/создание подпапки — suspend на Dispatchers.IO
 * (см. BackupManager), поэтому здесь они вызываются из lifecycleScope без
 * риска заблокировать главный поток.
 *
 * Создавать нужно ДО того, как Activity достигнет STARTED (требование
 * registerForActivityResult) — т.е. как свойство Activity или в её
 * onCreate. Контроллер живёт ровно столько же, сколько сама Activity
 * (поле Activity, ничем более долгоживущим не удерживается) — утечки нет.
 */
class BackupSetupController(private val activity: AppCompatActivity) {

    /** Пользователь выбирает РОДИТЕЛЬСКУЮ папку (обычно корень памяти) —
     * внутри неё ищется/создаётся "TrailBack". */
    private val pickParentFolder = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            onParentFolderPicked(uri)
        }
    }

    /**
     * Запускать после того, как экран готов (обычно конец onCreate).
     *
     * Шаги: 1) если backupFolderUri уже выбран — ничего не делаем;
     * 2) иначе объясняющий диалог → системный SAF-пикер на родительскую
     * папку; 3) после выбора — ищем в ней подпапку "TrailBack": нашли —
     * принимаем как есть (тихо восстанавливаем данные, если там уже лежит
     * бэкап — типичный случай переустановки); не нашли — спрашиваем явное
     * подтверждение "Создать каталог…?" и создаём только после "Да".
     *
     * Отказ (кнопка "Не сейчас" или отмена системного пикера) оставляет
     * backupFolderUri == null — на следующий холодный старт диалог
     * покажется снова.
     */
    fun startIfNeeded() {
        val app = activity.application as TrailBackApp
        if (app.settingsStore.backupFolderUri != null) return
        AlertDialog.Builder(activity, R.style.TrailBackAlertDialogTheme)
            .setTitle(R.string.backup_setup_title)
            .setMessage(R.string.backup_setup_message)
            .setCancelable(false)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> pickParentFolder.launch(null) }
            .setNegativeButton(R.string.backup_setup_later, null)
            .show()
    }

    private fun onParentFolderPicked(parentUri: Uri) {
        // takePersistableUriPermission может бросить SecurityException, если
        // провайдер не поддерживает постоянные разрешения — для результата
        // OpenDocumentTree это редкость, но раньше исключение не
        // перехватывалось вообще и валило бы приложение прямо в колбэке.
        try {
            activity.contentResolver.takePersistableUriPermission(
                parentUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "takePersistableUriPermission failed for $parentUri", e)
            Toast.makeText(activity, R.string.backup_setup_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val app = activity.application as TrailBackApp
        activity.lifecycleScope.launch {
            val existing = app.backupManager.findTrailBackFolder(parentUri)
            if (existing != null) {
                // Папка "TrailBack" уже есть в выбранном месте (скорее всего —
                // переустановка приложения): используем как есть, ничего не
                // спрашиваем.
                app.backupManager.adoptFolder(existing.uri)
            } else {
                showCreateFolderConfirmDialog(parentUri)
            }
        }
    }

    private fun showCreateFolderConfirmDialog(parentUri: Uri) {
        AlertDialog.Builder(activity, R.style.TrailBackAlertDialogTheme)
            .setMessage(R.string.backup_create_folder_confirm)
            .setCancelable(false)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> createAndAdoptFolder(parentUri) }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    private fun createAndAdoptFolder(parentUri: Uri) {
        val app = activity.application as TrailBackApp
        activity.lifecycleScope.launch {
            val created = app.backupManager.createTrailBackFolder(parentUri)
            if (created != null) {
                app.backupManager.adoptFolder(created.uri)
            } else {
                Toast.makeText(activity, R.string.backup_setup_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        private const val TAG = "BackupSetupController"
    }
}
