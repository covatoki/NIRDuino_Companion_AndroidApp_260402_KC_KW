package com.example.nirduino_android_app_v2.device_manager_files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R

class NewDeviceAdapter(
    private val items: MutableList<NewDeviceItem>,
    private val onSave: (NewDeviceItem) -> Unit // NEW listener
) : RecyclerView.Adapter<NewDeviceAdapter.DeviceViewHolder>() {

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textDeviceName = itemView.findViewById<TextView>(R.id.textDeviceName)
        val textRSSIValue = itemView.findViewById<TextView>(R.id.textRSSIValue)
        val textMacAddress = itemView.findViewById<TextView>(R.id.textMacAddress)
        val saveDeviceBtn = itemView.findViewById<ImageButton>(R.id.saveButton)

        fun bind(item: NewDeviceItem) {
            textDeviceName.text = item.textDeviceName
            textMacAddress.text = "MAC: ${item.textMacAddress}"
            textRSSIValue.text = "RSSI: ${item.rssi} dBm"
            saveDeviceBtn.setOnClickListener {
                onSave(item) // 🔥 call the lambda function
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.listitem_new_devices, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val item = items[position]

        holder.textDeviceName.text = item.textDeviceName
        holder.textMacAddress.text = "MAC:${item.textMacAddress}"
        holder.textRSSIValue.text = "RSSI: ${item.rssi} dBm"

        holder.saveDeviceBtn.setOnClickListener {
            onSave(item)
        }

    }

    override fun getItemCount(): Int = items.size
}
