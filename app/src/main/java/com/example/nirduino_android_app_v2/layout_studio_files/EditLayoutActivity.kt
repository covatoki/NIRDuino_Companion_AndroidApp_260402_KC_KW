package com.example.nirduino_android_app_v2.layout_studio_files

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nirduino_android_app_v2.R
import com.google.gson.Gson
import kotlinx.coroutines.launch

class EditLayoutActivity : AppCompatActivity() {

    private lateinit var gridView: PannableGridView
    private lateinit var zoomInButton: Button
    private lateinit var zoomOutButton: Button
    private lateinit var recenterButton: Button
    private lateinit var saveButton: Button
    private lateinit var mmLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_layout)

        gridView = findViewById(R.id.gridView)
        zoomInButton = findViewById(R.id.zoomInButton)
        zoomOutButton = findViewById(R.id.zoomOutButton)
        recenterButton = findViewById(R.id.recenterButton)
        saveButton = findViewById(R.id.saveButton)
        mmLabel = findViewById(R.id.mmLabel)

        val layoutItemJson = intent.getStringExtra("layoutItemJson")
        val layoutItem = layoutItemJson?.let { Gson().fromJson(it, LayoutStudioItem::class.java) }

        val loadFromStorage = intent.getBooleanExtra("loadFromStorage", false)
        val layoutStore = LayoutDataStore.getInstance(applicationContext)

        if (loadFromStorage && layoutItem != null) {
            lifecycleScope.launch {
                val saved = try {
                    layoutStore.loadOverlayElements(layoutItem.layoutName)
                } catch (e: Exception) {
                    Log.e("EditLayoutActivity", "Error loading layout: ${e.message}")
                    emptyList()
                }

                if (saved.isEmpty()) {
                    Toast.makeText(applicationContext, "New canvas created", Toast.LENGTH_LONG).show()
                    Log.w("EditLayoutActivity","No overlays saved for ${layoutItem.layoutName}")
                }

                // Only keep elements that are still part of this layout selection
                val currentSources = layoutItem.selectedSources.toSet()
                val currentDetectors = layoutItem.selectedDetectors.toSet()

                val merged = mutableListOf<OverlayElement>()
                val existingSourceIds = mutableSetOf<Int>()
                val existingDetectorIds = mutableSetOf<Int>()

                saved.forEach { e ->
                    if (e.isSource && e.id in currentSources) {
                        merged.add(e); existingSourceIds.add(e.id)
                    } else if (!e.isSource && e.id in currentDetectors) {
                        merged.add(e); existingDetectorIds.add(e.id)
                    }
                }

                // 👉 Seed missing ones IN MILLIMETERS (storage units), NOT pixels
                var xMm = 0f
                var yMm = 0f
                for (src in currentSources.sorted()) {
                    if (src !in existingSourceIds) {
                        merged.add(OverlayElement(xMm, yMm, true, src))
                        xMm += 10f  // 10 mm step between sources
                    }
                }

                xMm = 0f
                yMm = 20f  // place detectors 20 mm below sources
                for (det in currentDetectors.sorted()) {
                    if (det !in existingDetectorIds) {
                        merged.add(OverlayElement(xMm, yMm, false, det))
                        xMm += 10f  // 10 mm step between detectors
                    }
                }

                gridView.setOverlayElements(merged)
            }
        } else if (layoutItem != null) {
            gridView.loadFromLayoutItem(layoutItem)
        }

        zoomInButton.setOnClickListener { gridView.zoomIn();  updateMmLabel() }
        zoomOutButton.setOnClickListener { gridView.zoomOut(); updateMmLabel() }
        recenterButton.setOnClickListener { gridView.recenterOnItems() }

        saveButton.setOnClickListener {
            val li = layoutItem ?: run {
                Toast.makeText(this, "Layout not loaded", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 1) Snapshot positions from the view (in mm, storage space per your view’s contract)
            val elementsFromView = gridView.getOverlayElements().map { it.copy() }

            // --- Optional: UNFLIP Y back to a top-left origin if your view was flipped on load/set ---
            // Comment this whole block out if you intentionally want the mirrored Y.
            if (elementsFromView.isNotEmpty()) {
                val maxYmm = elementsFromView.maxOf { it.y }
                elementsFromView.forEach { e -> e.y = maxYmm - e.y }
            }
            // -----------------------------------------------------------------------------------------

            // 2) Round to 0.1 mm for stable storage (avoid tiny float noise)
            val elementsForStorage = elementsFromView.map { e ->
                e.copy(
                    x = kotlin.math.round(e.x * 10f) / 10f,
                    y = kotlin.math.round(e.y * 10f) / 10f
                )
            }

            // Debug logs so you can verify what’s being saved
            Log.d("EditLayoutActivity", "Saving ${elementsForStorage.size} overlay elements:")
            elementsForStorage.forEach {
                Log.d("EditLayoutActivity", "  id=${it.id} src=${it.isSource}  x(mm)=${it.x}  y(mm)=${it.y}")
            }

            lifecycleScope.launch {
                try {
                    // Save only THIS layout's overlays (keyed by layoutName)
                    layoutStore.saveOverlayElements(li.layoutName, elementsForStorage)

                    // Update lastUpdated in the layouts list (by name)
                    val allLayouts = layoutStore.loadLayoutItems().toMutableList()
                    val index = allLayouts.indexOfFirst { it.layoutName == li.layoutName }
                    if (index != -1) {
                        allLayouts[index].lastUpdated = System.currentTimeMillis()
                        layoutStore.saveLayoutItems(allLayouts)
                        Log.d("EditLayoutActivity", "Layout updated: ${li.layoutName}")
                        Toast.makeText(applicationContext, "Layout data saved!", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(applicationContext, "Layout not found!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("EditLayoutActivity", "Save failed: ${e.message}", e)
                    Toast.makeText(applicationContext, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        updateMmLabel()
    }

    private fun updateMmLabel() {
        mmLabel.text = "1 cell = ${gridView.getMmPerCell()} mm"
    }
}
