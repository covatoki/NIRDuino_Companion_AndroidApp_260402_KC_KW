package com.example.nirduino_android_app_v2.layout_studio_files

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LayoutStudio : AppCompatActivity() {

    private lateinit var adapter: LayoutStudioAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var layoutStore: LayoutDataStore

    // Prevent overlapping saves/imports
    @Volatile private var isImporting = false
    private val saveMutex = Mutex()

    // ----- File picker (accept both common JSON mime types) -----
    private val openJsonFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        lifecycleScope.launch {
            try {
                // Persist permission when possible; some providers don't support it.
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) { /* ignore for one-shot read */ }

                // ---- Heavy work off the main thread ----
                val importResult = withContext(Dispatchers.IO) {
                    // Optional: guard against huge files
                    val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
                    if (size > 5_000_000) throw IllegalArgumentException("JSON file too large")

                    fun readOnce(): String? =
                        contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }

                    val json = readOnce() ?: run {
                        // tiny retry for flaky providers returning empty stream once
                        Thread.sleep(50)
                        readOnce()
                    } ?: throw IllegalArgumentException("Could not read selected file")

                    try {
                        val gson = Gson()

                        // Parse the top-level strongly
                        val importedItem = gson.fromJson(json, LayoutStudioItem::class.java)
                            ?: throw IllegalArgumentException("Invalid LayoutStudioItem")

                        // Parse overlay robustly (Number → Float, etc.)
                        val jsonMap: Map<*, *> = gson.fromJson(json, Map::class.java)
                        val rawOverlayList = (jsonMap["overlayElements"] as? List<*>)?.mapNotNull { elem ->
                            val m = elem as? Map<*, *> ?: return@mapNotNull null
                            val idNum = m["id"] as? Number ?: return@mapNotNull null
                            val isSource = m["isSource"] as? Boolean ?: return@mapNotNull null
                            val xNum = m["x"] as? Number ?: return@mapNotNull null
                            val yNum = m["y"] as? Number ?: return@mapNotNull null
                            OverlayElement(
                                id = idNum.toInt(),
                                isSource = isSource,
                                x = xNum.toFloat(),
                                y = yNum.toFloat()
                            )
                        } ?: emptyList()

                        val normalized = normalizeOverlayElements(rawOverlayList)

                        ImportedLayoutPayload(importedItem, normalized)
                    } catch (e: JsonSyntaxException) {
                        throw IllegalArgumentException("Malformed JSON")
                    }
                }

                // ---- Back on main: update adapter state, then single sequenced save ----
                isImporting = true
                val canonical = canonicalName(importResult.item.layoutName)
                val canonicalItem = importResult.item.copy(layoutName = canonical)

                // Build a new list replacing / appending by canonical name (case-insensitive)
                val current = adapter.getItems().toMutableList()
                val idx = current.indexOfFirst { canonicalName(it.layoutName).equals(canonical, ignoreCase = true) }
                if (idx >= 0) {
                    current[idx] = canonicalItem
                } else {
                    current.add(canonicalItem)
                }
                adapter.setItems(current) // single UI refresh

                // Single, ordered save guarded by a Mutex to avoid interleaving with other writes
                saveMutex.withLock {
                    layoutStore.saveLayoutItems(adapter.getItems())
                    layoutStore.saveOverlayElements(canonicalItem.layoutName, importResult.overlay)
                }

                Toast.makeText(this@LayoutStudio, "Layout imported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@LayoutStudio, "Import error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isImporting = false
            }
        }
    }

    private data class ImportedLayoutPayload(
        val item: LayoutStudioItem,
        val overlay: List<OverlayElement>
    )

    // ---- Normalize negatives by shifting so minX/minY == 0 (only if needed) ----
    private fun normalizeOverlayElements(list: List<OverlayElement>): List<OverlayElement> {
        if (list.isEmpty()) return list
        val minX = list.minOf { it.x }
        val minY = list.minOf { it.y }
        val shiftX = if (minX < 0f) -minX else 0f
        val shiftY = if (minY < 0f) -minY else 0f
        if (shiftX == 0f && shiftY == 0f) return list
        return list.map { e -> e.copy(x = e.x + shiftX, y = e.y + shiftY) }
    }

    private fun canonicalName(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_layout_studio)

        layoutStore = LayoutDataStore.getInstance(applicationContext)

        recyclerView = findViewById(R.id.layoutRecyclerView)
        fab = findViewById(R.id.floatingActionButton)

        adapter = LayoutStudioAdapter(
            this,
            mutableListOf(),
            layoutStore,
            object : OnLayoutItemChangedListener {
                override fun onLayoutItemUpdated(position: Int, item: LayoutStudioItem) {
                    // Skip auto-saves while importing to prevent interleaving writes
                    if (isImporting) return
                    lifecycleScope.launch {
                        saveMutex.withLock {
                            layoutStore.saveLayoutItems(adapter.getItems())
                        }
                    }
                }
            }
        )
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Load items without blocking UI
        lifecycleScope.launch {
            val loaded = layoutStore.loadLayoutItems()
            adapter.setItems(loaded.toMutableList())
        }

        fab.setOnClickListener {
            val options = arrayOf("Create blank layout", "Import from file")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select Action")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val newItem = LayoutStudioItem(
                                layoutName = "Temporary name",
                                selectedSources = mutableSetOf(),
                                selectedDetectors = mutableSetOf(),
                                layoutStatus = false
                            )
                            val current = adapter.getItems().toMutableList()
                            current.add(newItem)
                            adapter.setItems(current)
                            lifecycleScope.launch {
                                saveMutex.withLock {
                                    layoutStore.saveLayoutItems(adapter.getItems())
                                    layoutStore.saveOverlayElements(newItem.layoutName, emptyList())
                                }
                            }
                        }
                        1 -> pickLayoutJsonFile()
                    }
                }
                .show()
        }
    }

    private fun pickLayoutJsonFile() {
        openJsonFileLauncher.launch(arrayOf("application/json", "text/json"))
    }

    interface OnLayoutItemChangedListener {
        fun onLayoutItemUpdated(position: Int, item: LayoutStudioItem)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val updatedItems = layoutStore.loadLayoutItems()
            adapter.setItems(updatedItems.toMutableList())
        }
    }
}
