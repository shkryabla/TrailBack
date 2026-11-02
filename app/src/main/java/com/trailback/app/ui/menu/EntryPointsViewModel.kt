package com.trailback.app.ui.menu
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trailback.app.TrailBackApp
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.data.repository.TrackingRepository
import com.trailback.app.data.repository.TrackingStateStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * НОВОЕ (предрелизный аудит, тот же рефакторинг, что и у MarkedPlaces —
 * см. MarkedPlacesViewModel): весь доступ к TrackingRepository вынесен сюда
 * из EntryPointsActivity. Байндинг к TrackingService осознанно ОСТАВЛЕН в
 * Activity — это системная Android-подписка (bindService/ServiceConnection),
 * завязанная на Context и жизненный цикл окна, а не на данные; ViewModel
 * отвечает только за чтение/запись в БД через репозиторий.
 */
class EntryPointsViewModel(
    private val trackingRepository: TrackingRepository,
    private val stateStore: TrackingStateStore
) : ViewModel() {
    val entryPoints: StateFlow<List<EntryPoint>> = trackingRepository.observeEntryPoints()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Смена активной точки заблокирована в любом активном режиме (и
     * RECORDING, и RETURNING) — см. решение по ТЗ в onEntryPointTapped
     * исходного кода Activity. */
    fun isSelectionLocked(): Boolean = stateStore.mode != TrackingMode.IDLE

    /**
     * Выбирает точку как активную и переводит в режим "Домой". Вызывать
     * только когда isSelectionLocked() == false — ViewModel не дублирует
     * эту проверку сама, чтобы не плодить два места, откуда может прийти
     * Toast "нельзя сменить точку" (единственное место — Activity, перед
     * показом диалога подтверждения).
     *
     * onDbUpdated вызывается на Main после завершения записи в БД — Activity
     * использует его, чтобы следом дёрнуть TrackingService.updateMode()
     * (это уже не забота ViewModel, см. комментарий класса) и закрыть экран.
     */
    fun selectEntryPoint(entryPointId: Long, onDbUpdated: () -> Unit) {
        viewModelScope.launch {
            trackingRepository.selectActiveEntryPoint(entryPointId)
            trackingRepository.enterReturningMode()
            onDbUpdated()
        }
    }
    /** Тройное подтверждение самого удаления остаётся в Activity (это UI-
     * забота) — сюда попадает только финальный вызов после "Да" x3. */
    fun clearAllEntryPoints() {
        viewModelScope.launch { trackingRepository.clearAllEntryPoints() }
    }

    class Factory(private val app: TrailBackApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EntryPointsViewModel(app.trackingRepository, app.trackingStateStore) as T
        }
    }
}
