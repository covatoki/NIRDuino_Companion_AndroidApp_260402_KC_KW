package com.example.nirduino_android_app_v2.device_communication_management

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
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
import com.example.nirduino_android_app_v2.layout_studio_files.LayoutDataStore
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BLEConnectionManager : Service() {

    private val serviceScope = HandlerThread("BLEServiceThread").apply { start() }
    private val serviceHandler = Handler(serviceScope.looper)

    val activeConnections = mutableMapOf<String, BleDeviceConnection>()
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private val scanCallback = BleScanCallback()
    private var targetMacs = emptySet<String>()
    private var macToAliasMap = mapOf<String, String>()
    private var layoutOverlayElements: List<com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement> = emptyList()

    @Volatile
    private var configurationReadyToStream: Boolean = false

    fun getStreamReadinessStatus(): Boolean {
        return configurationReadyToStream
    }

    @SuppressLint("MissingPermission")
    fun startStreamFromDevice(ledIntensityValues: IntArray) {
        activeConnections.values.forEach { it.streamNIRDuinoData(ledIntensityValues) }
    }

    @SuppressLint("MissingPermission")
    fun getBatteryLevelFromDevice(){
        activeConnections.values.forEach { it.requestDeviceForBatteryLevel() }
    }

    @SuppressLint("MissingPermission")
    fun stopStreamingFromDevice() {
        activeConnections.values.forEach { it.stopStreamNIRDuinoData() }
    }

    fun getLatestSQIValues():List<Float> {
        var currSQIValues = emptyList<Float>()
        activeConnections.values.forEach {
            currSQIValues = it.getLatestSignalRating()
        }
        return currSQIValues
    }

    // Safer: returns only the last `maxPoints` of the *latest* round
    fun getLatestfNIRSData(maxPoints: Int = Int.MAX_VALUE): List<DataRound> {
        // pick one connection's dataProcessor (your original logic)
        var rounds: List<DataRound> = emptyList()
        activeConnections.values.forEach { rounds = it.dataProcessor.roundWiseData }

        if (rounds.isEmpty()) return emptyList()

        val lastRound = rounds.last()

        // Defensive sizes
        val n = lastRound.timestamps.size
        if (n == 0) return listOf(
            DataRound(
                timestamps = mutableListOf(),
                redData = mutableListOf(),
                irData = mutableListOf(),
                stimuli = lastRound.stimuli.toMutableList() // keep current stimuli state
            )
        )

        val k = maxPoints.coerceAtLeast(0)
        val from = (n - k).coerceAtLeast(0)
        val to = n

        // Snapshot copies so UI can’t race with producer
        val ts  = lastRound.timestamps.subList(from, to).toList()
        val red = lastRound.redData.subList(from, to).map { it.toList() }
        val ir  = lastRound.irData.subList(from, to).map { it.toList() }

        return listOf(
            DataRound(
                timestamps = ts.toMutableList(),
                redData    = red.toMutableList(),
                irData     = ir.toMutableList(),
                stimuli    = lastRound.stimuli.toMutableList()
            )
        )
    }


    fun getChannelDisplayData(): List<DisplayChannelData>{
        var currentChannelDisplayData : List<DisplayChannelData> = emptyList()
        activeConnections.values.forEach(){
            it.dataProcessor.extractfNIRSChannelDataUsingLayout()
            currentChannelDisplayData = it.getChannelDisplayData()
        }
        return currentChannelDisplayData
    }

    override fun onCreate() {
        super.onCreate()
        registerInstance(this)  // ✅ This is the missing link
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasRequiredPermissions()) {
            Log.e("BLEConnectionManager", "Missing required BLE permissions")
            return START_NOT_STICKY
        }

        val layoutJson = intent?.getStringExtra(EXTRA_LAYOUT_JSON)
        val alias = intent?.getStringExtra(EXTRA_DEVICE_ALIAS)

        if (!layoutJson.isNullOrBlank()) {
            val gson = Gson()
            val overlays = try {
                gson.fromJson(layoutJson, Array<com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement>::class.java).toList()
            } catch (e: Exception) {
                Log.e("BLEConnectionManager", "❌ Failed to parse layout JSON", e)
                emptyList()
            }

            overlays.forEach {
                Log.d("BLEConnectionManager", "✅ Layout Element: ${if (it.isSource) "Source" else "Detector"} ${it.id} → x=${it.x}, y=${it.y}")
            }

            // Store if needed
            layoutOverlayElements = overlays
        }

        when (intent?.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_START -> {
                if (!alias.isNullOrEmpty()) {
                    loadSingleDeviceAlias(alias)
                } else {
                    Log.w("BLEConnectionManager", "No device alias provided with START command")
                }
            }
            COMMAND_STOP -> {
                Log.d("BLEConnectionManager", "Stop command received")
                stopSelf()
            }
            else -> Log.w("BLEConnectionManager", "Unknown or missing command")
        }

        return START_STICKY
    }


    @SuppressLint("MissingPermission")
    private fun loadSingleDeviceAlias(alias: String) {
        serviceHandler.post {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val deviceStore = KnownDeviceDataStore.getInstance(applicationContext)
                    val aliasToMacMap = deviceStore.getAllDeviceAliasesWithMac()

                    val mac = aliasToMacMap[alias]
                    if (mac != null) {
                        macToAliasMap = mapOf(mac to alias)
                        targetMacs = setOf(mac)

                        configurationReadyToStream = false
                        startBleScan()

                        Log.d("BLEConnectionManager", "Started scan for alias: $alias, MAC: $mac")
                    } else {
                        Log.e("BLEConnectionManager", "Alias not found: $alias")
                    }
                } catch (e: Exception) {
                    Log.e("BLEConnectionManager", "Failed to load alias", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        activeConnections.values.forEach { it.disconnect() }
        activeConnections.clear()
        serviceScope.quitSafely()
        Log.d("BLEConnectionManager", "Service closed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasRequiredPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun startForegroundService() {
        val notificationChannelId = "BLEConnectionManagerChannel"
        val channel = NotificationChannel(
            notificationChannelId,
            "BLE Connection Manager",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("BLE Service Running")
            .setContentText("Managing BLE connections in background.")
            .setSmallIcon(R.drawable.ic_ble)
            .build()

        startForeground(1, notification)
    }

    @SuppressLint("MissingPermission")
    fun loadDeviceAliasesToConnect(deviceAliases: List<String>) {
        serviceHandler.post {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val deviceStore = KnownDeviceDataStore.getInstance(applicationContext)
                    val aliasToMacMap = deviceStore.getAllDeviceAliasesWithMac()

                    macToAliasMap = aliasToMacMap.entries.associate { (alias, mac) -> mac to alias }
                    targetMacs = deviceAliases.mapNotNull { aliasToMacMap[it] }.toSet()

                    configurationReadyToStream = false
                    startBleScan()
                    Log.d("BLEConnectionManager", "Started scan for: $targetMacs")
                } catch (e: Exception) {
                    Log.e("BLEConnectionManager", "Failed to load devices", e)
                }
            }
        }
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
        Log.d("BLEConnectionManager", "BLE scan started")
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
        configurationReadyToStream = targetMacs.isNotEmpty() && targetMacs.all { mac ->
            activeConnections[mac]?.isConnected() == true
        }
        Log.d("BLEConnectionManager", "Configuration ready to stream: $configurationReadyToStream")
    }

    private inner class BleScanCallback : ScanCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val mac = device.address

            if (mac in targetMacs && !activeConnections.containsKey(mac)) {
                if (ActivityCompat.checkSelfPermission(this@BLEConnectionManager, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e("BLEConnectionManager", "Missing BLUETOOTH_CONNECT permission for $mac")
                    return
                }

                val alias = macToAliasMap[mac] ?: mac
                val connection = BleDeviceConnection(applicationContext, device, alias, selectedLayoutName)

                updateLayoutOverlayElements(connection)

                connection.onConnected = {
                    Log.d("BLEConnectionManager", "CONNECTED: $alias")
                    updateConfigurationReadiness()
                }

                connection.onDisconnected = {
                    Log.d("BLEConnectionManager", "DISCONNECTED: $alias")
                    configurationReadyToStream = false

                }

                // 🔽 NEW: Handle incoming data from this device
                connection.onDataReceived = { _, alias ->
                    val displayData = connection.dataProcessor.channelDisplayData
                    DisplayDataFormatter.saveDisplayDataToCSV(applicationContext, displayData, "${alias}_filtered_data.csv")
                }

                activeConnections[mac] = connection
                connection.connect()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLEConnectionManager", "BLE scan failed: $errorCode")
        }
    }

    fun updateLayoutOverlayElements(connection: BleDeviceConnection){
        // Assign layout overlay
        connection.dataProcessor.layoutOverlayElements = layoutOverlayElements
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val COMMAND_START = "start"
        const val COMMAND_STOP = "stop"
        const val EXTRA_DEVICE_ALIAS = "device_alias"
        const val EXTRA_LAYOUT_JSON = "layout_json"
        var selectedLayoutName = "unknown"

        fun startService(context: Context, alias: String, layoutJson: String) {
            val intent = Intent(context, BLEConnectionManager::class.java).apply {
                putExtra(EXTRA_COMMAND, COMMAND_START)
                putExtra(EXTRA_DEVICE_ALIAS, alias)
                putExtra(EXTRA_LAYOUT_JSON, layoutJson) // 🔽 new
            }
            context.startForegroundService(intent)
            this.selectedLayoutName = selectedLayoutName
        }

        private var connectionManagerInstance: BLEConnectionManager? = null

        fun stopService(context: Context) {
            val intent = Intent(context, BLEConnectionManager::class.java).apply {
                putExtra(EXTRA_COMMAND, COMMAND_STOP)
            }
            context.startService(intent)
        }

        fun registerInstance(instance: BLEConnectionManager) {
            connectionManagerInstance = instance
        }

        fun getStreamReadinessStatus(): Boolean {
            val returnValue =  connectionManagerInstance?.getStreamReadinessStatus() ?: false
            return returnValue
        }

        fun startStreamFromDevice(ledIntensityValues: IntArray, layoutName: String) {
            connectionManagerInstance?.startStreamFromDevice(ledIntensityValues)
            selectedLayoutName = layoutName
        }

        fun getDeviceBatteryLevel(){
            connectionManagerInstance?.getBatteryLevelFromDevice()
        }

        fun stopStreamingFromDevice() {
            connectionManagerInstance?.stopStreamingFromDevice()
        }

        fun broadcastStimulusEvent(event: StimulusEvent) {
            connectionManagerInstance?.activeConnections?.values?.forEach {
                it.logStimulusEvent(event)
            }
        }

        fun getLatestSQIValues(): List<Float>? {
            return connectionManagerInstance?.getLatestSQIValues()
        }

        fun getChannelDisplayData(): List<DisplayChannelData> {
            return connectionManagerInstance?.getChannelDisplayData() ?: emptyList()
        }

        fun setLayoutName(layoutName:String){
            selectedLayoutName = layoutName
        }

        fun getLatestfNIRSData(maxPoints:Int):List<DataRound>{
            return connectionManagerInstance?.getLatestfNIRSData(maxPoints) ?: emptyList()
        }

        fun hardResetTimer() {
            connectionManagerInstance?.activeConnections?.values?.forEach {
                it.resetTimeStamps()
            }
        }

        @SuppressLint("MissingPermission")
        fun requestConnectionSignalLevel(){
            connectionManagerInstance?.activeConnections?.values?.forEach {
                it.requestCurrentRSSI()
            }
        }

        fun readLatestSignalLevel(): Int {
            var currRSSI = 0
            connectionManagerInstance?.activeConnections?.values?.forEach {
                currRSSI =  it.connectionRSSI
            }
            return currRSSI
        }

        fun readLatestBatteryLevel(): Int {
            var currBatteryLevel = 0
            connectionManagerInstance?.activeConnections?.values?.forEach {
                currBatteryLevel = it.deviceBatteryLevel
            }
            return currBatteryLevel
        }

    }


}
