package com.trailback.app.ui.map
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.trailback.app.ui.common.KeepScreenOnActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.trailback.app.R
import com.trailback.app.TrailBackApp
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.databinding.ActivityMapBinding
import com.trailback.app.service.TrackingService
import com.trailback.app.ui.compass.CompassActivity
import com.trailback.app.ui.common.BackupSetupController
import com.trailback.app.ui.common.InfoPanelController
import com.trailback.app.ui.menu.MenuActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
class MapActivity : KeepScreenOnActivity() {
    companion object {
        const val ACTION_SHOW_ARRIVED_DIALOG = "com.trailback.app.SHOW_ARRIVED_DIALOG"
        // НОВОЕ: симметрично ACTION_SHOW_ARRIVED_DIALOG, для "взятия направления".
        const val ACTION_SHOW_DIRECTION_ARRIVED_DIALOG = "com.trailback.app.SHOW_DIRECTION_ARRIVED_DIALOG"
        // НОВОЕ: "Показать на карте" из MarkedPlacesActivity — центрирует
        // карту на координатах отмеченного места (см. MapController.centerOn).
        const val ACTION_CENTER_ON_PLACE = "com.trailback.app.CENTER_ON_PLACE"
        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val LOW_ACCURACY_THRESHOLD_METERS = 50f
    }
    private lateinit var binding: ActivityMapBinding
    private lateinit var viewModel: MapViewModel
    private lateinit var mapController: MapController
    private var trackingService: TrackingService? = null
    private var isServiceBound = false
    // НОВОЕ: подписка на общий (см. TrailBackApp) CompassSensorManager.heading —
    // отменяется/пересоздаётся вместе с acquire()/release() в onResume/onPause.
    private var headingObserverJob: kotlinx.coroutines.Job? = null
    private var lastKnownLocation: Location? = null
    // НОВОЕ: "взятие направления" — координаты активной цели (см. решение
    // по ТЗ), null если не активна. Читается/пишется напрямую в
    // TrackingStateStore через TrackingRepository, это поле — локальный
    // кэш для UI (видимость кнопки отмены, линия/маркер на карте).
    private var navigationTarget: Pair<Double, Double>? = null
    private var lastAppliedOfflineMapsUri: String? = null
    private var serviceObserverJob: kotlinx.coroutines.Job? = null
    private lateinit var infoPanelController: InfoPanelController
    // Собственный лёгкий запрос геопозиции для отображения на карте и
    // кнопки "Старт", пока экран открыт — НЕЗАВИСИМО от фонового сервиса.
    // Сервис теперь не опрашивает GPS в IDLE/STOPPED (экономия батареи,
    // см. решение по ТЗ), поэтому карта в эти моменты питается отсюда, а не
    // от TrackingService. В RECORDING/RETURNING работает параллельно с
    // сервисом — не мешает, координаты берутся из того же GPS-чипа.
    private val foregroundLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private val foregroundLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastKnownLocation = location
            onLocationUpdated(location)
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as TrackingService.LocalBinder).getService()
            trackingService = service
            observeService(service)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            trackingService = null
        }
    }
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            startTrackingServiceIfNeeded()
        }
    }
    /** ИЗМЕНЕНО (предрелизный аудит): флоу первого запуска "выбрать/создать
     * папку бэкапа" (SAF-пикер + два диалога) вынесен в отдельный
     * BackupSetupController — раньше это были ~110 строк прямо здесь.
     * Создаётся как свойство, т.е. ДО STARTED — того требует
     * registerForActivityResult внутри контроллера. */
    private val backupSetup = BackupSetupController(this)
    /** Диалоги "Старт/Домой/Я на месте/Вы вернулись!" — см. TrackingDialogs. */
    private val trackingDialogs = TrackingDialogs(this)
    /** Диалоги сохранения места и долгого тапа — см. PlaceDialogs. */
    private val placeDialogs = PlaceDialogs(this)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val app = application as TrailBackApp
        viewModel = ViewModelProvider(this, MapViewModel.Factory(app))[MapViewModel::class.java]
        mapController = MapController(this, binding.mapContainer)
        lastAppliedOfflineMapsUri = app.settingsStore.offlineMapsUri
        lifecycleScope.launch {
            mapController.setupMap(lastAppliedOfflineMapsUri)
            // Пересчёт состояния зум-кнопок ПОСЛЕ готовности mapView — иначе
            // при офлайн-карте (реальная suspend-точка на Dispatchers.IO)
            // canZoomIn()/canZoomOut() читают ещё null mapView и кнопки
            // навсегда остаются disabled/полупрозрачными (баг: кнопки
            // недоступны только когда загружена офлайн-карта).
            updateZoomButtonsState()
            refreshNavigationTargetUi() // НОВОЕ — та же гонка: mapView пересоздан, маркер цели нужно перерисовать
        }
        // НОВОЕ: курс телефона теперь обновляется через подписку на общий
        // app.compassSensorManager.heading (см. onResume) вместо собственного
        // экземпляра с разовым колбэком.
        app.compassSensorManager.northMode = app.settingsStore.northMode
        infoPanelController = InfoPanelController(this, binding.topInfoPanel)
        checkCrashRecoveryThenStart(app)
        setupButtons()
        setupMapControlButtons()
        observeViewModel()
        handleIncomingIntent(intent)
        // НОВОЕ: "взятие направления" — долгий тап в любом месте карты,
        // независимо от текущего TrackingMode (см. решение по ТЗ).
        // ИЗМЕНЕНО: явный запрет без загруженной карты (ни офлайн-тайлов,
        // ни онлайн — просто серая заглушка) — без визуальной подложки
        // пользователь не видит ориентиров на местности (троп, рек, дорог)
        // и физически не может осмысленно выбрать, что отметить или куда
        // взять направление. mapController.isMapLoaded() — см. MapController.
        mapController.onLongPress = { x, y ->
            if (!mapController.isMapLoaded()) {
                Toast.makeText(this, R.string.long_press_requires_map, Toast.LENGTH_SHORT).show()
            } else {
                mapController.screenToLatLong(x, y)?.let { (lat, lon) -> showLongPressActionDialog(lat, lon) }
            }
        }
        refreshNavigationTargetUi()
        backupSetup.startIfNeeded()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }
    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SHOW_ARRIVED_DIALOG -> showArrivedDialog()
            ACTION_SHOW_DIRECTION_ARRIVED_DIALOG -> showDirectionArrivedDialog() // НОВОЕ
            // НОВОЕ: "Показать на карте" — MapActivity обычно уже жива в
            // стеке (singleTop + launcher-активность), поэтому сюда почти
            // всегда попадаем через onNewIntent(), когда mapController уже
            // полностью настроен — centerOn() просто no-op, если mapView
            // почему-то ещё не готов (холодный старт, см. MapController).
            ACTION_CENTER_ON_PLACE -> {
                val lat = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
                val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    mapController.centerOn(lat, lon)
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        Intent(this, TrackingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isServiceBound = true
        }
    }
    override fun onResume() {
        super.onResume()
        val app = application as TrailBackApp
        app.compassSensorManager.acquire()
        infoPanelController.start()
        viewModel.refreshActiveEntryPoint()
        reapplyOfflineMapIfChanged()
        startForegroundOnlyLocationUpdates()
        refreshNavigationTargetUi() // НОВОЕ
        // ИЗМЕНЕНО: раньше heading только запоминался в currentHeading и
        // применялся к стрелке значка на карте лишь при следующем GPS-фиксе
        // (см. onLocationUpdated) — отсюда рывки поворота. Теперь стрелка
        // значка вращается прямо здесь, на каждый тик курса, синхронно с
        // мини-компасом — mapController.updateUserHeading() не трогает
        // позицию и ничего не пересоздаёт (см. RotatingUserMarkerLayer).
        headingObserverJob?.cancel()
        headingObserverJob = lifecycleScope.launch {
            app.compassSensorManager.heading.collect { heading ->
                binding.miniCompassView.headingDegrees = heading
                mapController.updateUserHeading(heading)
            }
        }
    }
    override fun onPause() {
        val app = application as TrailBackApp
        app.compassSensorManager.release()
        headingObserverJob?.cancel()
        headingObserverJob = null
        infoPanelController.stop()
        foregroundLocationClient.removeLocationUpdates(foregroundLocationCallback)
        super.onPause()
    }
    /**
     * Лёгкий запрос геопозиции, активный только пока экран открыт (item 4) —
     * компенсирует то, что фоновый сервис больше не опрашивает GPS в
     * IDLE/STOPPED. Экономия батареи здесь — за счёт ОГРАНИЧЕНИЯ ВРЕМЕНИ
     * работы (только пока Activity видима, через onResume/onPause), а НЕ за
     * счёт снижения точности: PRIORITY_BALANCED_POWER_ACCURACY в основном
     * использует Wi-Fi/сотовую сеть вместо GPS-чипа и даёт точность порядка
     * сотен метров — этим объяснялось ухудшение показа позиции. Пока
     * пользователь реально смотрит на экран (и так расходует батарею на сам
     * экран), полноценная точность GPS оправдана и нужна — в том числе для
     * корректной проверки перед "Старт" (LOW_ACCURACY_THRESHOLD_METERS).
     */
    private fun startForegroundOnlyLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).build()
        try {
            foregroundLocationClient.requestLocationUpdates(request, foregroundLocationCallback, mainLooper)
        } catch (e: SecurityException) {
            // Разрешение не выдано — просто не запускаем обновления.
        }
    }
    /**
     * Раньше выбор офлайн-карты в настройках применялся только после полного
     * перезапуска приложения — MapController.setupMap() вызывался лишь один
     * раз в onCreate. Теперь при каждом возврате на экран карты сверяем,
     * не изменился ли путь к офлайн-картам, и переинициализируем карту.
     */
    private fun reapplyOfflineMapIfChanged() {
        val app = application as TrailBackApp
        val currentUri = app.settingsStore.offlineMapsUri
        if (currentUri != lastAppliedOfflineMapsUri) {
            lastAppliedOfflineMapsUri = currentUri
            lifecycleScope.launch {
                mapController.setupMap(currentUri)
                updateZoomButtonsState() // та же гонка, что и в onCreate()
                refreshNavigationTargetUi() // НОВОЕ
            }
        }
    }
    override fun onStop() {
        serviceObserverJob?.cancel()
        serviceObserverJob = null
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onStop()
    }
    override fun onDestroy() {
        mapController.onDestroy()
        super.onDestroy()
    }
    /**
     * КРИТИЧНО: отменяем предыдущую подписку перед созданием новой.
     * onServiceConnected() срабатывает заново на каждый bindService()
     * (то есть на каждый onStart(), в т.ч. после сворачивания приложения) —
     * без отмены старой корутины подписки накапливались бы при каждом
     * цикле сворачивания/разворачивания (тот же класс бага, что чинили
     * в CompassActivity — там он путал сглаживание компаса, здесь мог бы
     * приводить к повторным показам диалога "Вы вернулись!").
     */
    private fun observeService(service: TrackingService) {
        serviceObserverJob?.cancel()
        serviceObserverJob = lifecycleScope.launch {
            launch {
                service.currentLocation.collect { location ->
                    if (location != null) {
                        lastKnownLocation = location
                        onLocationUpdated(location)
                    }
                }
            }
            launch {
                service.arrivedHomeEvent.collect { shouldShow ->
                    if (shouldShow) showArrivedDialog()
                }
            }
            // НОВОЕ: "взятие направления" — независимое событие прибытия.
            launch {
                service.directionArrivedEvent.collect { shouldShow ->
                    if (shouldShow) showDirectionArrivedDialog()
                }
            }
        }
    }
    private fun onLocationUpdated(location: Location) {
        mapController.updateUserPosition(location)
        val entryPoint = viewModel.activeEntryPoint.value
        mapController.updateHomeLine(location, entryPoint, viewModel.mode.value)
        mapController.updateNavigationTargetLine(location, navigationTarget) // НОВОЕ
        infoPanelController.updateDistanceToDestination(location, entryPoint)
        // Счётчик пути (п.6): TrackingService копит дистанцию в
        // TrackingStateStore напрямую (не через ViewModel), поэтому UI
        // синхронизируется с реальным значением на каждый тик геопозиции,
        // а не полагается только на разовые сбросы во ViewModel.
        if (viewModel.mode.value == TrackingMode.RECORDING) {
            infoPanelController.updateRouteCounter()
        }
    }
    private fun setupMapControlButtons() {
        binding.zoomInButton.setOnClickListener {
            mapController.zoomIn()
            updateZoomButtonsState()
        }
        binding.zoomOutButton.setOnClickListener {
            mapController.zoomOut()
            updateZoomButtonsState()
        }
        binding.recenterButton.setOnClickListener { mapController.recenterOn(lastKnownLocation) }
        updateZoomButtonsState()
    }
    private fun updateZoomButtonsState() {
        binding.zoomInButton.isEnabled = mapController.canZoomIn()
        binding.zoomOutButton.isEnabled = mapController.canZoomOut()
        binding.zoomInButton.alpha = if (binding.zoomInButton.isEnabled) 1f else 0.4f
        binding.zoomOutButton.alpha = if (binding.zoomOutButton.isEnabled) 1f else 0.4f
    }
    private fun ensurePermissionsAndStartService() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted) {
            startTrackingServiceIfNeeded()
        } else {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestLocationPermission.launch(permissions.toTypedArray())
        }
    }
    private fun startTrackingServiceIfNeeded() {
        val intent = Intent(this, TrackingService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
    private fun checkCrashRecoveryThenStart(app: TrailBackApp) {
        val mode = app.trackingStateStore.mode
        val isActiveMode = mode == TrackingMode.RECORDING || mode == TrackingMode.RETURNING
        val serviceAlreadyRunning = isServiceRunning(TrackingService::class.java)
        // Диалог восстановления нужен, только если процесс/сервис реально
        // погибли (краш) — при обычном сворачивании foreground-сервис жив,
        // и повторно спрашивать пользователя не нужно.
        val needsRecoveryPrompt = isActiveMode &&
            !serviceAlreadyRunning &&
            app.trackingStateStore.hasRecoverableTrack(System.currentTimeMillis())
        if (needsRecoveryPrompt) {
            showRecoveryDialog(app)
        } else {
            ensurePermissionsAndStartService()
        }
    }
    private fun showRecoveryDialog(app: TrailBackApp) {
        trackingDialogs.showRecovery(
            onContinue = ::ensurePermissionsAndStartService,
            onCancel = {
                lifecycleScope.launch {
                    app.trackingRepository.cancelRecovery()
                    viewModel.resetToIdleState()
                    ensurePermissionsAndStartService()
                }
            }
        )
    }
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }
    private fun setupButtons() {
        binding.menuButton.setOnClickListener {
            startActivity(Intent(this, MenuActivity::class.java))
        }
        binding.miniCompassView.setOnClickListener {
            startActivity(Intent(this, CompassActivity::class.java))
        }
        binding.mainActionButton.setOnClickListener {
            when (viewModel.mode.value) {
                TrackingMode.IDLE -> trackingDialogs.confirmStart(isLowAccuracy(), ::onStartConfirmed)
                TrackingMode.RECORDING -> trackingDialogs.confirmHome(::onHomeConfirmed)
                TrackingMode.RETURNING -> trackingDialogs.confirmManualArrival(::onArrivalConfirmed)
            }
        }
        binding.quickMarkButton.setOnClickListener {
            showMarkPlaceDialog()
        }
        // НОВОЕ: кнопка видна только когда navigationTarget != null
        // (см. refreshNavigationTargetUi).
        binding.cancelDirectionButton.setOnClickListener {
            confirmCancelNavigationTarget()
        }
    }
    // ИЗМЕНЕНО (предрелизный аудит): шесть диалоговых функций основного
    // сценария (confirmStart/showStartConfirmDialog/confirmHome/
    // confirmManualArrival/confirmManualArrivalReset/showArrivedDialog)
    // вынесены в TrackingDialogs — здесь остались только ДЕЙСТВИЯ после
    // подтверждения. Заодно устранён дубль: "прибытие подтверждено" раньше
    // было написано дважды (в ручном и в автоматическом диалоге) —
    // теперь одна onArrivalConfirmed().
    private fun isLowAccuracy(): Boolean {
        val location = lastKnownLocation
        return location != null && location.accuracy > LOW_ACCURACY_THRESHOLD_METERS
    }
    private fun onStartConfirmed() {
        // lastKnownLocation читается в момент подтверждения, а не открытия
        // диалога — за время ответа пользователя фикс мог обновиться.
        lastKnownLocation?.let {
            viewModel.onStartConfirmed(it)
            trackingService?.updateMode(TrackingMode.RECORDING)
        }
    }
    private fun onHomeConfirmed() {
        viewModel.onHomeConfirmed()
        trackingService?.updateMode(TrackingMode.RETURNING)
    }
    private fun onArrivalConfirmed() {
        viewModel.onArrivedConfirmed()
        trackingService?.onArrivalDialogDismissed(confirmed = true)
        trackingService?.updateMode(TrackingMode.IDLE)
    }
    private fun onArrivalDeclined() {
        trackingService?.onArrivalDialogDismissed(confirmed = false)
    }
    private fun showArrivedDialog() {
        trackingDialogs.showArrived(::onArrivalConfirmed, ::onArrivalDeclined)
    }
    // ИЗМЕНЕНО (предрелизный аудит): два почти одинаковых диалога ввода
    // названия (для текущей геопозиции и для точки долгого тапа) заменены
    // одним PlaceDialogs.askPlaceName(); запись места и бэкап — в
    // MarkedPlaceRepository через MapViewModel.addMarkedPlace(), Activity
    // больше не ходит в MarkedPlaceDao и BackupManager напрямую.
    private fun showMarkPlaceDialog() {
        placeDialogs.askPlaceName { name ->
            // Геопозиция читается в момент подтверждения, а не открытия диалога.
            lastKnownLocation?.let { viewModel.addMarkedPlace(name, it.latitude, it.longitude) }
        }
    }
    // === "взятие направления" (долгий тап на карте) ===
    /** Точка входа в фичу — вызывается из mapController.onLongPress. Работает
     * в любом TrackingMode, диалог не завязан на режим. */
    private fun showLongPressActionDialog(latitude: Double, longitude: Double) {
        placeDialogs.showLongPressActions(
            onSavePlace = {
                placeDialogs.askPlaceName { name -> viewModel.addMarkedPlace(name, latitude, longitude) }
            },
            onTakeDirection = { takeDirectionTo(latitude, longitude) }
        )
    }
    /** Активирует "взятие направления": сохраняет цель, будит GPS-опрос в
     * сервисе (важно, если сейчас IDLE), обновляет UI (кнопка/маркер/линия).
     * Работает независимо от TrackingMode — RECORDING продолжает писать
     * трек как ни в чём не бывало. */
    private fun takeDirectionTo(latitude: Double, longitude: Double) {
        val app = application as TrailBackApp
        lifecycleScope.launch {
            app.trackingRepository.setNavigationTarget(latitude, longitude)
            trackingService?.onNavigationTargetChanged(true)
            refreshNavigationTargetUi()
        }
    }
    /** Ручная отмена через кнопку слева от главной. */
    private fun confirmCancelNavigationTarget() {
        trackingDialogs.confirmCancelDirection(::clearNavigationTarget)
    }
    /** Автоматический диалог прибытия — та же логика (радиус + серия фиксов
     * + точность), что и "Вы вернулись!" в режиме "Домой" (HomeArrivalDetector
     * переиспользуется в TrackingService как есть). */
    private fun showDirectionArrivedDialog() {
        trackingDialogs.showDirectionArrived(
            onConfirmed = {
                trackingService?.onDirectionArrivalDialogDismissed(confirmed = true)
                clearNavigationTarget()
            },
            onDeclined = { trackingService?.onDirectionArrivalDialogDismissed(confirmed = false) }
        )
    }
    private fun clearNavigationTarget() {
        val app = application as TrailBackApp
        lifecycleScope.launch {
            app.trackingRepository.clearNavigationTarget()
            trackingService?.onNavigationTargetChanged(false)
            refreshNavigationTargetUi()
            mapController.updateNavigationTargetLine(null, null)
        }
    }
    /** Синхронизирует локальный кэш navigationTarget с TrackingStateStore и
     * перерисовывает маркер/кнопку — вызывается после любого изменения цели
     * и при onCreate/onResume (восстановление после сна/краша процесса). */
    private fun refreshNavigationTargetUi() {
        val app = application as TrailBackApp
        navigationTarget = if (app.trackingStateStore.navigationTargetActive) {
            app.trackingStateStore.navigationTargetLatitude to app.trackingStateStore.navigationTargetLongitude
        } else {
            null
        }
        mapController.updateNavigationTargetMarker(navigationTarget)
        binding.cancelDirectionButton.visibility = if (navigationTarget != null) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.mode.collect { mode ->
                updateButtonForMode(mode)
            }
        }
        // НОВОЕ: прогресс копирования .map-файла (см. MapController.setupMap/
        // resolveFirstMapFile) — показываем текст только пока идёт реальное
        // копирование (percent != null), иначе прячем. Подписка живёт весь
        // жизненный цикл Activity (не только onResume), т.к. само
        // копирование может продолжаться и до первого onResume — это не
        // датчик/GPS, расхода ресурсов на простое обновление TextView нет.
        lifecycleScope.launch {
            mapController.mapLoadProgress.collect { percent ->
                if (percent != null) {
                    binding.mapLoadProgressText.visibility = android.view.View.VISIBLE
                    binding.mapLoadProgressText.text = getString(R.string.map_loading_progress, percent)
                } else {
                    binding.mapLoadProgressText.visibility = android.view.View.GONE
                }
            }
        }
        lifecycleScope.launch {
            viewModel.distanceMeters.collect {
                infoPanelController.updateRouteCounter()
            }
        }
        lifecycleScope.launch {
            viewModel.activeEntryPoint.collect { entryPoint ->
                mapController.updateEntryPointMarker(entryPoint)
            }
        }
        lifecycleScope.launch {
            // ВАЖНО: flatMapLatest, а не вложенный .collect { ... .collect {} }.
            // Вложенный collect блокировал внешний поток навсегда на первой
            // же точке входа — при повторной записи маршрута (новая точка
            // входа после "Я на месте") внешний коллектор не мог "дойти" до
            // новой подписки, поэтому трек переставал обновляться до полного
            // перезапуска приложения (см. решение по багу — п.5).
            // flatMapLatest сам отменяет предыдущую внутреннюю подписку при
            // каждой новой активной точке входа.
            //
            // НОВОЕ: .combine(viewModel.mode) — раньше updateTrackLine()
            // вызывался ТОЛЬКО когда сама БД точек трека эмитила изменение
            // (новая GPS-точка). При нажатии "Домой" запись в RECORDING
            // прекращается, новых точек для этой entryPointId больше не
            // появится — а линия должна в этот момент перекраситься в
            // полупрозрачную (см. MapController.updateTrackLine). Без
            // combine() это бы не происходило, пока не случится любое другое
            // событие, дёргающее points-поток. combine пересобирает пару
            // (points, mode) на изменение ЛЮБОГО из двух источников.
            viewModel.activeEntryPoint
                .flatMapLatest { entryPoint ->
                    if (entryPoint == null) {
                        flowOf(emptyList())
                    } else {
                        val app = application as TrailBackApp
                        app.trackingRepository.observeTrackForEntryPoint(entryPoint.id)
                    }
                }
                .combine(viewModel.mode) { points, mode -> points to mode }
                .collect { (points, mode) ->
                    mapController.updateTrackLine(points, mode)
                }
        }
        lifecycleScope.launch {
            viewModel.markedPlaces.collect { places ->
                mapController.updateMarkedPlaces(places)
            }
        }
    }
    private fun updateButtonForMode(mode: TrackingMode) {
        binding.mainActionButton.text = when (mode) {
            TrackingMode.IDLE -> getString(R.string.button_start)
            TrackingMode.RECORDING -> getString(R.string.button_home)
            TrackingMode.RETURNING -> getString(R.string.manual_arrival_button)
        }
    }
}
