package com.example.nirduino_android_app_v2.snirf_convert_files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.participant_manager.data.local.SecureParticipantDb
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*

class SNIRFConverter : AppCompatActivity() {

    private lateinit var spinnerParticipant: Spinner
    private lateinit var buttonPickJSON: Button
    private lateinit var textJSONName: TextView
    private lateinit var fileQaTextView: TextView
    private lateinit var buttonGenerate: Button
    private lateinit var outputTextView: TextView

    private lateinit var pickJsonLauncher: ActivityResultLauncher<Intent>
    private var jsonUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snirf_converter)

        // Views from your XML
        spinnerParticipant = findViewById(R.id.spinner_participant)
        buttonPickJSON     = findViewById(R.id.button_pick_json)
        textJSONName       = findViewById(R.id.text_json_name)
        fileQaTextView     = findViewById(R.id.fileQaTextView)
        buttonGenerate     = findViewById(R.id.button_generate_snirf)
        outputTextView     = findViewById(R.id.outputTextView)

        populateParticipantSpinner()

        // JSON picker
        pickJsonLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                jsonUri = uri
                uri?.let {
                    textJSONName.text = "Selected File: ${getFileName(it)}"
                    // Run QA immediately after selection
                    runCatching { qaJson(it) }
                        .onFailure { e ->
                            fileQaTextView.text = "QA failed: ${e.message}"
                            Log.e("SNIRF_QA", "QA error", e)
                        }
                } ?: run {
                    textJSONName.text = "No file selected"
                    fileQaTextView.text = ""
                }
            }
        }

        buttonPickJSON.setOnClickListener { pickJSON() }
        buttonGenerate.setOnClickListener { onGenerateClicked() }
    }

    // ───────────── helpers ─────────────

    private fun populateParticipantSpinner() = CoroutineScope(Dispatchers.IO).launch {
        val list = SecureParticipantDb.get(this@SNIRFConverter)
            .participantDao()
            .getAll()
            .sortedBy { it.subjectId }
        val labels = list.map { p -> "${p.subjectId} – ${p.age} y /${p.sex}" }
        withContext(Dispatchers.Main) {
            spinnerParticipant.adapter = ArrayAdapter(
                this@SNIRFConverter,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
            spinnerParticipant.tag = list // stash full objects for later
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun readTextFromUri(uri: Uri): String =
        contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText().orEmpty() }

    // ───────────── pickers ─────────────

    private fun pickJSON() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/json"))
        }
        pickJsonLauncher.launch(intent)
    }

    // ───────────── QA & Generate ─────────────

    private fun qaJson(uri: Uri) {
        val raw = readTextFromUri(uri)
        val root = JsonParser.parseString(raw).asJsonObject

        val layoutName = root.get("layoutName")?.asString ?: "(missing)"
        val deviceAlias = root.get("deviceAlias")?.asString ?: "(missing)"

        // layout → unique sourceId/detectorId
        val layoutArr = root.getAsJsonArray("layout")
            ?: error("Missing 'layout' array")
        val sources = mutableSetOf<Int>()
        val detectors = mutableSetOf<Int>()
        var potentialSourceYBug = false

        layoutArr.forEach { el ->
            val obj = el.asJsonObject
            obj.get("sourceId")?.asInt?.let { sources.add(it) }
            obj.get("detectorId")?.asInt?.let { detectors.add(it) }

            // Sanity check for the reported export typo ("sourceY" set from sourceX)
            val sx = obj.get("sourceX")?.asDouble
            val sy = obj.get("sourceY")?.asDouble
            if (sx != null && sy != null && sx == sy) potentialSourceYBug = true
        }

        // rounds → count + stimuli labels
        val roundsArr = root.getAsJsonArray("rounds") ?: error("Missing 'rounds' array")
        val numRounds = roundsArr.size()
        val allStimLabels = mutableListOf<String>()

        roundsArr.forEach { rEl ->
            val rObj = rEl.asJsonObject
            val stimArr = rObj.getAsJsonArray("stimuli")
            stimArr?.forEach { sEl ->
                val sObj = sEl.asJsonObject
                sObj.get("label")?.asString?.let { allStimLabels.add(it) }
            }
        }
        val uniqueStimuli = allStimLabels.groupingBy { it }.eachCount() // label → count

        val sb = StringBuilder()
            .appendLine("Layout Name: $layoutName")
            .appendLine("Device Alias: $deviceAlias")
            .appendLine("Sources: ${sources.size}")
            .appendLine("Detectors: ${detectors.size}")
            .appendLine("Rounds: $numRounds")

        if (uniqueStimuli.isEmpty()) {
            sb.appendLine("Stimuli: (none)")
        } else {
            sb.appendLine("Stimuli:")
            uniqueStimuli.forEach { (label, count) ->
                sb.appendLine("  • $label × $count")
            }
        }

        if (potentialSourceYBug) {
            sb.appendLine()
            sb.appendLine("⚠️ QA Note: Many layout entries have sourceY == sourceX.")
            sb.appendLine("   Check your export: \"sourceY\" may have been set from sourceX.")
        }

        val summary = sb.toString()
        fileQaTextView.text = summary
        Log.d("SNIRF_QA", summary)
    }

    private fun onGenerateClicked() {
        val participant =
            (spinnerParticipant.tag as? List<*>)?.get(spinnerParticipant.selectedItemPosition)
                    as? com.example.nirduino_android_app_v2.participant_manager.data.local.Participant

        when {
            participant == null -> { toast("Pick a participant."); return }
            jsonUri == null     -> { toast("Choose the data JSON file."); return }
        }

        // Log participant for traceability (optional)
        Log.d("SNIRF", "Participant (JSON): ${Gson().toJson(participant)}")

        // You can proceed with SNIRF generation using jsonUri + participant here.
        // e.g., buildSnirfFromJson(jsonUri!!, participant, ...)

        outputTextView.text = "Ready to generate SNIRF from selected JSON."
        toast("✓ QA complete. Ready to generate SNIRF.")
    }

    private fun JsonObject.getAsJsonArrayOrNull(name: String) =
        if (this.has(name) && this.get(name).isJsonArray) this.getAsJsonArray(name) else null

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
