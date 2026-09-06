package com.trailback.app.ui.menu
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.trailback.app.ui.common.KeepScreenOnActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.trailback.app.TrailBackApp
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.databinding.ActivityMenuBinding
import com.trailback.app.ui.compass.CompassActivity
class MenuActivity : KeepScreenOnActivity() {
    private lateinit var binding: ActivityMenuBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val items = listOf(
            MenuItem(getString(com.trailback.app.R.string.menu_entry_points)) {
                startActivity(Intent(this, EntryPointsActivity::class.java))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_marked_places)) {
                startActivity(Intent(this, MarkedPlacesActivity::class.java))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_compass)) {
                startActivity(Intent(this, CompassActivity::class.java))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_compass_settings)) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_COMPASS))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_offline_maps)) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_MAPS))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_calibration)) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_CALIBRATION))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_info)) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_INFO))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_language)) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_LANGUAGE))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_exit)) {
                onExitTapped()
            }
        )
        binding.menuList.layoutManager = LinearLayoutManager(this)
        binding.menuList.adapter = MenuAdapter(items)
        // НОВОЕ: кнопка "Выход" в постоянном уведомлении (см. NotificationHelper)
        // открывает этот экран с данным флагом — используем ТОТ ЖЕ onExitTapped(),
        // что и кнопка меню, чтобы поведение (блокировка в режиме "Домой",
        // диалог подтверждения) было идентичным (см. решение по ТЗ).
        if (intent.getBooleanExtra(EXTRA_AUTO_EXIT, false)) {
            onExitTapped()
        }
    }
    /**
     * Выход заблокирован в активном режиме "Домой" (см. решение по ТЗ).
     *
     * Традиционный для Android способ закрытия приложения с фоновым
     * сервисом (см. решение по ТЗ): останавливаем сервис штатно и
     * закрываем всю задачу — Android сам освободит процесс, когда сочтёт
     * нужным, без явного самоубийства.
     *
     * РАНЬШЕ здесь был android.os.Process.killProcess() сразу после
     * stopService(). Это стало причиной бага "приложение не закрывается":
     * stopService() — АСИНХРОННЫЙ вызов (лишь просьба системе остановить
     * сервис), а killProcess() убивает процесс СИНХРОННО следующей же
     * строкой. Если самоубийство происходило раньше, чем система успевала
     * зарегистрировать нашу остановку как ОСОЗНАННУЮ, а TrackingService —
     * foreground-сервис с START_STICKY (см. onStartCommand) — Android
     * трактовал это как НЕОЖИДАННУЮ смерть процесса и, следуя контракту
     * START_STICKY, заново поднимал сервис в новом процессе. Со стороны
     * это выглядело так, будто "Выход" просто закрывает экран меню и
     * ничего не происходит. Явное самоубийство процесса — и вообще
     * нестандартный приём: большинство Android-приложений просто
     * останавливают сервис и завершают задачу, не убивая процесс сами.
     */
    private fun onExitTapped() {
        val app = application as TrailBackApp
        if (app.trackingStateStore.mode == TrackingMode.RETURNING) {
            Toast.makeText(this, com.trailback.app.R.string.exit_locked_in_returning, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(com.trailback.app.R.string.exit_confirm_message)
            .setPositiveButton(com.trailback.app.R.string.arrived_dialog_yes) { _, _ ->
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancel(com.trailback.app.service.NotificationHelper.FOREGROUND_NOTIFICATION_ID)
                stopService(Intent(this, com.trailback.app.service.TrackingService::class.java))
                // finishAndRemoveTask() (не finishAffinity()) — иначе задача
                // приложения оставалась висеть в "Недавних" даже после
                // закрытия всех экранов (см. решение по ТЗ).
                finishAndRemoveTask()
            }
            .setNegativeButton(com.trailback.app.R.string.arrived_dialog_no, null)
            .show()
    }
    companion object {
        /** Флаг для запуска этого экрана с автоматическим вызовом onExitTapped()
         * — используется кнопкой "Выход" в постоянном уведомлении (см.
         * NotificationHelper.buildForegroundNotification). */
        const val EXTRA_AUTO_EXIT = "extra_auto_exit"
    }
}
data class MenuItem(val title: String, val onClick: () -> Unit)

