package com.example.nirduino_android_app_v2.snirf_convert_files

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.participant_manager.data.local.Participant

class ParticipantSelectorAdapter : RecyclerView.Adapter<ParticipantSelectorAdapter.ParticipantViewHolder>() {

    private var participantOptions: List<Participant> = emptyList()
    private var count = 0
    private var context: Context? = null
    private val selectedIndices: MutableList<Int?> = mutableListOf()

    fun setParticipantCount(count: Int) {
        this.count = count
        selectedIndices.clear()
        repeat(count) { selectedIndices.add(null) }
        notifyDataSetChanged()
    }

    fun setParticipants(ctx: Context, list: List<Participant>) {
        this.context = ctx
        this.participantOptions = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParticipantViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_participant_selector, parent, false)
        return ParticipantViewHolder(view)
    }

    override fun getItemCount(): Int = count

    override fun onBindViewHolder(holder: ParticipantViewHolder, position: Int) {
        holder.bind(position, participantOptions, context, selectedIndices)
    }

    fun getSelectedParticipants(): List<Participant?> {
        return selectedIndices.map { index ->
            index?.let { i ->
                participantOptions.getOrNull(i)
            }
        }
    }

    class ParticipantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val spinner: Spinner = itemView.findViewById(R.id.spinner_participant)
        private val label: TextView = itemView.findViewById(R.id.text_subject_label)

        fun bind(index: Int, options: List<Participant>, context: Context?, selectedIndices: MutableList<Int?>) {
            label.text = "Subject ${index + 1}"
            if (context != null) {
                val names = options.map { it.subjectId }
                val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, names)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter

                spinner.setSelection(selectedIndices[index] ?: 0)
                spinner.setOnItemSelectedListener(null) // prevent re-entry on recycling

                spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        selectedIndices[index] = pos
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                        selectedIndices[index] = null
                    }
                })
            }
        }
    }
}
