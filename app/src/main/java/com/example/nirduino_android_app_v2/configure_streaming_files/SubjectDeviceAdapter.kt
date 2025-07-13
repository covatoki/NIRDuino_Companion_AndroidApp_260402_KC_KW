package com.example.nirduino_android_app_v2.configure_streaming_files

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.device_manager_files.KnownDeviceDataStore
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SubjectDeviceAdapter(
    private val context: Context,
    private val selectedDeviceNames: MutableList<String>,
    private val selectedLayoutNames: MutableList<String>,
    private val onDataChanged: () -> Unit
) : RecyclerView.Adapter<SubjectDeviceAdapter.ConfigViewHolder>() {

    private var deviceList: List<String> = emptyList()
    private var layoutList: List<String> = emptyList()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            val deviceStore = KnownDeviceDataStore.getInstance(context)
            val devices = deviceStore.getDevices().first().map { it.alias }
            val layouts = LayoutDataStore.getInstance(context).loadLayoutItems().map { it.layoutName }

            android.util.Log.d("SubjectDeviceAdapter", "Loaded ${devices.size} devices, ${layouts.size} layouts")

            CoroutineScope(Dispatchers.Main).launch {
                deviceList = devices
                layoutList = layouts
                notifyDataSetChanged()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.listitem_subject_device_selector, parent, false)
        return ConfigViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConfigViewHolder, position: Int) {
        holder.label.text = "Subject/Device ${position + 1}"

        val deviceAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, deviceList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val layoutAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, layoutList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        holder.deviceSpinner.adapter = deviceAdapter
        holder.layoutSpinner.adapter = layoutAdapter

        if (position < selectedDeviceNames.size) {
            holder.deviceSpinner.setSelection(deviceList.indexOf(selectedDeviceNames[position]).coerceAtLeast(0))
        }
        if (position < selectedLayoutNames.size) {
            holder.layoutSpinner.setSelection(layoutList.indexOf(selectedLayoutNames[position]).coerceAtLeast(0))
        }

        holder.deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (position < selectedDeviceNames.size && selectedDeviceNames[position] != deviceList[pos]) {
                    selectedDeviceNames[position] = deviceList[pos]
                    onDataChanged()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        holder.layoutSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (position < selectedLayoutNames.size && selectedLayoutNames[position] != layoutList[pos]) {
                    selectedLayoutNames[position] = layoutList[pos]
                    onDataChanged()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    override fun getItemCount(): Int = selectedDeviceNames.size

    fun updateData(newDevices: List<String>, newLayouts: List<String>) {
        selectedDeviceNames.clear()
        selectedDeviceNames.addAll(newDevices)
        selectedLayoutNames.clear()
        selectedLayoutNames.addAll(newLayouts)
        notifyDataSetChanged()
    }

    class ConfigViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.labelSubjectDevice)
        val deviceSpinner: Spinner = view.findViewById(R.id.spinnerSubjectId)
        val layoutSpinner: Spinner = view.findViewById(R.id.spinnerDeviceName)
    }
}
