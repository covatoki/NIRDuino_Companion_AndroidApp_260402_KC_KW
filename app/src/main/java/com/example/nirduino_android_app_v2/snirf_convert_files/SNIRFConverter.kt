package com.example.nirduino_android_app_v2.snirf_convert_files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutDataStore
import com.example.nirduino_android_app_v2.participant_manager.data.local.SecureParticipantDb
import kotlinx.coroutines.*
import com.example.nirduino_android_app_v2.R
import com.google.gson.Gson

class SNIRFConverter : AppCompatActivity() {

    private lateinit var spinnerLayout: Spinner
    private lateinit var spinnerParticipant: Spinner
    private lateinit var buttonPickCsv: Button
    private lateinit var textCsvName: TextView
    private lateinit var editStimulusLabel: EditText
    private lateinit var editNotes: EditText
    private lateinit var buttonGenerate: Button

    private var csvUri: Uri? = null

    companion object { private const val REQ_PICK_CSV = 42 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snirfconverter)

        // ── view refs ─────────────────────────────────────────────
        spinnerLayout       = findViewById(R.id.spinner_layout)
        spinnerParticipant  = findViewById(R.id.spinner_participant)
        buttonPickCsv       = findViewById(R.id.button_pick_csv)
        textCsvName         = findViewById(R.id.text_csv_name)
        editStimulusLabel   = findViewById(R.id.edit_stimulus_label)
        editNotes           = findViewById(R.id.edit_notes)
        buttonGenerate      = findViewById(R.id.button_generate_snirf)

        populateLayoutSpinner()
        populateParticipantSpinner()

        buttonPickCsv.setOnClickListener { pickCsv() }
        buttonGenerate.setOnClickListener { onGenerateClicked() }
    }

    // ───────────── helpers ─────────────

    private fun populateLayoutSpinner() = CoroutineScope(Dispatchers.IO).launch {
        val layoutNames = LayoutDataStore.getInstance(this@SNIRFConverter)
            .getAllLayoutsByName()
            .keys
            .sorted()
        withContext(Dispatchers.Main) {
            spinnerLayout.adapter =
                ArrayAdapter(this@SNIRFConverter,
                    android.R.layout.simple_spinner_dropdown_item,
                    layoutNames)
        }
    }

    private fun populateParticipantSpinner() = CoroutineScope(Dispatchers.IO).launch {
        val list = SecureParticipantDb.get(this@SNIRFConverter)
            .participantDao()
            .getAll()
            .sortedBy { it.subjectId }
        val labels = list.map { p -> "${p.subjectId} – ${p.age} y /${p.sex}" }
        withContext(Dispatchers.Main) {
            spinnerParticipant.adapter =
                ArrayAdapter(this@SNIRFConverter,
                    android.R.layout.simple_spinner_dropdown_item,
                    labels)
            spinnerParticipant.tag = list          // stash full objects for later
        }
    }

    private fun pickCsv() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"                       // covers .csv
        }
        startActivityForResult(intent, REQ_PICK_CSV)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_PICK_CSV && res == Activity.RESULT_OK) {
            csvUri = data?.data
            textCsvName.text = csvUri?.lastPathSegment ?: "No file selected"
        }
    }

    private fun onGenerateClicked() {
        val layoutName   = spinnerLayout.selectedItem as? String
        val participant  =
            (spinnerParticipant.tag as? List<*>)?.get(spinnerParticipant.selectedItemPosition)
                    as? com.example.nirduino_android_app_v2.participant_manager.data.local.Participant
        val stimLabel    = editStimulusLabel.text.toString().trim()
        val notes        = editNotes.text.toString().trim()

        when {
            layoutName == null  -> { toast("Pick a layout first."); return }
            participant == null -> { toast("Pick a participant.");  return }
            csvUri == null      -> { toast("Choose the data .csv file."); return }
            stimLabel.isEmpty() -> { toast("Enter a stimulus label.");    return }
        }

        /* ---- layoutName is non-null past this point, but the compiler
               loses that knowledge inside the coroutine.  ---- */
        val layoutNameNN = layoutName   //  ❱ capture a non-null copy

        lifecycleScope.launch {

            layoutName?.let { name ->

                /* ① Load overlay elements off-thread */
                val overlays = withContext(Dispatchers.IO) {
                    LayoutDataStore.getInstance(this@SNIRFConverter)
                        .loadOverlayElements(name)
                }

                /* ② Split into sources & detectors */
                val sources   = overlays.filter { it.isSource }
                val detectors = overlays.filter { !it.isSource }

                /* ③ Log layout info */
                Log.d("SNIRF", "====== Selected Layout: $name ======")
                Log.d("SNIRF", "Sources (${sources.size}):")
                sources.forEach { Log.d("SNIRF", "  (${it.x}, ${it.y})") }

                Log.d("SNIRF", "Detectors (${detectors.size}):")
                detectors.forEach { Log.d("SNIRF", "  (${it.x}, ${it.y})") }

                /* ④ Log participant info  ── choose one style ─────────── */

                // A. Verbose JSON dump (requires Gson import)
                Log.d("SNIRF", "Participant (JSON): ${Gson().toJson(participant)}")

                // B. Field-by-field (uncomment / adjust to your model)
                // Log.d("SNIRF", "Participant → " +
                //        "ID=${participant.subjectId}, " +
                //        "Age=${participant.age}, " +
                //        "Sex=${participant.sex}, " +
                //        "Name=${participant.firstName} ${participant.lastName}")

                Log.d("SNIRF", "=========================================")

                /* ⑤ Continue with SNIRF generation */
                // buildSnirf(name, participant, csvUri!!, stimLabel, notes)
                toast("✓ Layout & participant data logged – ready to generate SNIRF.")
            } ?: toast("Layout name disappeared 🤔")
        }

    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
