package com.trailback.app.ui.map
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import com.trailback.app.R

/**
 * НОВОЕ (предрелизный аудит, рефакторинг MapActivity): диалоги, связанные с
 * сохранением места и долгим тапом по карте. Как и TrackingDialogs — только
 * показ, никакой логики данных и состояния: ЧТО делать с введённым именем
 * и откуда брать координаты, решает вызывающий код.
 *
 * Главное, что здесь устранено: раньше было ДВА почти одинаковых диалога
 * ввода названия (showMarkPlaceDialog — для текущей геопозиции и
 * showSavePlaceAtDialog — для точки долгого тапа), отличавшихся только
 * источником координат. Теперь один askPlaceName(), а источник координат —
 * забота вызывающего кода.
 */
class PlaceDialogs(private val activity: AppCompatActivity) {

    /**
     * Диалог ввода названия места. [onNamed] вызывается только с непустым
     * (после trim) названием — пустой ввод молча игнорируется, как и раньше.
     */
    fun askPlaceName(onNamed: (String) -> Unit) {
        val input = AppCompatEditText(activity).apply {
            hint = activity.getString(R.string.mark_place_hint)
        }
        AlertDialog.Builder(activity, R.style.TrailBackAlertDialogTheme)
            .setTitle(R.string.mark_place_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) onNamed(name)
            }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    /** Долгий тап по карте: сохранить место или взять направление. Работает
     * в любом TrackingMode — диалог от режима не зависит. */
    fun showLongPressActions(onSavePlace: () -> Unit, onTakeDirection: () -> Unit) {
        AlertDialog.Builder(activity, R.style.TrailBackAlertDialogTheme)
            .setTitle(R.string.long_press_dialog_title)
            .setItems(
                arrayOf(
                    activity.getString(R.string.long_press_save_place),
                    activity.getString(R.string.long_press_take_direction)
                )
            ) { _, which ->
                when (which) {
                    0 -> onSavePlace()
                    1 -> onTakeDirection()
                }
            }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }
}
