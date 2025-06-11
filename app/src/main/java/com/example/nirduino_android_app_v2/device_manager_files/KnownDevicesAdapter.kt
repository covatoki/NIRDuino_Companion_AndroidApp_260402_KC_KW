package com.example.nirduino_android_app_v2.device_manager_files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R

class KnownDevicesAdapter(
    private val items: MutableList<KnownDeviceItem>,
    private val onDelete: (KnownDeviceItem) -> Unit
) : RecyclerView.Adapter<KnownDevicesAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val alias = itemView.findViewById<TextView>(R.id.textAlias)
        val addedOn = itemView.findViewById<TextView>(R.id.textAddedOn)
        val mac = itemView.findViewById<TextView>(R.id.textMacAddress)
        val deleteBtn = itemView.findViewById<ImageButton>(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.listitem_known_devices, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val item = items[position]
        holder.alias.text = item.alias
        holder.addedOn.text = "Added on ${item.addedOn}"
        holder.mac.text = "MAC ${item.macAddress}"
        holder.deleteBtn.setOnClickListener {
            onDelete(item)
        }
    }

    override fun getItemCount(): Int = items.size
}
