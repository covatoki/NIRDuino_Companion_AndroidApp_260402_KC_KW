package com.example.nirduino_android_app_v2.participant_manager

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.participant_manager.data.local.Participant
import com.example.nirduino_android_app_v2.participant_manager.data.local.SecureParticipantDb
import kotlinx.coroutines.launch

class ParticipantForm : AppCompatActivity() {

    companion object { const val EXTRA_SUBJECT_ID = "extra_subject_id" }

    // ── view refs ────────────────────────────────────────────────
    private lateinit var etSubjectId: EditText
    private lateinit var etAge:       EditText
    private lateinit var etSex:       EditText
    private lateinit var etHanded:    EditText
    private lateinit var etWeight:    EditText
    private lateinit var etHeight:    EditText
    private lateinit var etEthnicity: EditText
    private lateinit var etComments:  EditText
    private lateinit var btnSave:     Button

    private var editingSid: String? = null

    // ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_participant_form)
        bindViews()

        // if started for editing, load existing row
        editingSid = intent.getStringExtra(EXTRA_SUBJECT_ID)
        if (editingSid != null) {
            loadParticipant(editingSid!!)
            etSubjectId.isEnabled = false        // lock primary key when editing
        }

        btnSave.setOnClickListener { saveParticipant() }
    }

    // ── read one row and pre-fill fields ─────────────────────────
    private fun loadParticipant(sid: String) = lifecycleScope.launch {
        SecureParticipantDb.get(this@ParticipantForm)
            .participantDao()
            .findById(sid)
            ?.let { p ->
                etSubjectId.setText(p.subjectId)
                etAge.setText(p.age?.toString() ?: "")
                etSex.setText(p.sex ?: "")
                etHanded.setText(p.handedness ?: "")
                etWeight.setText(p.weight?.toString() ?: "")
                etHeight.setText(p.height?.toString() ?: "")
                etEthnicity.setText(p.ethnicity ?: "")
                etComments.setText(p.comments ?: "")
            }
    }

    // ── validate + upsert ────────────────────────────────────────
    private fun saveParticipant() {
        val subjectId = etSubjectId.text.toString().trim()
        if (subjectId.isEmpty()) {
            etSubjectId.error = "Subject ID is required"
            etSubjectId.requestFocus()
            return
        }

        val p = Participant(
            subjectId   = subjectId,
            age         = etAge.text.toString().toIntOrNull(),
            sex         = etSex.text.toString().ifBlank { null },
            handedness  = etHanded.text.toString().ifBlank { null },
            weight      = etWeight.text.toString().toFloatOrNull(),
            height      = etHeight.text.toString().toFloatOrNull(),
            ethnicity   = etEthnicity.text.toString().ifBlank { null },
            comments    = etComments.text.toString().ifBlank { null },
            lastUpdated = System.currentTimeMillis()          // addedOn stays as-is on replace
        )

        lifecycleScope.launch {
            SecureParticipantDb.get(this@ParticipantForm)
                .participantDao()
                .upsert(p)   // not insert()

            Toast.makeText(this@ParticipantForm,
                "Participant saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ── one-time findViewById() calls ────────────────────────────
    private fun bindViews() {
        etSubjectId = findViewById(R.id.input_subject_id)
        etAge       = findViewById(R.id.input_age)
        etSex       = findViewById(R.id.input_sex)
        etHanded    = findViewById(R.id.input_handedness)
        etWeight    = findViewById(R.id.input_weight)
        etHeight    = findViewById(R.id.input_height)
        etEthnicity = findViewById(R.id.input_ethnicity)
        etComments  = findViewById(R.id.input_comments)
        btnSave     = findViewById(R.id.button_save)
    }
}
