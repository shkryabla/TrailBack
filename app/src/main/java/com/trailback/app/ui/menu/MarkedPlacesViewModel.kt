package com.trailback.app.ui.menu
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trailback.app.TrailBackApp
import com.trailback.app.data.db.MarkedPlace
import com.trailback.app.data.repository.MarkedPlaceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * НОВОЕ (предрелизный аудит, пункт о единственном ViewModel на всё
 * приложение): раньше весь доступ к MarkedPlaceDao и вызовы
 * BackupManager.backupNow() жили прямо в MarkedPlacesActivity — обычная
 * Activity отвечала и за UI (диалоги, PopupMenu, Intent-навигация), и за
 * бизнес-логику (валидация координат, запись в БД, бэкап). Теперь
 * Activity — тонкий UI-слой, вся работа с данными здесь.
 *
 * Разделение ответственности: Activity по-прежнему сама показывает диалоги,
 * PopupMenu и открывает geo:/mailto:-интенты (это UI-забота, ей там и
 * место) — но САМ доступ к DAO и решение "когда вызывать backupNow()"
 * теперь единственный раз описаны здесь, а не дублируются по разным
 * callback'ам Activity.
 */
class MarkedPlacesViewModel(
    private val repository: MarkedPlaceRepository
) : ViewModel() {
    /**
     * stateIn(WhileSubscribed(5000)) — а не просто observeAll() напрямую —
     * чтобы Flow из Room не пересоздавал подписку при каждом мимолётном
     * уходе экрана в фон (поворот, переключение на другое приложение на
     * пару секунд): подписка держится ещё 5с после последнего отписавшегося
     * коллектора, прежде чем реально остановиться. 5000 — рекомендованное
     * значение из официальных гайдов Android по StateFlow+Compose/Views,
     * не произвольная прикидка.
     */
    val places: StateFlow<List<MarkedPlace>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Валидация + вставка в одном месте — раньше parseCoordinates() жила в
     * Activity просто как private fun без явной связи с тем, что она —
     * часть правила "когда место вообще можно добавить". Чистая функция,
     * не трогает Android API — тривиально покрывается unit-тестом без
     * Robolectric/инструментальных тестов.
     *
     * @return true — место добавлено, false — невалидный ввод (имя пустое
     * или координаты не распознались/вне диапазона), Activity показывает
     * Toast с ошибкой и не закрывает диалог.
     */
    fun addPlace(name: String, coordinatesRaw: String?): Boolean {
        val trimmedName = name.trim()
        val coordinates = parseCoordinates(coordinatesRaw)
        if (trimmedName.isEmpty() || coordinates == null) return false
        val (latitude, longitude) = coordinates
        viewModelScope.launch { repository.add(trimmedName, latitude, longitude) }
        return true
    }
    fun delete(place: MarkedPlace) {
        viewModelScope.launch { repository.delete(place) }
    }

    class Factory(private val app: TrailBackApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MarkedPlacesViewModel(app.markedPlaceRepository) as T
        }
    }
    companion object {
        /**
         * Разбирает строку вида "55.75124, 37.61842" — null при любой
         * некорректности (не то число частей, не парсится как Double,
         * широта/долгота вне допустимого диапазона).
         *
         * НОВОЕ (предрелизный аудит — чек-лист тестов, пункт "парсинг
         * координат не покрыт вообще"): вынесена в companion object
         * намеренно — чистая функция без единой Android-зависимости
         * (не нужен ни Context, ни DAO, ни BackupManager), поэтому
         * тестируется обычным JUnit без Robolectric, не требуя
         * инстанцирования самого ViewModel. См. MarkedPlacesViewModelTest.
         */
        fun parseCoordinates(raw: String?): Pair<Double, Double>? {
            val parts = raw?.split(",")?.map { it.trim() } ?: return null
            if (parts.size != 2) return null
            val latitude = parts[0].toDoubleOrNull() ?: return null
            val longitude = parts[1].toDoubleOrNull() ?: return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return latitude to longitude
        }
    }
}
