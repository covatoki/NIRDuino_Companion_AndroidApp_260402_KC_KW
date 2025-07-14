package com.example.nirduino_android_app_v2.snirf_convert_files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.configure_streaming_files.ConfigurationDataStore
import com.example.nirduino_android_app_v2.configure_streaming_files.ConfigurationCardItem
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutDataStore
import com.example.nirduino_android_app_v2.participant_manager.data.local.Participant
import com.example.nirduino_android_app_v2.participant_manager.data.local.SecureParticipantDb
import kotlinx.coroutines.*

class SNIRFConverter : AppCompatActivity() {

    private lateinit var spinnerConfiguration: Spinner
    private lateinit var recyclerParticipants: RecyclerView
    private lateinit var buttonPickDirectory: Button
    private lateinit var textFileSummary: TextView
    private lateinit var textFolderContents: TextView
    private lateinit var buttonGenerateSNIRF: Button

    private lateinit var participantAdapter: ParticipantSelectorAdapter
    private var selectedDirectoryUri: Uri? = null

    companion object {
        private const val REQUEST_CODE_PICK_DIRECTORY = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snirfconverter)

        spinnerConfiguration = findViewById(R.id.spinner_configuration)
        recyclerParticipants = findViewById(R.id.recycler_participants)
        buttonPickDirectory = findViewById(R.id.button_pick_directory)
        textFileSummary = findViewById(R.id.text_file_summary)
        textFolderContents = findViewById(R.id.text_folder_contents)
        buttonGenerateSNIRF = findViewById(R.id.button_generate_snirf)

        participantAdapter = ParticipantSelectorAdapter()
        recyclerParticipants.layoutManager = LinearLayoutManager(this)
        recyclerParticipants.adapter = participantAdapter

        loadConfigurations()
        loadParticipants()

        buttonPickDirectory.setOnClickListener {
            pickDirectory()
        }


        buttonGenerateSNIRF.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val selectedConfigIndex = spinnerConfiguration.selectedItemPosition
                val allConfigs = ConfigurationDataStore.loadConfigs(this@SNIRFConverter)

                if (selectedConfigIndex !in allConfigs.indices) {
                    showToast("No configuration selected.")
                    return@launch
                }

                val config = allConfigs[selectedConfigIndex]
                val selectedParticipants = participantAdapter.getSelectedParticipants()

                if (selectedParticipants.contains(null)) {
                    showToast("Please select all participants.")
                    return@launch
                }

                if (selectedDirectoryUri == null) {
                    showToast("No folder selected.")
                    return@launch
                }

                // Read file names in selected directory
                val docId = DocumentsContract.getTreeDocumentId(selectedDirectoryUri!!)
                val fileNames = getDirectoryFileNames(selectedDirectoryUri!!, docId)

                val dataFile = fileNames.find { it.endsWith("_dataLog.csv", ignoreCase = true) }
                val stimFile = fileNames.find { it.equals("stimulus_metadata.csv", ignoreCase = true) }
                val annotFile = fileNames.find { it.equals("annotations.txt", ignoreCase = true) }

                val missingFiles = mutableListOf<String>()
                if (dataFile == null) missingFiles.add("_dataLog.csv")
                if (stimFile == null) missingFiles.add("stimulus_metadata.csv")
                if (annotFile == null) missingFiles.add("annotations.txt")

                if (missingFiles.isNotEmpty()) {
                    showToast("Missing files: ${missingFiles.joinToString(", ")}")
                    return@launch
                }

                // ✅ All data available — log everything
                val layoutDataStore = LayoutDataStore.getInstance(this@SNIRFConverter)
                val layoutMap = layoutDataStore.getAllLayoutsByName()

                Log.d("SNIRF", "====== SNIRF Data Summary ======")
                Log.d("SNIRF", "Configuration Name: ${config.configName}")
                Log.d("SNIRF", "Device Aliases: ${config.selectedDeviceNames}")
                Log.d("SNIRF", "Layout Names: ${config.selectedLayoutNames}")

                config.selectedLayoutNames.forEach { layoutName ->
                    val overlays = layoutDataStore.loadOverlayElements(layoutName)
                    val sources = overlays.filter { it.isSource }
                    val detectors = overlays.filter { !it.isSource }

                    Log.d("SNIRF", "-- Layout: $layoutName --")
                    Log.d("SNIRF", "Sources:")
                    sources.forEach {
                        Log.d("SNIRF", "  (${it.x}, ${it.y})")
                    }
                    Log.d("SNIRF", "Detectors:")
                    detectors.forEach {
                        Log.d("SNIRF", "  (${it.x}, ${it.y})")
                    }
                }

                Log.d("SNIRF", "Participants:")
                selectedParticipants.forEach {
                    Log.d("SNIRF", "  ${it?.subjectId}")
                }

                Log.d("SNIRF", "Files in Directory:")
                Log.d("SNIRF", "  Data File: $dataFile")
                Log.d("SNIRF", "  Stimulus Metadata File: $stimFile")
                Log.d("SNIRF", "  Annotations File: $annotFile")
                Log.d("SNIRF", "================================")

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SNIRFConverter, "All data logged. Ready to proceed!", Toast.LENGTH_LONG).show()
                }
            }
        }


    }

    private fun loadConfigurations() {
        CoroutineScope(Dispatchers.IO).launch {
            val configList = ConfigurationDataStore.loadConfigs(this@SNIRFConverter)
            val configNames = configList.map { it.configName }

            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(this@SNIRFConverter, android.R.layout.simple_spinner_item, configNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerConfiguration.adapter = adapter

                spinnerConfiguration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                        val selectedConfig = configList[position]
                        participantAdapter.setParticipantCount(selectedConfig.numSubjects)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }
            }
        }
    }

    private fun loadParticipants() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = SecureParticipantDb.get(this@SNIRFConverter)
            val allParticipants = db.participantDao().getAll()

            withContext(Dispatchers.Main) {
                participantAdapter.setParticipants(this@SNIRFConverter, allParticipants)
            }
        }
    }

    private fun pickDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(intent, REQUEST_CODE_PICK_DIRECTORY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_PICK_DIRECTORY && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                selectedDirectoryUri = uri
                val docId = DocumentsContract.getTreeDocumentId(uri)
                textFileSummary.text = "Selected folder: $docId"

                val fileNames = getDirectoryFileNames(uri, docId)

                val hasDataFile = fileNames.any { it.endsWith("_dataLog.csv", ignoreCase = true) }
                val hasStimulusFile = fileNames.any { it.equals("stimulus_metadata.csv", ignoreCase = true) }
                val hasAnnotationsFile = fileNames.any { it.equals("annotations.txt", ignoreCase = true) }

                val missing = mutableListOf<String>()
                if (!hasDataFile) missing.add("_dataLog.csv")
                if (!hasStimulusFile) missing.add("stimulus_metadata.csv")
                if (!hasAnnotationsFile) missing.add("annotations.txt")

                if (missing.isEmpty()) {
                    textFolderContents.text = "✅ All required files found."
                } else {
                    textFolderContents.text = "❌ Missing file(s): ${missing.joinToString(", ")}"
                }
            }
        }
    }

    private fun getDirectoryFileNames(uri: Uri, docId: String): List<String> {
        val result = mutableListOf<String>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                result.add(cursor.getString(nameIndex))
            }
        }
        return result
    }

    private fun showToast(msg: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(this@SNIRFConverter, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
