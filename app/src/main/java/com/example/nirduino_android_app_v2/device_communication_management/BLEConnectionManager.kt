package com.example.nirduino_android_app_v2.device_communication_management


import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.device_manager_files.KnownDeviceDataStore
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class BLEConnectionManager : Service() {


    private val serviceThread = HandlerThread("BLEServiceThread").apply { start() }
    private val serviceHandler = Handler(serviceThread.looper)


    // Keep track of multiple devices simultaneously
    private val connections = mutableMapOf<String, BleDeviceConnection>()
    private val targetDevices = mutableMapOf<String, String>()  // MAC -> alias


    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private val scanCallback = BleScanCallback()


    private var layoutOverlayElements: List<com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement> = emptyList()


    @Volatile
    private var configurationReadyToStream: Boolean = false


    fun getStreamReadinessStatus(): Boolean = configurationReadyToStream


    @SuppressLint("MissingPermission")
    fun startStreamFromDevice(ledIntensityValues: IntArray) {
        connections.values.forEach {
            it.streamNIRDuinoData(ledIntensityValues)
        }
    }


    @SuppressLint("MissingPermission")
    fun getBatteryLevelFromDevice() {
        connections.values.forEach {
            it.requestDeviceForBatteryLevel()
        }
    }


    @SuppressLint("MissingPermission")
    fun stopStreamingFromDevice() {
        connections.values.forEach {
            it.stopStreamNIRDuinoData()
        }
    }


    fun getLatestSQIValues(): Map<String, List<Float>> {
        return connections.mapValues { it.value.getLatestSignalRating() }
    }


    fun saveSessionNotes(sessionNotes: String) {
        connections.values.forEach {
            it.dataProcessor.sessionNotes = sessionNotes
        }
    }


    // Returns last round per device (optionally truncated to maxPoints)
    fun getLatestfNIRSData(maxPoints: Int): Map<String, List<DataRound>> {
        val result = mutableMapOf<String, List<DataRound>>()

        connections.forEach { (mac, conn) ->
            val rounds = conn.dataProcessor.roundWiseData
            Log.d("DATA_DEBUG", "MAC=$mac alias=${conn.alias} rounds=${rounds.size}")
            if (rounds.isEmpty()) return@forEach

            val last = rounds.last()
            val n = last.timestamps.size
            val from = (n - maxPoints).coerceAtLeast(0)

            result[mac] = listOf(
                DataRound(
                    timestamps = last.timestamps.subList(from, n).toMutableList(),
                    redData = last.redData.subList(from, n).map { it.toList() }.toMutableList(),
                    irData = last.irData.subList(from, n).map { it.toList() }.toMutableList(),
                    stimuli = last.stimuli.toMutableList()
                )
            )
        }

        return result
    }


    fun getChannelDisplayData(): List<DisplayChannelData> {
        val allData = mutableListOf<DisplayChannelData>()
        connections.values.forEach { conn ->
            conn.dataProcessor.extractfNIRSChannelDataUsingLayout()
            allData.addAll(conn.getChannelDisplayData())
        }
        return allData
    }

    fun setLayoutElements(elements: List<com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement>) {
        layoutOverlayElements = elements
        connections.values.forEach { conn ->
            conn.dataProcessor.layoutOverlayElements = elements
        }
        Log.d("BLEConnectionManager", "Layout overlay elements set directly: ${elements.size}")
    }


    override fun onCreate() {
        super.onCreate()
        registerInstance(this)
        startForegroundService()
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasRequiredPermissions()) {
            Log.e("BLEConnectionManager", "Missing required BLE permissions")
            return START_NOT_STICKY
        }

        val layoutJson = intent?.getStringExtra(EXTRA_LAYOUT_JSON)

        if (!layoutJson.isNullOrBlank()) {
            layoutOverlayElements = try {
                Gson().fromJson(
                    layoutJson,
                    Array<com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement>::class.java
                ).toList()
            } catch (e: Exception) {
                Log.e("BLEConnectionManager", "Failed to parse layout JSON", e)
                emptyList()
            }
        }

        when (intent?.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_START -> {
                val aliases = intent.getStringArrayListExtra(EXTRA_DEVICE_ALIASES)
                if (!aliases.isNullOrEmpty()) {
                    loadDeviceAliases(aliases)
                } else {
                    Log.w("BLEConnectionManager", "No device aliases provided with START command")
                }
            }
            COMMAND_STOP -> stopSelf()
            else -> Log.w("BLEConnectionManager", "Unknown or missing command")
        }

        return START_STICKY
    }


    @SuppressLint("MissingPermission")
    private fun loadDeviceAliases(aliases: List<String>) {
        serviceHandler.post {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val deviceStore = KnownDeviceDataStore.getInstance(applicationContext)
                    val aliasToMacMap = deviceStore.getAllDeviceAliasesWithMac()

                    for (alias in aliases) {
                        val mac = aliasToMacMap[alias]
                        if (mac == null) {
                            Log.e("BLEConnectionManager", "Alias not found in store: $alias")
                        } else {
                            targetDevices[mac] = alias
                        }
                    }

                    if (targetDevices.isEmpty()) {
                        Log.e("BLEConnectionManager", "No valid target devices found, aborting scan")
                        return@launch
                    }

                    configurationReadyToStream = false
                    startBleScan()
                } catch (e: Exception) {
                    Log.e("BLEConnectionManager", "Failed to load aliases", e)
                }
            }
        }
    }


    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        connections.values.forEach { it.disconnect() }
        connections.clear()
        serviceThread.quitSafely()
        Log.d("BLEConnectionManager", "Service closed")
    }


    override fun onBind(intent: Intent?): IBinder? = null


    private fun hasRequiredPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }


    private fun startForegroundService() {
        val channelId = "BLEConnectionManagerChannel"
        val channel = NotificationChannel(
            channelId,
            "BLE Connection Manager",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BLE Service Running")
            .setContentText("Managing BLE connection in background.")
            .setSmallIcon(R.drawable.ic_ble)
            .build()

        startForeground(1, notification)
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startBleScan() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        if (!bluetoothAdapter.isEnabled) {
            Log.e("BLEConnectionManager", "Bluetooth is disabled")
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BLEConnectionManager", "Missing BLUETOOTH_SCAN permission")
            return
        }
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        bluetoothLeScanner?.startScan(scanCallback)
        Log.d("BLEConnectionManager", "BLE scan started for ${targetDevices.size} target(s)")
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopBleScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BLEConnectionManager", "Missing BLUETOOTH_SCAN permission")
            return
        }
        bluetoothLeScanner?.stopScan(scanCallback)
        Log.d("BLEConnectionManager", "BLE scan stopped")
    }


    private fun updateConfigurationReadiness() {
        configurationReadyToStream =
            connections.isNotEmpty() &&
                    connections.size == targetDevices.size &&
                    connections.values.all { it.isConnected() }

        Log.d(
            "BLEConnectionManager",
            "Connections: ${connections.size}/${targetDevices.size} — ready=$configurationReadyToStream"
        )
    }


    private inner class BleScanCallback : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address
            val alias = targetDevices[mac] ?: return     // not a device we want
            if (connections.containsKey(mac)) return     // already connecting/connected

            if (ActivityCompat.checkSelfPermission(
                    this@BLEConnectionManager,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("BLEConnectionManager", "Missing BLUETOOTH_CONNECT permission for $mac")
                return
            }

            Log.d("BLEConnectionManager", "Found target device: $alias ($mac)")

            val conn = BleDeviceConnection(applicationContext, result.device, alias, selectedLayoutName)
            conn.dataProcessor.layoutOverlayElements = layoutOverlayElements
            Log.d("BLEConnectionManager", "Layout elements loaded: ${layoutOverlayElements.size} for $alias")

            conn.onConnected = {
                Log.d("BLEConnectionManager", "CONNECTED: $alias")
                updateConfigurationReadiness()
            }
            conn.onDisconnected = {
                Log.d("BLEConnectionManager", "DISCONNECTED: $alias")
                configurationReadyToStream = false
            }
            conn.onDataReceived = { _, a ->
                val displayData = conn.dataProcessor.channelDisplayData
                DisplayDataFormatter.saveDisplayDataToCSV(
                    applicationContext,
                    displayData,
                    "${a}_filtered_data.csv"
                )
            }

            connections[mac] = conn
            conn.connect()

            // Stop scanning only after we've registered ALL target devices
            if (connections.size == targetDevices.size) {
                Log.d("BLEConnectionManager", "All target devices found — stopping scan")
                stopBleScan()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLEConnectionManager", "BLE scan failed: $errorCode")
        }
    }


    companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val COMMAND_START = "start"
        const val COMMAND_STOP = "stop"
        const val EXTRA_DEVICE_ALIAS = "device_alias"
        const val EXTRA_LAYOUT_JSON = "layout_json"
        const val EXTRA_DEVICE_ALIASES = "device_aliases"

        // Globally selected layout name
        var selectedLayoutName = "unknown"

        fun startService(
            context: Context,
            aliases: List<String>,
            layoutJson: String,
            layoutName: String = "unknown"
        ) {
            if (layoutName.isNotBlank() && layoutName != "unknown") {
                selectedLayoutName = layoutName
            }
            val intent = Intent(context, BLEConnectionManager::class.java).apply {
                putExtra(EXTRA_COMMAND, COMMAND_START)
                putStringArrayListExtra(EXTRA_DEVICE_ALIASES, ArrayList(aliases))
                putExtra(EXTRA_LAYOUT_JSON, layoutJson)
            }
            context.startForegroundService(intent)
        }

        // Convenience overload for a single device (backwards-compatible)
        fun startService(
            context: Context,
            alias: String,
            layoutJson: String,
            layoutName: String = "unknown"
        ) = startService(context, listOf(alias), layoutJson, layoutName)


        private var connectionManagerInstance: BLEConnectionManager? = null


        fun stopService(context: Context, sessionNotes: String) {
            connectionManagerInstance?.saveSessionNotes(sessionNotes)
            val intent = Intent(context, BLEConnectionManager::class.java).apply {
                putExtra(EXTRA_COMMAND, COMMAND_STOP)
            }
            context.startService(intent)
        }


        fun registerInstance(instance: BLEConnectionManager) {
            connectionManagerInstance = instance
        }


        fun getStreamReadinessStatus(): Boolean =
            connectionManagerInstance?.getStreamReadinessStatus() ?: false


        fun startStreamFromDevice(ledIntensityValues: IntArray, layoutName: String) {
            selectedLayoutName = layoutName
            connectionManagerInstance?.startStreamFromDevice(ledIntensityValues)
        }


        fun getDeviceBatteryLevel() {
            connectionManagerInstance?.getBatteryLevelFromDevice()
        }


        fun stopStreamingFromDevice() {
            connectionManagerInstance?.stopStreamingFromDevice()
        }


        fun broadcastStimulusEvent(event: StimulusEvent) {
            connectionManagerInstance?.connections?.values?.forEach {
                it.logStimulusEvent(event)
            }
        }


        fun getLatestSQIValues(): Map<String, List<Float>> =
            connectionManagerInstance?.getLatestSQIValues() ?: emptyMap()

        // Legacy single-device accessor (returns first device for UI that hasn't migrated yet)
        fun getLatestSQIValuesSingle(): List<Float> =
            connectionManagerInstance?.connections?.values?.firstOrNull()?.getLatestSignalRating() ?: emptyList()


        fun getChannelDisplayData(): List<DisplayChannelData> =
            connectionManagerInstance?.getChannelDisplayData() ?: emptyList()


        fun setLayoutName(layoutName: String) {
            selectedLayoutName = layoutName
        }

        fun setLayoutElements(elements: List<com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement>) {
            connectionManagerInstance?.setLayoutElements(elements)
        }


        fun getLatestfNIRSData(maxPoints: Int): Map<String, List<DataRound>> =
            connectionManagerInstance?.getLatestfNIRSData(maxPoints) ?: emptyMap()

        // Legacy single-device accessor (returns first device) for UI not yet migrated
        fun getLatestfNIRSDataSingle(maxPoints: Int): List<DataRound> =
            connectionManagerInstance
                ?.connections
                ?.values
                ?.firstOrNull()
                ?.let { conn ->
                    val rounds = conn.dataProcessor.roundWiseData
                    if (rounds.isEmpty()) return emptyList()
                    val last = rounds.last()
                    val n = last.timestamps.size
                    val from = (n - maxPoints).coerceAtLeast(0)
                    listOf(
                        DataRound(
                            timestamps = last.timestamps.subList(from, n).toMutableList(),
                            redData = last.redData.subList(from, n).map { it.toList() }.toMutableList(),
                            irData = last.irData.subList(from, n).map { it.toList() }.toMutableList(),
                            stimuli = last.stimuli.toMutableList()
                        )
                    )
                }
                ?: emptyList()


        fun hardResetTimer() {
            connectionManagerInstance?.connections?.values?.forEach { it.resetTimeStamps() }
        }


        @SuppressLint("MissingPermission")
        fun requestConnectionSignalLevel() {
            connectionManagerInstance?.connections?.values?.forEach { it.requestCurrentRSSI() }
        }


        fun readLatestSignalLevels(): Map<String, Int> =
            connectionManagerInstance?.connections?.values?.associate { it.alias to it.connectionRSSI }
                ?: emptyMap()

        // Legacy single-device accessor
        fun readLatestSignalLevel(): Int =
            connectionManagerInstance?.connections?.values?.firstOrNull()?.connectionRSSI ?: 0


        // FIX 7: Return battery level per device (keyed by alias) rather than only first
        fun readLatestBatteryLevels(): Map<String, Int> =
            connectionManagerInstance?.connections?.values?.associate { it.alias to it.deviceBatteryLevel }
                ?: emptyMap()

        // Legacy single-device accessor
        fun readLatestBatteryLevel(): Int =
            connectionManagerInstance?.connections?.values?.firstOrNull()?.deviceBatteryLevel ?: 0


        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun requestAutomaticLEDAdjustment(channelCoords: List<DisplayChannelData>) {
            connectionManagerInstance
                ?.connections
                ?.values
                ?.forEach { conn ->
                    conn.dataProcessor.ledsAutoAdjusted = false
                    conn.requestAutosetLEDs(channelCoords)
                }
        }


        fun checkIfLEDsAdjusted(): Boolean? {
            return connectionManagerInstance
                ?.connections
                ?.values
                ?.all { it.dataProcessor.ledsAutoAdjusted }
        }


        fun getLatestIntensityValues(): IntArray? {
            return connectionManagerInstance
                ?.connections
                ?.values
                ?.firstOrNull()
                ?.dataProcessor
                ?.ledIntensityValues
        }


        fun setPresetStimulusLabels(labels: List<String>) {
            connectionManagerInstance
                ?.connections
                ?.values
                ?.forEach { conn ->
                    conn.dataProcessor.presetStimulusLabels = labels
                }
        }


        // Returns how many devices are currently connected vs targeted
        fun getConnectionCount(): Pair<Int, Int> {
            val instance = connectionManagerInstance ?: return Pair(0, 0)
            return Pair(instance.connections.size, instance.targetDevices.size)
        }

        // Returns alias -> isConnected map for UI status display
        fun getConnectionStatuses(): Map<String, Boolean> =
            connectionManagerInstance?.connections?.values?.associate { it.alias to it.isConnected() }
                ?: emptyMap()
    }
}
