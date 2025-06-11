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
        val layoutItem = layoutItemJson?.let {
            Gson().fromJson(it, LayoutStudioItem::class.java)
        }

        val loadFromStorage = intent.getBooleanExtra("loadFromStorage", false)

        if (loadFromStorage && layoutItem != null) {
            lifecycleScope.launch {

                val saved = try {
                    LayoutDataStore.loadOverlayElements(this@EditLayoutActivity, layoutItem.layoutName)
                } catch (e: Exception) {
                    android.util.Log.e("EditLayoutActivity", "Error loading layout: ${e.message}")
                    emptyList()
                }

                if (saved.isEmpty()) {
                    Toast.makeText(applicationContext, "New canvas created", Toast.LENGTH_LONG).show()
                    Log.e("EditLayoutActivity","This layout no longer exists or has been deleted.")
                }

                val currentSources = layoutItem.selectedSources.toSet()
                val currentDetectors = layoutItem.selectedDetectors.toSet()

                val merged = mutableListOf<OverlayElement>()
                val existingSourceIds = mutableSetOf<Int>()
                val existingDetectorIds = mutableSetOf<Int>()

                // Keep saved sources/detectors that are still in current layout
                saved.forEach {
                    if (it.isSource && it.id in currentSources) {
                        merged.add(it)
                        existingSourceIds.add(it.id)
                    } else if (!it.isSource && it.id in currentDetectors) {
                        merged.add(it)
                        existingDetectorIds.add(it.id)
                    }
                }

                // Add new sources
                var x = 0f
                var y = 0f
                for (src in currentSources.sorted()) {
                    if (src !in existingSourceIds) {
                        merged.add(OverlayElement(x, y, true, src))
                        x += gridView.mmToPx(10f)
                    }
                }

                // Add new detectors
                x = 0f
                y = gridView.mmToPx(20f)
                for (det in currentDetectors.sorted()) {
                    if (det !in existingDetectorIds) {
                        merged.add(OverlayElement(x, y, false, det))
                        x += gridView.mmToPx(10f)
                    }
                }

                gridView.setOverlayElements(merged)
            }
        } else if (layoutItem != null) {
            gridView.loadFromLayoutItem(layoutItem)
        }

        zoomInButton.setOnClickListener {
            gridView.zoomIn()
            updateMmLabel()
        }

        zoomOutButton.setOnClickListener {
            gridView.zoomOut()
            updateMmLabel()
        }

        recenterButton.setOnClickListener {
            gridView.recenterOnItems()
        }

//        saveButton.setOnClickListener {
//            val elements = gridView.getOverlayElements()
//            lifecycleScope.launch {
//                // Save using Jetpack DataStore (asynchronously)
//                LayoutDataStore.saveOverlayElements(this@EditLayoutActivity, layoutItem!!.layoutName, elements)
//                android.util.Log.d("EditLayoutActivity", "Layout saved: ${elements.size} items")
//
//                // Show a toast to notify the user
//                Toast.makeText(applicationContext, "Layout data saved!", Toast.LENGTH_SHORT).show()
//
//                // Finish the current activity and return to the previous one
//                finish()
//            }
//        }

        saveButton.setOnClickListener {
            val elements = gridView.getOverlayElements()

            lifecycleScope.launch {
                // 1. Save overlay elements for this layout name
                LayoutDataStore.saveOverlayElements(this@EditLayoutActivity, layoutItem!!.layoutName, elements)

                // 2. Load the full list of layout items from DataStore
                val allLayouts = LayoutDataStore.loadLayoutItems(this@EditLayoutActivity).toMutableList()

                // 3. Find the matching item by layout name
                val index = allLayouts.indexOfFirst { it.layoutName == layoutItem!!.layoutName }
                if (index != -1) {
                    // 4. Update lastUpdated timestamp
                    allLayouts[index].lastUpdated = System.currentTimeMillis()

                    // 5. Save the modified list back
                    LayoutDataStore.saveLayoutItems(this@EditLayoutActivity, allLayouts)

                    // 6. Optional log and toast
                    android.util.Log.d("EditLayoutActivity", "Layout updated: ${layoutItem!!.layoutName}")
                    Toast.makeText(applicationContext, "Layout data saved!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(applicationContext, "Layout not found!", Toast.LENGTH_SHORT).show()
                }

                // 7. Close the activity
                finish()
            }
        }


        updateMmLabel()
    }

    private fun updateMmLabel() {
        mmLabel.text = "1 cell = ${gridView.getMmPerCell()} mm"
    }
}
