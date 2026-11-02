package com.trailback.app.ui.menu
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
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
import com.trailback.app.data.db.MarkedPlace
import com.trailback.app.databinding.ActivityMarkedPlacesBinding
import com.trailback.app.databinding.ItemMarkedPlaceRowBinding
import com.trailback.app.ui.map.MapActivity
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * ИЗМЕНЕНО (предрелизный аудит): вся работа с MarkedPlaceDao и BackupManager
 * вынесена в MarkedPlacesViewModel — Activity теперь отвечает только за UI
 * (диалоги, PopupMenu, запуск geo:/mailto:/MapActivity интентов) и
 * подписку на places. См. MarkedPlacesViewModel для бизнес-логики.
 */
class MarkedPlacesActivity : KeepScreenOnActivity() {
    private lateinit var binding: ActivityMarkedPlacesBinding
    private lateinit var viewModel: MarkedPlacesViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMarkedPlacesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val app = application as TrailBackApp
        viewModel = ViewModelProvider(this, MarkedPlacesViewModel.Factory(app))[MarkedPlacesViewModel::class.java]
        binding.list.layoutManager = LinearLayoutManager(this)
        val adapter = MarkedPlacesAdapter(
            onOpenInNavigation = ::openInNavigationApp,
            onCopyCoordinates = ::copyCoordinates,
            onShowOnMap = ::showOnMap,
            onDeleteRequested = ::confirmDelete
        )
        binding.list.adapter = adapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.places.collect { places ->
                    adapter.submitList(places)
                }
            }
        }
        binding.addPlaceButton.setOnClickListener { showAddPlaceDialog() }
    }
    private fun openInNavigationApp(place: MarkedPlace) {
        // geo: URI — система сама предложит установленные навигационные приложения
        val uri = Uri.parse(
            "geo:${place.latitude},${place.longitude}?q=${place.latitude},${place.longitude}(${Uri.encode(place.name)})"
        )
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
    private fun copyCoordinates(place: MarkedPlace) {
        val text = "%.6f, %.6f".format(place.latitude, place.longitude)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("coordinates", text))
        Toast.makeText(this, R.string.coordinates_copied, Toast.LENGTH_SHORT).show()
    }
    /** "Показать на карте" — открывает/поднимает MapActivity и центрирует
     * её на координатах места (см. MapController.centerOn). MapActivity —
     * launcher-активность с launchMode="singleTop", почти всегда уже жива
     * в стеке ниже текущего экрана, поэтому FLAG_ACTIVITY_CLEAR_TOP
     * поднимает именно её, не пересоздавая. */
    private fun showOnMap(place: MarkedPlace) {
        val intent = Intent(this, MapActivity::class.java).apply {
            action = MapActivity.ACTION_CENTER_ON_PLACE
            putExtra(MapActivity.EXTRA_LATITUDE, place.latitude)
            putExtra(MapActivity.EXTRA_LONGITUDE, place.longitude)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }
    private fun confirmDelete(place: MarkedPlace) {
        AlertDialog.Builder(this, R.style.TrailBackAlertDialogTheme)
            .setMessage(place.name)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> viewModel.delete(place) }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }
    /**
     * Диалог ручного добавления места — два поля (название, координаты вида
     * "широта, долгота"), стиль — тот же TrailBackAlertDialogTheme, что и у
     * предупреждений. Позитивная кнопка переопределяется вручную через
     * setOnShowListener, чтобы при невалидном вводе диалог НЕ закрывался
     * сам (штатное поведение AlertDialog — закрыть по любой кнопке), а
     * показывал Toast с ошибкой и оставался открытым для исправления.
     * Валидация и сама вставка — в MarkedPlacesViewModel.addPlace(), Activity
     * здесь только читает поля ввода и реагирует на true/false результата.
     */
    private fun showAddPlaceDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_marked_place, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.nameInput)
        val coordinatesInput = dialogView.findViewById<EditText>(R.id.coordinatesInput)
        val dialog = AlertDialog.Builder(this, R.style.TrailBackAlertDialogTheme)
            .setTitle(R.string.add_place_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.add_place_save, null) // слушатель ниже, см. setOnShowListener
            .setNegativeButton(R.string.add_place_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val added = viewModel.addPlace(
                    name = nameInput.text?.toString().orEmpty(),
                    coordinatesRaw = coordinatesInput.text?.toString()
                )
                if (added) {
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, R.string.add_place_invalid_input, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }
}
/** ListAdapter + DiffUtil — создаётся единожды в onCreate, обновляется
 * через submitList() на каждую эмиссию places (см. предрелизный аудит:
 * раньше адаптер пересоздавался на каждое изменение БД целиком). */
class MarkedPlacesAdapter(
    private val onOpenInNavigation: (MarkedPlace) -> Unit,
    private val onCopyCoordinates: (MarkedPlace) -> Unit,
    private val onShowOnMap: (MarkedPlace) -> Unit,
    private val onDeleteRequested: (MarkedPlace) -> Unit
) : ListAdapter<MarkedPlace, MarkedPlacesAdapter.ViewHolder>(DIFF_CALLBACK) {
    inner class ViewHolder(val binding: ItemMarkedPlaceRowBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMarkedPlaceRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val place = getItem(position)
        holder.binding.nameText.text = place.name
        holder.binding.coordinatesText.text =
            String.format(Locale.getDefault(), "%.5f, %.5f", place.latitude, place.longitude)
        holder.binding.overflowButton.setOnClickListener { anchor ->
            val popup = PopupMenu(anchor.context, anchor)
            popup.menuInflater.inflate(R.menu.marked_place_actions, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_open_navigation -> { onOpenInNavigation(place); true }
                    R.id.action_copy_coordinates -> { onCopyCoordinates(place); true }
                    R.id.action_show_on_map -> { onShowOnMap(place); true }
                    R.id.action_delete -> { onDeleteRequested(place); true }
                    else -> false
                }
            }
            popup.show()
        }
    }
    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<MarkedPlace>() {
            override fun areItemsTheSame(oldItem: MarkedPlace, newItem: MarkedPlace) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MarkedPlace, newItem: MarkedPlace) = oldItem == newItem
        }
    }
}
