package com.example.nirduino_android_app_v2.participant_manager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.participant_manager.data.local.Participant
import java.text.SimpleDateFormat
import java.util.*

class ParticipantAdapter(
    private val participants: MutableList<Participant>,
    private val onEdit:      (Participant) -> Unit,
    private val onDelete:    (Participant) -> Unit,
) : RecyclerView.Adapter<ParticipantAdapter.VH>() {

    private val dfDate  = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    private val dfDateT = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US)

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val lblId:      TextView   = v.findViewById(R.id.participantID)
        val txtId:      TextView   = v.findViewById(R.id.labelParticipantName)
        val txtSex:     TextView   = v.findViewById(R.id.participantGender)
        val txtAge:     TextView   = v.findViewById(R.id.participantAge)
        val txtHanded:  TextView   = v.findViewById(R.id.participantHandedness)
        val txtAdded:   TextView   = v.findViewById(R.id.itemAdded)
        val txtUpdated: TextView   = v.findViewById(R.id.lastUpdated)
        val btnEdit:     ImageButton = v.findViewById(R.id.editButton)
        val btnDelete:   ImageButton = v.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int) = VH(
        LayoutInflater.from(p.context).inflate(R.layout.listitem_participant, p, false)
    )

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = participants[pos]

        h.lblId.text      = "Participant ID:"
        h.txtId.text      = p.subjectId
        h.txtSex.text     = p.sex ?: "-"
        h.txtAge.text     = p.age?.let { "$it Years" } ?: "-"
        h.txtHanded.text  = p.handedness?.let { "${it.capitalize()} handed" } ?: "-"

        h.txtAdded.text   = "Added: ${dfDate.format(Date(p.addedOn))}"
        h.txtUpdated.text = "Updated: ${dfDateT.format(Date(p.lastUpdated))}"

        h.btnEdit.setOnClickListener      { onEdit(p) }
        h.btnDelete.setOnClickListener    { onDelete(p) }
    }

    override fun getItemCount() = participants.size
}
