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

    // ***** Single-connection state *****
    var connection: BleDeviceConnection? = null
    private var targetMac: String? = null
    private var targetAlias: String? = null

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private val scanCallback = BleScanCallback()

    private var layoutOverlayElements: List<com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement> = emptyList()

    @Volatile
    private var configurationReadyToStream: Boolean = false

    fun getStreamReadinessStatus(): Boolean = configurationReadyToStream

    @SuppressLint("MissingPermission")
    fun startStreamFromDevice(ledIntensityValues: IntArray) {
        connection?.streamNIRDuinoData(ledIntensityValues)
    }

    @SuppressLint("MissingPermission")
    fun getBatteryLevelFromDevice() {
        connection?.requestDeviceForBatteryLevel()
    }

    @SuppressLint("MissingPermission")
    fun stopStreamingFromDevice() {
        connection?.stopStreamNIRDuinoData()
    }

    fun getLatestSQIValues(): List<Float> {
        return connection?.getLatestSignalRating() ?: emptyList()
    }

    fun saveSessionNotes(sessionNotes: String){
        connection?.dataProcessor?.sessionNotes = sessionNotes
    }

    // Returns last round (optionally truncated to maxPoints)
    fun getLatestfNIRSData(maxPoints: Int = Int.MAX_VALUE): List<DataRound> {
        val rounds = connection?.dataProcessor?.roundWiseData ?: emptyList()
        if (rounds.isEmpty()) return emptyList()
        val last = rounds.last()
        val n = last.timestamps.size
        if (n == 0) {
            return listOf(
                DataRound(
                    timestamps = mutableListOf(),
                    redData = mutableListOf(),
                    irData = mutableListOf(),
                    stimuli = last.stimuli.toMutableList()
                )
            )
        }
        val from = (n - maxPoints).coerceAtLeast(0)
        val ts = last.timestamps.subList(from, n).toList()
        val red = last.redData.subList(from, n).map { it.toList() }
        val ir = last.irData.subList(from, n).map { it.toList() }
        return listOf(
            DataRound(
                timestamps = ts.toMutableList(),
                redData = red.toMutableList(),
                irData = ir.toMutableList(),
                stimuli = last.stimuli.toMutableList()
            )
        )
    }

    fun getChannelDisplayData(): List<DisplayChannelData> {
        connection?.dataProcessor?.extractfNIRSChannelDataUsingLayout()
        return connection?.getChannelDisplayData() ?: emptyList()
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
        val alias = intent?.getStringExtra(EXTRA_DEVICE_ALIAS)

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
                if (!alias.isNullOrEmpty()) {
                    loadSingleDeviceAlias(alias)
                } else {
                    Log.w("BLEConnectionManager", "No device alias provided with START command")
                }
            }
            COMMAND_STOP -> {
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
                    if (mac == null) {
                        Log.e("BLEConnectionManager", "Alias not found: $alias")
                        return@launch
                    }
                    // set single-target
                    targetAlias = alias
                    targetMac = mac
                    configurationReadyToStream = false
                    startBleScan()
                    Log.d("BLEConnectionManager", "Started scan for alias: $alias, MAC: $mac")
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
        connection?.disconnect()
        connection = null
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
        configurationReadyToStream = connection?.isConnected() == true
        Log.d("BLEConnectionManager", "Configuration ready to stream: $configurationReadyToStream")
    }

    private inner class BleScanCallback : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address
            val target = targetMac ?: return
            if (mac != target) return
            if (connection != null) return // already set

            if (ActivityCompat.checkSelfPermission(this@BLEConnectionManager, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("BLEConnectionManager", "Missing BLUETOOTH_CONNECT permission for $mac")
                return
            }

            // Stop scanning once we find our target
            stopBleScan()

            val alias = targetAlias ?: mac
            val conn = BleDeviceConnection(applicationContext, result.device, alias, selectedLayoutName)
            conn.dataProcessor.layoutOverlayElements = layoutOverlayElements

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

            connection = conn
            connection?.connect()
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

        // keep global selected layout name
        var selectedLayoutName = "unknown"

        fun startService(
            context: Context,
            alias: String,
            layoutJson: String,
            layoutName: String = "unknown"
        ) {
            val intent = Intent(context, BLEConnectionManager::class.java).apply {
                putExtra(EXTRA_COMMAND, COMMAND_START)
                putExtra(EXTRA_DEVICE_ALIAS, alias)
                putExtra(EXTRA_LAYOUT_JSON, layoutJson)
            }
            if (layoutName.isNotBlank() && layoutName != "unknown") {
                selectedLayoutName = layoutName  // ✅ save the chosen layout
            }
            context.startForegroundService(intent)
        }

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
            connectionManagerInstance?.connection?.logStimulusEvent(event)
        }

        fun getLatestSQIValues(): List<Float> =
            connectionManagerInstance?.getLatestSQIValues() ?: emptyList()

        fun getChannelDisplayData(): List<DisplayChannelData> =
            connectionManagerInstance?.getChannelDisplayData() ?: emptyList()

        fun setLayoutName(layoutName: String) {
            selectedLayoutName = layoutName
        }

        fun getLatestfNIRSData(maxPoints: Int): List<DataRound> =
            connectionManagerInstance?.getLatestfNIRSData(maxPoints) ?: emptyList()

        fun hardResetTimer() {
            connectionManagerInstance?.connection?.resetTimeStamps()
        }

        @SuppressLint("MissingPermission")
        fun requestConnectionSignalLevel() {
            connectionManagerInstance?.connection?.requestCurrentRSSI()
        }

        fun readLatestSignalLevel(): Int =
            connectionManagerInstance?.connection?.connectionRSSI ?: 0

        fun readLatestBatteryLevel(): Int =
            connectionManagerInstance?.connection?.deviceBatteryLevel ?: 0

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun requestAutomaticLEDAdjustment(channelCoords : List<DisplayChannelData>){
            connectionManagerInstance?.connection?.dataProcessor?.ledsAutoAdjusted = false
            connectionManagerInstance?.connection?.requestAutosetLEDs(channelCoords)
        }

        fun checkIfLEDsAdjusted(): Boolean? {
            return connectionManagerInstance?.connection?.dataProcessor?.ledsAutoAdjusted
        }
    }
}
