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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trailback.app.R
import com.trailback.app.TrailBackApp
import com.trailback.app.data.db.MarkedPlace
import com.trailback.app.databinding.ActivityMarkedPlacesBinding
import com.trailback.app.databinding.ItemMarkedPlaceRowBinding
import com.trailback.app.ui.map.MapActivity
import kotlinx.coroutines.launch
import java.util.Locale
class MarkedPlacesActivity : KeepScreenOnActivity() {
    private lateinit var binding: ActivityMarkedPlacesBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMarkedPlacesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val app = application as TrailBackApp
        binding.list.layoutManager = LinearLayoutManager(this)
        lifecycleScope.launch {
            app.database.markedPlaceDao().observeAll().collect { places ->
                binding.list.adapter = MarkedPlacesAdapter(
                    items = places,
                    onOpenInNavigation = ::openInNavigationApp,
                    onCopyCoordinates = ::copyCoordinates,
                    onShowOnMap = ::showOnMap,
                    onDeleteRequested = ::confirmDelete
                )
            }
        }
        // НОВОЕ: кнопка "+" — ручное добавление места по введённым координатам
        // (раньше места создавались только по текущей геопозиции или долгим
        // тапом на карте).
        binding.addPlaceButton.setOnClickListener { showAddPlaceDialog(app) }
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
    /** НОВОЕ: "Показать на карте" — открывает/поднимает MapActivity и
     * центрирует её на координатах места (см. MapController.centerOn).
     * MapActivity — launcher-активность с launchMode="singleTop", почти
     * всегда уже жива в стеке ниже текущего экрана, поэтому
     * FLAG_ACTIVITY_CLEAR_TOP поднимает именно её, не пересоздавая. */
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
        val app = application as TrailBackApp
        AlertDialog.Builder(this, R.style.TrailBackAlertDialogTheme)
            .setMessage(place.name)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ ->
                lifecycleScope.launch {
                    app.database.markedPlaceDao().delete(place)
                    app.backupManager.backupNow()
                }
            }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }
    /**
     * НОВОЕ: диалог ручного добавления места — два поля (название,
     * координаты вида "широта, долгота"), стиль — тот же
     * TrailBackAlertDialogTheme, что и у предупреждений (см. themes.xml).
     * Позитивная кнопка переопределяется вручную через setOnShowListener,
     * чтобы при невалидном вводе диалог НЕ закрывался сам (штатное
     * поведение AlertDialog — закрыть по любой кнопке), а показывал Toast
     * с ошибкой и оставался открытым для исправления.
     */
    private fun showAddPlaceDialog(app: TrailBackApp) {
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
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val coordinates = parseCoordinates(coordinatesInput.text?.toString())
                if (name.isEmpty() || coordinates == null) {
                    Toast.makeText(this, R.string.add_place_invalid_input, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val (latitude, longitude) = coordinates
                lifecycleScope.launch {
                    app.database.markedPlaceDao().insert(
                        MarkedPlace(
                            name = name,
                            latitude = latitude,
                            longitude = longitude,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    app.backupManager.backupNow()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }
    /** Разбирает строку вида "55.75124, 37.61842" (тот же формат, в котором
     * координаты показываются и копируются по всему приложению — см.
     * copyCoordinates выше) — null при любой некорректности (не то число
     * частей, не парсится как Double, широта/долгота вне допустимого
     * диапазона). */
    private fun parseCoordinates(raw: String?): Pair<Double, Double>? {
        val parts = raw?.split(",")?.map { it.trim() } ?: return null
        if (parts.size != 2) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }
}
class MarkedPlacesAdapter(
    private val items: List<MarkedPlace>,
    private val onOpenInNavigation: (MarkedPlace) -> Unit,
    private val onCopyCoordinates: (MarkedPlace) -> Unit,
    private val onShowOnMap: (MarkedPlace) -> Unit,
    private val onDeleteRequested: (MarkedPlace) -> Unit
) : RecyclerView.Adapter<MarkedPlacesAdapter.ViewHolder>() {
    inner class ViewHolder(val binding: ItemMarkedPlaceRowBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMarkedPlaceRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val place = items[position]
        holder.binding.nameText.text = place.name
        holder.binding.coordinatesText.text =
            String.format(Locale.getDefault(), "%.5f, %.5f", place.latitude, place.longitude)
        // ИЗМЕНЕНО: было три отдельные кнопки — теперь одна кнопка overflow
        // открывает PopupMenu (см. res/menu/marked_place_actions.xml).
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
    override fun getItemCount() = items.size
}
