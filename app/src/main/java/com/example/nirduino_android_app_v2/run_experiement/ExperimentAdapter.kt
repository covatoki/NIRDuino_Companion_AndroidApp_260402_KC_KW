package com.example.nirduino_android_app_v2.run_experiement

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.databinding.DialogExperimentDetailsBinding

class ExperimentAdapter(
    private val context: Activity,
    private val items: List<ExperimentModel>,
    private val listener: OnExperimentClickListener
) : RecyclerView.Adapter<ExperimentAdapter.MyViewHolder>() {

    inner class MyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvExperimentName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_experiments, parent, false)
        return MyViewHolder(view)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val number = position + 1
        holder.tvTitle.text = "$number. ${items[position].name}"

        holder.itemView.setOnClickListener {
            showExperimentDialog(items[position],listener)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun showExperimentDialog(experimentModel: ExperimentModel?, listener: OnExperimentClickListener) {
        val dialog = Dialog(context)
        val binding = DialogExperimentDetailsBinding.inflate(context.layoutInflater)

        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.setCancelable(true)

        val dialogText = experimentModel?.description?.trimIndent()

        binding.tvTitle.text = "${experimentModel?.name}\nexperiment"
        binding.tvDialogText.text = dialogText

        binding.btnOk.setOnClickListener {
            dialog.dismiss()
            experimentModel?.let { listener.onExperimentSelected(it) }
        }

        dialog.show()
    }

}

interface OnExperimentClickListener {
    fun onExperimentSelected(model: ExperimentModel)
}