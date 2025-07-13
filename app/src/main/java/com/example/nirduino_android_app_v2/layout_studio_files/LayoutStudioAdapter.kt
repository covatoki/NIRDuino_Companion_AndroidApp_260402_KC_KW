package com.example.nirduino_android_app_v2.layout_studio_files

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LayoutStudioAdapter(
    private val adapterContext: Context,
    private val items: MutableList<LayoutStudioItem>,
    private val layoutStore: LayoutDataStore,
    private val onItemChangedListener: LayoutStudio.OnLayoutItemChangedListener
) : RecyclerView.Adapter<LayoutStudioAdapter.ViewHolder>() {

    private val cachedOverlayMap = mutableMapOf<String, List<OverlayElement>>()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutNameEditText: EditText = view.findViewById(R.id.editLayoutName)
        val deleteLayoutButton: ImageButton = view.findViewById(R.id.deleteButton)
        val updateLayoutButton: Button = view.findViewById(R.id.updateLayoutButton)
        val view2DDataButton: Button = view.findViewById(R.id.btnView2DData)
        val duplicateLayoutButton: ImageButton = view.findViewById(R.id.duplicateButton)
        val lastUpdatedDateText: TextView = view.findViewById(R.id.lastUpdated)
        val downloadLayoutDataButton: ImageButton = view.findViewById(R.id.downloadButton)

        val sources: List<CheckBox> = (1..8).map {
            view.findViewById(view.resources.getIdentifier("source$it", "id", view.context.packageName))
        }
        val detectors: List<CheckBox> = (1..16).map {
            view.findViewById(view.resources.getIdentifier("detector$it", "id", view.context.packageName))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.listitem_layoutstudio, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, @SuppressLint("RecyclerView") position: Int) {

        if (position >= items.size || position < 0) {
            return
        }

        val item = items[position]

        val timeZone = TimeZone.getDefault()
        val dateFormat = SimpleDateFormat("MMMM dd, yyyy 'at' HH:mm:ss z", Locale.getDefault())
        dateFormat.timeZone = timeZone
        val formattedDate = dateFormat.format(Date(item.lastUpdated))
        holder.lastUpdatedDateText.text = "Last updated: $formattedDate"

        if (!cachedOverlayMap.containsKey(item.layoutName)) {
            CoroutineScope(Dispatchers.IO).launch {
                val saved = layoutStore.loadOverlayElements(item.layoutName)
                cachedOverlayMap[item.layoutName] = saved

                CoroutineScope(Dispatchers.Main).launch {
                    notifyItemChanged(position)
                }
            }
        }

        holder.layoutNameEditText.setText(item.layoutName)
        holder.layoutNameEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s.toString() != item.layoutName) {
                    val oldLayoutName = item.layoutName
                    item.layoutName = s.toString()

                    CoroutineScope(Dispatchers.IO).launch {
                        layoutStore.saveLayoutItems(items)
                        val savedOverlays = layoutStore.loadOverlayElements(oldLayoutName)
                        layoutStore.saveOverlayElements(item.layoutName, savedOverlays)
                    }

                    val currentPosition = holder.adapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION) {
                        onItemChangedListener.onLayoutItemUpdated(currentPosition, items[currentPosition])
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        holder.sources.forEachIndexed { index, checkBox ->
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = item.selectedSources.contains(index + 1)
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) item.selectedSources.add(index + 1)
                else item.selectedSources.remove(index + 1)
                val currentPosition = holder.adapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    onItemChangedListener.onLayoutItemUpdated(currentPosition, items[currentPosition])
                }
            }
        }

        holder.detectors.forEachIndexed { index, checkBox ->
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = item.selectedDetectors.contains(index + 1)
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) item.selectedDetectors.add(index + 1)
                else item.selectedDetectors.remove(index + 1)
                onItemChangedListener.onLayoutItemUpdated(position, item)
            }
        }

        holder.deleteLayoutButton.setOnClickListener {
            showDeleteConfirmation(adapterContext) {
                val currentPosition = holder.adapterPosition
                if (currentPosition != RecyclerView.NO_POSITION && currentPosition < items.size) {
                    val layoutNameToDelete = items[currentPosition].layoutName
                    items.removeAt(currentPosition)
                    notifyItemRemoved(currentPosition)

                    CoroutineScope(Dispatchers.IO).launch {
                        layoutStore.deleteOverlayElements(layoutNameToDelete)
                        layoutStore.deleteLayoutItems(layoutNameToDelete)
                    }
                }
            }
        }

        holder.updateLayoutButton.setOnClickListener {
            val context = holder.itemView.context

            if (item.layoutName.isBlank()) {
                Toast.makeText(context, "This layout has no valid name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!item.layoutStatus) item.layoutStatus = true

            val intent = Intent(context, EditLayoutActivity::class.java)
            intent.putExtra("layoutItemJson", Gson().toJson(item))
            intent.putExtra("loadFromStorage", true)
            context.startActivity(intent)
        }

        holder.view2DDataButton.setOnClickListener {
            val context = holder.itemView.context
            val layoutName = item.layoutName

            val savedOverlays = runBlocking {
                layoutStore.loadOverlayElements(layoutName)
            }

            val dpi = context.resources.displayMetrics.xdpi
            fun pxToMm(px: Float): Float = px * 25.4f / dpi

            val matchingItems = savedOverlays.filter {
                (it.isSource && item.selectedSources.contains(it.id)) ||
                        (!it.isSource && item.selectedDetectors.contains(it.id))
            }

            val message = buildString {
                if (matchingItems.isNotEmpty()) {
                    append("Sources:\n")
                    matchingItems.filter { it.isSource }.sortedBy { it.id }.forEach {
                        val xMm = pxToMm(it.x)
                        val yMm = pxToMm(it.y)
                        append("S${it.id}: x=${"%.1f".format(xMm)} mm, y=${"%.1f".format(yMm)} mm\n")
                    }
                    append("\nDetectors:\n")
                    matchingItems.filter { !it.isSource }.sortedBy { it.id }.forEach {
                        val xMm = pxToMm(it.x)
                        val yMm = pxToMm(it.y)
                        append("D${it.id}: x=${"%.1f".format(xMm)} mm, y=${"%.1f".format(yMm)} mm\n")
                    }
                } else {
                    append("No saved coordinates for this layout yet.\n")
                }
            }

            AlertDialog.Builder(context)
                .setTitle("2D Layout Coordinates (in mm)")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }

        holder.duplicateLayoutButton.setOnClickListener {
            val original = items[position]
            val copy = original.copy(
                layoutName = original.layoutName + " (copy)",
                layoutStatus = original.layoutStatus,
                selectedSources = original.selectedSources.toMutableSet(),
                selectedDetectors = original.selectedDetectors.toMutableSet()
            )

            CoroutineScope(Dispatchers.IO).launch {
                val originalOverlays = layoutStore.loadOverlayElements(original.layoutName)
                layoutStore.saveOverlayElements(copy.layoutName, originalOverlays)

                CoroutineScope(Dispatchers.Main).launch {
                    addItem(copy)
                }
            }
        }

        holder.downloadLayoutDataButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val layout = items[position]
                    val overlays = layoutStore.loadOverlayElements(layout.layoutName)

                    val exportObject = mapOf(
                        "layoutID" to layout.layoutId.toString(),
                        "layoutName" to layout.layoutName,
                        "layoutStatus" to layout.layoutStatus,
                        "selectedSources" to layout.selectedSources.toList().sorted(),
                        "selectedDetectors" to layout.selectedDetectors.toList().sorted(),
                        "lastUpdated" to layout.lastUpdated,
                        "overlayElements" to overlays.map {
                            mapOf(
                                "id" to it.id,
                                "isSource" to it.isSource,
                                "x" to it.x,
                                "y" to it.y
                            )
                        }
                    )

                    val json = Gson().toJson(exportObject)
                    val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
                    val targetDir = java.io.File(docsDir, "NIRDuinoCompanion/Layouts")
                    if (!targetDir.exists()) targetDir.mkdirs()

                    val fileName = layout.layoutName.replace("""[^\w\s-]""".toRegex(), "_") + ".json"
                    val outputFile = java.io.File(targetDir, fileName)

                    outputFile.writeText(json)

                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(adapterContext, "Saved to ${outputFile.absolutePath}", Toast.LENGTH_LONG).show()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(adapterContext, "Failed to export layout", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun addItem(item: LayoutStudioItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun showDeleteConfirmation(context: Context, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Confirm Deletion")
            .setMessage("Are you sure you wish to delete this layout?")
            .setPositiveButton("Yes") { dialog, _ ->
                dialog.dismiss()
                onConfirm()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun getItems(): List<LayoutStudioItem> = items

    fun setItems(newItems: MutableList<LayoutStudioItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}