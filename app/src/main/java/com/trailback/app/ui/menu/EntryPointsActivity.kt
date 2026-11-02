package com.trailback.app.ui.menu
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.trailback.app.ui.common.KeepScreenOnActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.trailback.app.R
import com.trailback.app.TrailBackApp
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.databinding.ActivityEntryPointsBinding
import com.trailback.app.databinding.ItemMenuRowBinding
import com.trailback.app.service.TrackingService
import kotlinx.coroutines.launch

/**
 * ИЗМЕНЕНО (предрелизный аудит): доступ к TrackingRepository вынесен в
 * EntryPointsViewModel — Activity теперь отвечает только за UI (диалоги,
 * список) и за байндинг к TrackingService (системная Android-подписка,
 * осознанно оставлена здесь — см. комментарий в EntryPointsViewModel).
 */
class EntryPointsActivity : KeepScreenOnActivity() {
    private lateinit var binding: ActivityEntryPointsBinding
    private lateinit var viewModel: EntryPointsViewModel
    private var trackingService: TrackingService? = null
    private var isServiceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            trackingService = (binder as TrackingService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            trackingService = null
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEntryPointsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val app = application as TrailBackApp
        viewModel = ViewModelProvider(this, EntryPointsViewModel.Factory(app))[EntryPointsViewModel::class.java]
        binding.list.layoutManager = LinearLayoutManager(this)
        val adapter = EntryPointsAdapter(
            onTap = ::onEntryPointTapped,
            onLongPress = ::copyCoordinates
        )
        binding.list.adapter = adapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.entryPoints.collect { points ->
                    adapter.submitList(points)
                }
            }
        }
        binding.clearAllButton.setOnClickListener {
            showClearConfirmation1()
        }
    }
    override fun onStart() {
        super.onStart()
        Intent(this, TrackingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isServiceBound = true
        }
    }
    override fun onStop() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onStop()
    }
    /**
     * По тапу — диалог "Выбрать эту точку?". Смена активной точки
     * заблокирована в ЛЮБОМ активном режиме — и RECORDING, и RETURNING
     * (изменено по решению: раньше блокировался только RETURNING, но выбор
     * старой точки во время активной записи "подвешивал" текущий трек, не
     * давая его ни продолжить, ни корректно завершить через "Я на месте").
     * При подтверждении выбора приложение переходит в режим "Домой" на
     * выбранную точку — вместе с точкой подгружается и её трек (постоянное
     * хранение, см. решение по ТЗ), отрисовывается уже полупрозрачным как
     * архивный (см. MapController.updateTrackLine).
     */
    private fun onEntryPointTapped(point: EntryPoint) {
        if (viewModel.isSelectionLocked()) {
            Toast.makeText(this, R.string.select_entry_point_locked_active_mode, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, R.style.TrailBackAlertDialogTheme)
            .setTitle(R.string.select_entry_point_title)
            .setMessage(point.name)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ ->
                viewModel.selectEntryPoint(point.id) {
                    trackingService?.updateMode(TrackingMode.RETURNING)
                    Toast.makeText(this, R.string.entry_point_set_active, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }
    private fun copyCoordinates(point: EntryPoint) {
        val text = "%.6f, %.6f".format(point.latitude, point.longitude)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("coordinates", text))
        Toast.makeText(this, R.string.coordinates_copied, Toast.LENGTH_SHORT).show()
    }
    /** Тройное подтверждение массовой очистки (см. п.6.3 ТЗ и решение по формулировкам). */
    private fun showClearConfirmation1() {
        AlertDialog.Builder(this, R.style.TrailBackAlertDialogTheme)
            .setMessage(R.string.clear_entry_points_warning_1)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> showClearConfirmation2() }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }
    private fun showClearConfirmation2() {
        AlertDialog.Builder(this, R.style.TrailBackAlertDialogTheme)
            .setMessage(R.string.clear_entry_points_warning_2)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> showClearConfirmation3() }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }
    private fun showClearConfirmation3() {
        AlertDialog.Builder(this, R.style.TrailBackAlertDialogTheme)
            .setMessage(R.string.clear_entry_points_warning_3)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> viewModel.clearAllEntryPoints() }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }
}
/** ListAdapter + DiffUtil — см. предрелизный аудит (раньше адаптер
 * пересоздавался целиком на каждую эмиссию). */
class EntryPointsAdapter(
    private val onTap: (EntryPoint) -> Unit,
    private val onLongPress: (EntryPoint) -> Unit
) : ListAdapter<EntryPoint, EntryPointsAdapter.ViewHolder>(DIFF_CALLBACK) {
    inner class ViewHolder(val binding: ItemMenuRowBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMenuRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val point = getItem(position)
        // Дата/время уже часть point.name (см. решение по ТЗ — формат
        // "Вход - сб. 05.09.26 г. 14:07"), координаты в списке не нужны.
        holder.binding.titleText.text = point.name
        holder.binding.iconImage.setImageResource(R.drawable.ic_flag)
        holder.binding.root.setOnClickListener { onTap(point) }
        holder.binding.root.setOnLongClickListener {
            onLongPress(point)
            true
        }
    }
    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<EntryPoint>() {
            override fun areItemsTheSame(oldItem: EntryPoint, newItem: EntryPoint) = oldItem.id == newItem.id
            // EntryPoint — data class, equals() сравнивает все поля структурно.
            override fun areContentsTheSame(oldItem: EntryPoint, newItem: EntryPoint) = oldItem == newItem
        }
    }
}
