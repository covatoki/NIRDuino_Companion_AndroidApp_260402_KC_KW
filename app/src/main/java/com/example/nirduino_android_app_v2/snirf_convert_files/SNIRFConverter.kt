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
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class SNIRFConverter : AppCompatActivity() {

    private lateinit var spinnerParticipant: Spinner
    private lateinit var buttonPickJSON: Button
    private lateinit var textJSONName: TextView
    private lateinit var fileQaTextView: TextView
    private lateinit var buttonGenerate: Button
    private lateinit var outputTextView: TextView

    private lateinit var pickJsonLauncher: ActivityResultLauncher<Intent>
    private var jsonUri: Uri? = null

    private val cloudRunBaseUrl =
        "https://data2snirf-fcr5zisowq-vp.a.run.app"

    // replace your httpClient with this
    private val httpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectionSpecs(
                listOf(
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2) // both OK
                        .build(),
                    ConnectionSpec.CLEARTEXT // harmless, in case you have other http calls
                )
            )
            .build()
    }

    private fun getDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else uri.lastPathSegment ?: "unknown"
            } ?: "unknown"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "unknown"
        }
    }

    fun Context.hasInternetConnection(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

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
        buttonGenerate.setOnClickListener {

            if (hasInternetConnection()) {
                onGenerateClicked()
            } else {
                toast("No internet or captive portal")
            }

            }
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

        val layoutName   = root.get("layoutName")?.asString ?: "(missing)"
        val deviceAlias  = root.get("deviceAlias")?.asString ?: "(missing)"
        val samplingHz   = root.get("samplingHz_est")?.asDouble
        val csvPath      = root.get("csvPath")?.asString ?: ""

        // --- Channels (new schema) ---
        val channelsArr = root.getAsJsonArray("channels")
            ?: error("Missing 'channels' array")

        val sources = mutableSetOf<Int>()
        val detectors = mutableSetOf<Int>()
        var longCount = 0
        var shortCount = 0
        var potentialSourceYBug = false
        var sourceYBugHits = 0

        channelsArr.forEach { el ->
            val obj = el.asJsonObject
            obj.get("sourceId")?.asInt?.let { sources.add(it) }
            obj.get("detectorId")?.asInt?.let { detectors.add(it) }

            when (obj.get("type")?.asString?.uppercase()) {
                "LONG" -> longCount++
                "SHORT" -> shortCount++
            }

            val sx = obj.get("sourceX")?.asDouble
            val sy = obj.get("sourceY")?.asDouble
            if (sx != null && sy != null && sx == sy) {
                potentialSourceYBug = true
                sourceYBugHits++
            }
        }
        val channelCount = channelsArr.size()

        // --- Rounds ---
        val roundsArr = root.getAsJsonArray("rounds") ?: error("Missing 'rounds' array")
        val reportedRoundCount = root.get("roundCount")?.asInt
        val actualRoundCount = roundsArr.size()

        // Stimuli label collection + light consistency checks
        val allStimLabels = mutableListOf<String>()
        var roundsWithTimestampMismatch = 0
        var roundsWithRedWidthMismatch = 0
        var roundsWithIrWidthMismatch = 0
        var roundsWithNSamplesMismatch = 0

        roundsArr.forEachIndexed { idx, rEl ->
            val rObj = rEl.asJsonObject

            val nSamples = rObj.get("nSamples")?.asInt
            val timestamps = rObj.getAsJsonArray("timestamps") ?: JsonArray()
            val redData = rObj.getAsJsonArray("redData") ?: JsonArray()
            val irData  = rObj.getAsJsonArray("irData")  ?: JsonArray()

            // Stimuli summary
            rObj.getAsJsonArray("stimuli")?.forEach { sEl ->
                val sObj = sEl.asJsonObject
                sObj.get("label")?.asString?.let { allStimLabels.add(it) }
            }

            // Consistency checks (lightweight to avoid huge iteration):
            // 1) nSamples matches timestamps.size
            if (nSamples != null && nSamples != timestamps.size()) {
                roundsWithTimestampMismatch++
            }
            // 2) nSamples matches red/ir row counts
            if (nSamples != null && (nSamples != redData.size() || nSamples != irData.size())) {
                roundsWithNSamplesMismatch++
            }
            // 3) red/ir vector width equals channelCount (check first row only, if present)
            if (redData.size() > 0) {
                val firstRedRow = redData[0].asJsonArray
                if (firstRedRow.size() != channelCount) roundsWithRedWidthMismatch++
            }
            if (irData.size() > 0) {
                val firstIrRow = irData[0].asJsonArray
                if (firstIrRow.size() != channelCount) roundsWithIrWidthMismatch++
            }
        }

        val uniqueStimuli = allStimLabels.groupingBy { it }.eachCount()

        // --- Build summary ---
        val sb = StringBuilder()
            .appendLine("Layout Name: $layoutName")
            .appendLine("Device Alias: $deviceAlias")
            .appendLine("Channels: $channelCount  (LONG: $longCount, SHORT: $shortCount)")
            .appendLine("Unique Sources: ${sources.size}")
            .appendLine("Unique Detectors: ${detectors.size}")
            .apply {
                if (samplingHz != null) appendLine("Estimated Sampling (Hz): $samplingHz")
                if (csvPath.isNotBlank()) appendLine("CSV Path: $csvPath")
            }
            .appendLine("Rounds: $actualRoundCount" + (reportedRoundCount?.let { " (reported: $it)" } ?: ""))

        if (uniqueStimuli.isEmpty()) {
            sb.appendLine("Stimuli: (none)")
        } else {
            sb.appendLine("Stimuli:")
            uniqueStimuli.forEach { (label, count) ->
                sb.appendLine("  • $label × $count")
            }
        }

        // Issues
        val anyIssues = (reportedRoundCount != null && reportedRoundCount != actualRoundCount) ||
                potentialSourceYBug ||
                roundsWithTimestampMismatch > 0 ||
                roundsWithNSamplesMismatch > 0 ||
                roundsWithRedWidthMismatch > 0 ||
                roundsWithIrWidthMismatch > 0

        if (anyIssues) {
            sb.appendLine()
            if (reportedRoundCount != null && reportedRoundCount != actualRoundCount) {
                sb.appendLine("⚠️ QA: roundCount ($reportedRoundCount) ≠ actual rounds ($actualRoundCount).")
            }
            if (potentialSourceYBug) {
                sb.appendLine("⚠️ QA: $sourceYBugHits channel(s) have sourceY == sourceX; verify export of sourceY.")
            }
            if (roundsWithTimestampMismatch > 0) {
                sb.appendLine("⚠️ QA: $roundsWithTimestampMismatch round(s) with nSamples ≠ timestamps.size.")
            }
            if (roundsWithNSamplesMismatch > 0) {
                sb.appendLine("⚠️ QA: $roundsWithNSamplesMismatch round(s) with nSamples ≠ red/ir row counts.")
            }
            if (roundsWithRedWidthMismatch > 0) {
                sb.appendLine("⚠️ QA: $roundsWithRedWidthMismatch round(s) where redData[0].size ≠ channelCount ($channelCount).")
            }
            if (roundsWithIrWidthMismatch > 0) {
                sb.appendLine("⚠️ QA: $roundsWithIrWidthMismatch round(s) where irData[0].size ≠ channelCount ($channelCount).")
            }
        }

        val summary = sb.toString()
        fileQaTextView.text = summary
        Log.d("SNIRF_QA", summary)
    }

    // Stream the content of a Content Uri as a RequestBody (no full file read into RAM)
    private fun contentUriRequestBody(mime: String, uri: android.net.Uri): RequestBody =
        object : RequestBody() {
            override fun contentType(): MediaType? = mime.toMediaTypeOrNull()
            override fun writeTo(sink: BufferedSink) {
                val cr = contentResolver
                cr.openInputStream(uri)?.use { input ->
                    sink.writeAll(input.source())
                } ?: throw IllegalStateException("Unable to open input stream for $uri")
            }
        }

    // Save the response bytes to Documents folder
    private suspend fun saveSnirfToDocuments(bytes: ByteArray, desiredName: String): String =
        withContext(Dispatchers.IO) {
            val base = desiredName.substringBeforeLast('.', desiredName)
            val outName = if (base.lowercase(Locale.US).endsWith(".snirf")) base else "$base.snirf"
            val subPath = "NIRDuinoCompanion/SNIRFOutputs"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, outName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/$subPath")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: throw IllegalStateException("Failed to create file in Documents/$subPath")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Failed to open output stream")
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                uri.toString()
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), subPath)
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, outName)
                FileOutputStream(outFile).use { it.write(bytes) }
                outFile.absolutePath
            }
        }

    private fun guessJsonMime(): String = "application/json" // good enough for our upload

    private fun String.ensureSnirfExtension(): String =
        if (lowercase(Locale.US).endsWith(".snirf")) this else replace(Regex("\\.json$", RegexOption.IGNORE_CASE), "") + ".snirf"

    private fun onGenerateClicked() {
        val participant =
            (spinnerParticipant.tag as? List<*>)?.get(spinnerParticipant.selectedItemPosition)
                    as? com.example.nirduino_android_app_v2.participant_manager.data.local.Participant

        when {
            participant == null -> { toast("Pick a participant."); return }
            jsonUri == null     -> { toast("Choose the data JSON file."); return }
        }

        val jsonDisplayName = getDisplayName(jsonUri!!) ?: "nirduino_data.json"

        Log.d("SNIRF", "Participant: ${Gson().toJson(participant)}")
        outputTextView.text = "Contacting Cloud Function…"

        // Build multipart body with a single file field named "file"
        val filePartBody = contentUriRequestBody(guessJsonMime(), jsonUri!!)
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", jsonDisplayName, filePartBody)
            .addFormDataPart("participantJson", Gson().toJson(participant)) // 👈 send all participant fields
            .build()

        lifecycleScope.launch {
            val resultMsg = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url(cloudRunBaseUrl) // e.g., https://data2snirf-249858040491.us-south1.run.app
                        .post(multipartBody)
                        .build()

                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val err = resp.body?.string().orEmpty()
                            return@withContext "HTTP ${resp.code} ${resp.message}: ${err.ifBlank { "No body" }}"
                        }

                        // Read SNIRF bytes from response
                        val snirfBytes = resp.body?.bytes()
                            ?: return@withContext "Empty body from server."

                        val savedUriOrPath = saveSnirfToDocuments(
                            snirfBytes,
                            jsonDisplayName.ensureSnirfExtension()
                        )

                        val rows = resp.header("X-Rows")
                        val cols = resp.header("X-Cols")
                        "Saved SNIRF to SNIRFOutputs folder"
                    }
                } catch (e: Exception) {
                    "Request failed: ${e.message}"
                }
            }

            outputTextView.text = resultMsg
            Log.d("SNIRF", "CloudRunResponse: $resultMsg")
            toast(if (resultMsg.startsWith("Saved")) "SNIRF saved" else "SNIRF failed")
        }
    }

    private fun JsonObject.getAsJsonArrayOrNull(name: String) =
        if (this.has(name) && this.get(name).isJsonArray) this.getAsJsonArray(name) else null

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
