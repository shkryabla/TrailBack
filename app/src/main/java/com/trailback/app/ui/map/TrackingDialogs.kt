package com.trailback.app.ui.map
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.trailback.app.R

/**
 * НОВОЕ (предрелизный аудит, рефакторинг MapActivity): диалоги основного
 * сценария "Старт → Домой → Я на месте / Вы вернулись!" раньше были шестью
 * приватными функциями прямо в MapActivity, вперемешку с остальной логикой
 * экрана. Здесь — только показ диалогов (тексты, кнопки, порядок шагов);
 * ЧТО делать после "Да" (viewModel, TrackingService) решает MapActivity и
 * передаёт колбэком — презентер ни о ViewModel, ни о сервисе не знает и
 * состояния не хранит.
 *
 * Держит ссылку на Activity только как поле самой Activity (создаётся в ней
 * как свойство) — живёт ровно столько же, чем-то более долгоживущим не
 * удерживается, утечки нет.
 */
class TrackingDialogs(private val activity: AppCompatActivity) {

    /**
     * "Старт": если точность GPS хуже порога ([isLowAccuracy], решает
     * вызывающий код — у него есть последняя геопозиция) — сперва отдельное
     * предупреждение, потом обычное подтверждение начала записи.
     */
    fun confirmStart(isLowAccuracy: Boolean, onConfirmed: () -> Unit) {
        if (isLowAccuracy) {
            builder()
                .setMessage(R.string.low_accuracy_warning)
                .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> showStartConfirm(onConfirmed) }
                .setNegativeButton(R.string.arrived_dialog_no, null)
                .show()
        } else {
            showStartConfirm(onConfirmed)
        }
    }

    private fun showStartConfirm(onConfirmed: () -> Unit) {
        builder()
            .setTitle(R.string.confirm_start_title)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    /** "Домой": остановить запись и включить режим возврата. */
    fun confirmHome(onConfirmed: () -> Unit) {
        builder()
            .setTitle(R.string.confirm_home_title)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    /**
     * Ручное "Я на месте" через объединённую кнопку: два РАЗНЫХ диалога
     * подряд, а не троекратное повторение одного и того же — сперва обычное
     * подтверждение прибытия, затем отдельное предупреждение о сбросе
     * режима возврата.
     */
    fun confirmManualArrival(onConfirmed: () -> Unit) {
        builder()
            .setTitle(R.string.arrived_dialog_title)
            .setMessage(R.string.arrived_dialog_message)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> confirmArrivalReset(onConfirmed) }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    private fun confirmArrivalReset(onConfirmed: () -> Unit) {
        builder()
            .setMessage(R.string.manual_arrival_reset_confirm_message)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    /**
     * Автоматическое "Вы вернулись!" (детектор прибытия или тап по
     * уведомлению) — закрыть тапом мимо нельзя, нужен явный ответ: и
     * "Да", и "Нет" сообщаются вызывающему коду (по "Нет" сервис ставит
     * cooldown на повторный автопоказ).
     */
    fun showArrived(onConfirmed: () -> Unit, onDeclined: () -> Unit) {
        builder()
            .setTitle(R.string.arrived_dialog_title)
            .setMessage(R.string.arrived_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.arrived_dialog_no) { _, _ -> onDeclined() }
            .show()
    }

    /**
     * Восстановление после краша: приложение закрылось во время записи
     * маршрута. Закрыть тапом мимо нельзя — пользователь обязан выбрать,
     * продолжать ли запись или отменить её.
     */
    fun showRecovery(onContinue: () -> Unit, onCancel: () -> Unit) {
        builder()
            .setTitle(R.string.recovery_dialog_title)
            .setMessage(R.string.recovery_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.recovery_dialog_continue) { _, _ -> onContinue() }
            .setNegativeButton(R.string.recovery_dialog_cancel) { _, _ -> onCancel() }
            .show()
    }

    /** Ручная отмена "взятия направления" (кнопка слева от главной). */
    fun confirmCancelDirection(onConfirmed: () -> Unit) {
        builder()
            .setMessage(R.string.cancel_direction_confirm_message)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    /**
     * Автоматическое "Вы пришли!" для цели "взятия направления" — тот же
     * принцип, что и showArrived() для режима "Домой": явный ответ
     * обязателен, "Нет" сообщается вызывающему коду (cooldown в сервисе).
     */
    fun showDirectionArrived(onConfirmed: () -> Unit, onDeclined: () -> Unit) {
        builder()
            .setTitle(R.string.direction_arrived_dialog_title)
            .setMessage(R.string.direction_arrived_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.arrived_dialog_no) { _, _ -> onDeclined() }
            .show()
    }

    private fun builder() = AlertDialog.Builder(activity, R.style.TrailBackAlertDialogTheme)
}
