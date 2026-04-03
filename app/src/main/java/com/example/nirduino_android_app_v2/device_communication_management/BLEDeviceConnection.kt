package com.example.nirduino_android_app_v2.device_communication_management

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import com.example.nirduino_android_app_v2.device_communication_management.ChannelType

class BleDeviceConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    val alias: String,
    val selectedLayoutName: String
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelayMillis = 3000L

    private var isManualDisconnect = false
    private var hasConnectedOnce = false

    private val expectedChunkSizes = listOf(480, 480, 480, 480, 344, 4)
    private val expectedTotalBytes = expectedChunkSizes.sum()

    private val receivedBuffers = mutableListOf<ByteArray>()
    private val fullDataBuffer = ByteArray(expectedTotalBytes)
    private var totalBytesWritten = 0
    var ledIntensityValues = IntArray(33) { 8 }

    val dataProcessor = DataParsingAndProcessing()
    var connectionRSSI = 0
    var deviceBatteryLevel = 0

    companion object {
        val FNIRS_SERVICE_UUID: UUID = UUID.fromString("938548e6-c655-11ea-87d0-0242ac130003")
        val LED_CHARACTERISTIC_UUID: UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1213")
        val DATA_CHARACTERISTIC_UUID_1: UUID = UUID.fromString("77539407-6493-4b89-985f-baaf4c0f8d86")
        val DATA_CHARACTERISTIC_UUID_2: UUID = UUID.fromString("513b630c-e5fd-45b5-a678-bb2835d6c1d2")
    }

    var onDataReceived: ((ByteArray, String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun isConnected(): Boolean = bluetoothGatt != null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun streamNIRDuinoData(ledIntensityValues: IntArray) {
        resetTimeStamps()
        this.ledIntensityValues = ledIntensityValues

        val service = bluetoothGatt?.getService(FNIRS_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(LED_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.e("BleDeviceConnection", "LED characteristic not found.")
            return
        }
        val value = hexStringToByteArray(getCommandString(this.ledIntensityValues))
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) ?: false
        } else {
            characteristic.value = value
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        }

        dataProcessor.beginSessionLogging(context, alias, BLEConnectionManager.selectedLayoutName)
        dataProcessor.startNewDataRound(this.ledIntensityValues)
        Log.d("BleDeviceConnection", "Sent START stream command to $alias, success: $success")

    }

    fun getCommandString(intArray: IntArray): String {
        val hex = StringBuilder()
        for (v in intArray) hex.append(String.format("%02X", v))
        return hex.toString()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stopStreamNIRDuinoData() {
        val service = bluetoothGatt?.getService(FNIRS_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(LED_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.e("BleDeviceConnection", "LED characteristic not found.")
            return
        }
        val value = hexStringToByteArray("03")
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) ?: false
        } else {
            characteristic.value = value
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        }
        Log.d("BleDeviceConnection", "Sent STOP stream command to $alias, success: $success")
        dataProcessor.resetTimeStamps()

        dataProcessor.endSessionLogging(context)

    }

    @SuppressLint("NewApi")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestAutosetLEDs(channelCoords : List<DisplayChannelData>){

        val service = bluetoothGatt?.getService(FNIRS_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(LED_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.e("BleDeviceConnection", "LED characteristic not found.")
            return
        }

        // Generate dummy request
        var sourceDetectorLayoutInformation = buildLedDetectorArray(channelCoords)

        // Send data over to firmware
        val value = hexStringToByteArray(getCommandString(sourceDetectorLayoutInformation))

        bluetoothGatt?.writeCharacteristic(
            characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )

    }

    fun buildLedDetectorArray(channels: List<DisplayChannelData>): IntArray {
        val ledList = ArrayList<Int>(channels.size * 2)
        val detList = ArrayList<Int>(channels.size * 2)

        for (ch in channels) {
            require(ch.sourceId in 1..8) { "sourceId ${ch.sourceId} out of range 1..8" }
            require(ch.detectorId in 1..16) { "detectorId ${ch.detectorId} out of range 1..16" }

            val base = when (ch.type) {
                ChannelType.LONG -> 0    // LONG LED indices: 0..15
                ChannelType.SHORT -> 16  // SHORT LED indices: 16..31
            }

            val sourceZeroBased = ch.sourceId - 1
            val red = base + sourceZeroBased * 2      // RED LED index
            val ir  = red + 1                         // IR LED index

            // Add two pairs per channel (RED, det) and (IR, det)
            ledList += red
            detList += ch.detectorId
            ledList += ir
            detList += ch.detectorId
        }

        val n = ledList.size // total LED–detector pairs (2 per channel)
        return IntArray(2 + n + n).apply {
            this[0] = 23
            this[1] = n
            var i = 2
            for (v in ledList) this[i++] = v
            for (v in detList) this[i++] = v
        }
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestDeviceForBatteryLevel() {
        val service = bluetoothGatt?.getService(FNIRS_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(LED_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.e("BleDeviceConnection", "LED characteristic not found.")
            return
        }
        val value = hexStringToByteArray("09")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            characteristic.value = value
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(characteristic)
        }
    }

    fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        return ByteArray(len / 2) { i ->
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            ((hi shl 4) + lo).toByte()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect() {
        isManualDisconnect = false
        retryCount = 0
        connectGatt()
        dataProcessor.resetTimeStamps()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        isManualDisconnect = true
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        Log.d("BleDeviceConnection", "Manually disconnected from $alias")
        dataProcessor.saveSessionToFile(context, alias, BLEConnectionManager.selectedLayoutName)
        dataProcessor.endSessionLogging(context)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.d("BleDeviceConnection", "Attempting connection to $alias")
    }

    fun logStimulusEvent(event: StimulusEvent) {
        dataProcessor.handleStimulusEvent(event)
    }

    fun getLatestSignalRating(): List<Float> = dataProcessor.latestSignalRating

    fun getChannelDisplayData(): List<DisplayChannelData> = dataProcessor.channelDisplayData

    fun resetTimeStamps() {
        dataProcessor.timestampSeconds = 0.0f
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestCurrentRSSI() {
        bluetoothGatt?.readRemoteRssi()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BleDeviceConnection", "Connected to $alias")
                    val mtuOk = gatt.requestMtu(517)
                    Log.d("BleDeviceConnection", "MTU request sent (517): $mtuOk")
                    retryCount = 0
                    gatt.discoverServices()
                    onConnected?.invoke()
                    if (hasConnectedOnce) {
                        handler.post {
                            Toast.makeText(context, "Reconnected to $alias", Toast.LENGTH_SHORT).show()
                        }
                    }
                    hasConnectedOnce = true
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BleDeviceConnection", "Disconnected from $alias")
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    onDisconnected?.invoke()

                    if (!isManualDisconnect && retryCount < maxRetries) {
                        retryCount++
                        Log.d("BleDeviceConnection", "Retrying connection ($retryCount/$maxRetries)...")
                        handler.post {
                            Toast.makeText(
                                context,
                                "Lost connection to $alias. Reconnecting ($retryCount/$maxRetries)...",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        handler.postDelayed({ connectGatt() }, retryDelayMillis)
                    } else if (!isManualDisconnect) {
                        Log.e("BleDeviceConnection", "Failed to reconnect to $alias")
                    }
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d("BleDeviceConnection", "Services discovered for $alias")
            enableNotificationsIfAvailable(gatt, DATA_CHARACTERISTIC_UUID_1)
            enableNotificationsIfAvailable(gatt, DATA_CHARACTERISTIC_UUID_2)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun enableNotificationsIfAvailable(gatt: BluetoothGatt, uuid: UUID) {
            val ch = gatt.getService(FNIRS_SERVICE_UUID)?.getCharacteristic(uuid)
            if (ch != null) {
                gatt.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                cccd?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
                Log.d("BleDeviceConnection", "Notifications enabled for $uuid")
            } else {
                Log.w("BleDeviceConnection", "Characteristic $uuid not found on $alias")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            val size = data.size
            Log.w("BleDeviceConnection", "[$alias] Received: $size bytes")
            if (size !in listOf(480, 480, 480, 480, 344, 32, 4, 208)) {
                Log.w("BleDeviceConnection", "[$alias] Unexpected packet size: $size")
                return
            }
            val buffer = ByteBuffer.wrap(data)
            val isRoundReady = dataProcessor.convertByteToChannelData(buffer) // keep your logic
            deviceBatteryLevel = dataProcessor.batteryLevel

            if (isRoundReady) {
                onDataReceived?.invoke(data, alias)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            Log.d("BleDeviceConnection", "MTU changed to $mtu for $alias (status=$status)")
            gatt.discoverServices()
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) connectionRSSI = rssi
        }
    }
}
