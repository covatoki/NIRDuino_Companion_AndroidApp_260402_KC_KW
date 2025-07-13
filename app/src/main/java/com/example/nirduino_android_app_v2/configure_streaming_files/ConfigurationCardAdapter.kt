package com.example.nirduino_android_app_v2.configure_streaming_files

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.live_data_view.LiveDataStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.gson.Gson

class ConfigurationCardAdapter(
    private val cards: MutableList<ConfigurationCardItem>,
    private val knownDeviceNames: List<String>,
    private val layoutNames: List<String>,
    private val onDuplicate: (ConfigurationCardItem) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onUpdate: () -> Unit
) : RecyclerView.Adapter<ConfigurationCardAdapter.ConfigurationViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigurationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.listitem_configure_streaming, parent, false)
        return ConfigurationViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConfigurationViewHolder, position: Int) {
        val configItem = cards[position]
        Log.d("BindView", "Binding card #$position: name=${configItem.configName}, subjects=${configItem.numSubjects}")

        val context = holder.itemView.context

        if (configItem.selectedDeviceNames == null) {
            configItem.selectedDeviceNames = mutableListOf()
        }
        if (configItem.selectedLayoutNames == null) {
            configItem.selectedLayoutNames = mutableListOf()
        }

        val totalDevices = configItem.numSubjects * configItem.numDevicesPerSubject

        val nestedAdapter = SubjectDeviceAdapter(
            context,
            configItem.selectedDeviceNames!!,
            configItem.selectedLayoutNames!!
        ) {
            Log.d("ConfigCardAdapter", "Spinner changed. DeviceNames: ${configItem.selectedDeviceNames}, layoutNames: ${configItem.selectedLayoutNames}")
            configItem.updateTimestamp()
            holder.lastUpdatedText.text = formatTimestamp(configItem.lastUpdated)

            CoroutineScope(Dispatchers.IO).launch {
                ConfigurationDataStore.saveConfigs(context, cards)
            }
        }

        holder.subjectDeviceRecyclerView.layoutManager = LinearLayoutManager(context)
        holder.subjectDeviceRecyclerView.adapter = nestedAdapter

        fun refreshRows(updateTime: Boolean = true) {
            holder.editConfigurationName.setText(configItem.configName)
            holder.alertMessage.visibility = if (totalDevices > 6) View.VISIBLE else View.INVISIBLE
            holder.subjectCountText.text = configItem.numSubjects.toString()
            holder.deviceCountText.text = configItem.numDevicesPerSubject.toString()
            holder.lastUpdatedText.text = formatTimestamp(configItem.lastUpdated)

            configItem.regenerateDeviceAndLayoutLists()
            nestedAdapter.notifyDataSetChanged()

            if (updateTime) {
                configItem.updateTimestamp()
            }
        }

        holder.incrementSubjects.setOnClickListener {
            if ((configItem.numSubjects + 1) * configItem.numDevicesPerSubject <= 6) {
                configItem.numSubjects++
                refreshRows(updateTime = false)
                holder.alertMessage.visibility = View.INVISIBLE
            }
            else{
                holder.alertMessage.visibility = View.VISIBLE
            }
        }

        holder.decrementSubjects.setOnClickListener {
            if (configItem.numSubjects > 1) {
                configItem.numSubjects--
                refreshRows(updateTime = false)
            }
        }

        holder.incrementDevices.setOnClickListener {
            if (configItem.numSubjects * (configItem.numDevicesPerSubject + 1) <= 6) {
                configItem.numDevicesPerSubject++
                refreshRows(updateTime = false)
                holder.alertMessage.visibility = View.INVISIBLE
            }
            else{
                holder.alertMessage.visibility = View.VISIBLE
            }
        }

        holder.decrementDevices.setOnClickListener {
            if (configItem.numDevicesPerSubject > 1) {
                configItem.numDevicesPerSubject--
                refreshRows(updateTime = false)
            }
        }

        holder.editConfigurationName.setText(configItem.configName)
        holder.editConfigurationName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                configItem.configName = s.toString()
                holder.lastUpdatedText.text = formatTimestamp(configItem.lastUpdated)
                onUpdate()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        holder.duplicateButton.setOnClickListener {
            onDuplicate(configItem.copy())
        }

        holder.deleteButton.setOnClickListener {
            onDelete(position)
        }

        holder.downloadButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val exportObject = mapOf(
                        "configName" to configItem.configName,
                        "numSubjects" to configItem.numSubjects,
                        "numDevicesPerSubject" to configItem.numDevicesPerSubject,
                        "lastUpdated" to configItem.lastUpdated,
                        "selectedDeviceNames" to configItem.selectedDeviceNames,
                        "selectedLayoutNames" to configItem.selectedLayoutNames
                    )

                    val json = Gson().toJson(exportObject)

                    val docsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOCUMENTS
                    )
                    val targetDir = java.io.File(docsDir, "NIRDuinoCompanion/StreamingConfigs")
                    if (!targetDir.exists()) targetDir.mkdirs()

                    val fileName = configItem.configName.replace("""[^\w\s-]""".toRegex(), "_") + ".json"
                    val outputFile = java.io.File(targetDir, fileName)
                    outputFile.writeText(json)

                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(
                            context,
                            "Saved to ${outputFile.absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(context, "Failed to export config", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        holder.streamDataButton.setOnClickListener {
            val context = holder.itemView.context
            val gson = Gson()
            val configJson = gson.toJson(configItem)

            val intent = Intent(context, LiveDataStream::class.java)
            intent.putExtra("configJson", configJson)
            context.startActivity(intent)
        }

        refreshRows(updateTime = false)

    }

    override fun getItemCount(): Int = cards.size

    class ConfigurationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val editConfigurationName: EditText = view.findViewById(R.id.editConfigurationName)
        val incrementSubjects: ImageButton = view.findViewById(R.id.incrementSubjects)
        val decrementSubjects: ImageButton = view.findViewById(R.id.decrementSubjects)
        val incrementDevices: ImageButton = view.findViewById(R.id.incrementDevicesPerSubject)
        val decrementDevices: ImageButton = view.findViewById(R.id.decrementDevicesPerSubject)
        val duplicateButton: ImageButton = view.findViewById(R.id.duplicateButton)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
        val subjectDeviceRecyclerView: RecyclerView = view.findViewById(R.id.subjectDeviceRecyclerView)
        val subjectCountText: TextView = view.findViewById(R.id.subjectCount)
        val deviceCountText: TextView = view.findViewById(R.id.devicePerSubjectCount)
        val alertMessage: TextView = view.findViewById(R.id.alertMessage)
        val lastUpdatedText: TextView = view.findViewById(R.id.lastUpdated)
        val downloadButton: ImageButton = view.findViewById(R.id.downloadButton)
        val streamDataButton: Button = view.findViewById(R.id.streamDataButton)
    }

    private fun formatTimestamp(timestamp: String): String {
        return try {
            val inputFormat = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm:ss", java.util.Locale.getDefault())
            val outputFormat = java.text.SimpleDateFormat("MMMM dd, yyyy 'at' HH:mm:ss z", java.util.Locale.getDefault())
            val date = inputFormat.parse(timestamp)
            "Last updated: ${outputFormat.format(date)}"
        } catch (e: Exception) {
            "Last updated: Invalid timestamp"
        }
    }

}
