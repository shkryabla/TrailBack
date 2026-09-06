package com.trailback.app
import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.trailback.app.data.db.AppDatabase
import com.trailback.app.data.repository.SettingsStore
import com.trailback.app.data.repository.TrackingRepository
import com.trailback.app.data.repository.TrackingStateStore
import com.trailback.app.ui.compass.CompassSensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
/**
 * Корень простого ручного DI (без Hilt/Dagger — конструкторное внедрение,
 * как того требует п.11 ТЗ).
 */
class TrailBackApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var trackingStateStore: TrackingStateStore
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var trackingRepository: TrackingRepository
        private set
    // НОВОЕ: единый на всё приложение экземпляр — раньше каждая Activity
    // (CompassActivity, MapActivity) создавала свой собственный, из-за чего
    // при переключении между экранами фьюжн-алгоритм датчика лишний раз
    // "разогревался" заново (см. решение по ТЗ).
    lateinit var compassSensorManager: CompassSensorManager
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // НОВОЕ: простой счётчик "видимых" Activity через штатный
    // Application.ActivityLifecycleCallbacks (доступен из коробки, начиная
    // с API 14 — новая зависимость типа lifecycle-process не нужна).
    // Используется TrackingService, чтобы решить, нужно ли показывать
    // фоновое push-уведомление о прибытии — если хотя бы один экран
    // приложения виден, диалог и так покажет сама Activity, дублировать
    // уведомлением не нужно (см. решение по ТЗ и комментарий в
    // NotificationHelper.notifyArrivedHome()).
    private var startedActivityCount = 0
    val isAppInForeground: Boolean
        get() = startedActivityCount > 0
    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        trackingStateStore = TrackingStateStore(this)
        settingsStore = SettingsStore(this)
        compassSensorManager = CompassSensorManager(this).apply {
            northMode = settingsStore.northMode
        }
        trackingRepository = TrackingRepository(
            entryPointDao = database.entryPointDao(),
            trackPointDao = database.trackPointDao(),
            stateStore = trackingStateStore
        )
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivityCount++ }
            override fun onActivityStopped(activity: Activity) { startedActivityCount-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // 1) Если после краша прошло больше 72 часов — трек считается брошенным
        //    и сбрасывается; 2) в любом случае подчищаем осиротевшие точки трека
        //    от предыдущих сессий (см. TrackingRepository).
        appScope.launch {
            trackingRepository.expireStaleSessionIfNeeded(System.currentTimeMillis())
            trackingRepository.purgeOrphanedTrackPoints()
        }
    }
}
