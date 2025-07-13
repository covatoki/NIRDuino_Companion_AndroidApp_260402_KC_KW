package com.example.nirduino_android_app_v2.participant_manager

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.participant_manager.data.local.Participant
import com.example.nirduino_android_app_v2.participant_manager.data.local.SecureParticipantDb
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.launch

class ParticipantManager : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var fabAdd:  ExtendedFloatingActionButton
    private val list = mutableListOf<Participant>()
    private lateinit var adapter: ParticipantAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_participant_manager)

        recycler = findViewById(R.id.participantRecyclerView)
        fabAdd   = findViewById(R.id.floatingActionButton)

        adapter = ParticipantAdapter(
            list,
            onEdit      = { startEdit(it) },
            onDelete    = { delete(it) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter       = adapter

        fabAdd.setOnClickListener {
            startActivity(Intent(this, ParticipantForm::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadParticipants()        // refresh after returning from form
    }

    // ──────────────────────────────────────────────────────────────
    private fun loadParticipants() = lifecycleScope.launch {
        val dao = SecureParticipantDb.get(this@ParticipantManager).participantDao()
        list.clear()
        list.addAll(dao.getAll())
        adapter.notifyDataSetChanged()
    }

    private fun startEdit(p: Participant) {
        startActivity(
            Intent(this, ParticipantForm::class.java)
                .putExtra(ParticipantForm.EXTRA_SUBJECT_ID, p.subjectId)
        )
    }


    private fun delete(p: Participant) = lifecycleScope.launch {
        SecureParticipantDb.get(this@ParticipantManager).participantDao().delete(p)
        loadParticipants()
        Toast.makeText(this@ParticipantManager, "Deleted", Toast.LENGTH_SHORT).show()
    }
}
