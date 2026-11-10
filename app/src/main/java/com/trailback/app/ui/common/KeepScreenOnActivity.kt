package com.trailback.app.ui.common
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Общий базовый класс для всех экранов приложения (см. решение по ТЗ):
 * пока пользователь в лесу сверяется с картой/компасом, экран не должен
 * гаснуть и телефон не должен блокироваться — это неудобно и рискованно
 * (можно не заметить нужный момент). Флаг FLAG_KEEP_SCREEN_ON ставится в
 * onResume() и снимается в onPause() — действует только пока КОНКРЕТНЫЙ
 * экран приложения виден на переднем плане, не меняет глобальные системные
 * настройки автогашения экрана (в других приложениях/на рабочем столе
 * поведение системы останется обычным).
 *
 * Достаточно наследоваться от этого класса вместо AppCompatActivity —
 * никаких дополнительных вызовов в самих экранах не требуется.
 */
abstract class KeepScreenOnActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
}
