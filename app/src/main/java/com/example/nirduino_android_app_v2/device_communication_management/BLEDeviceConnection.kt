package com.example.nirduino_android_app_v2.device_communication_management

import android.Manifest
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

class BleDeviceConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val alias: String,
    val selectedLayoutName: String
) {

    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelayMillis = 3000L

    private var isManualDisconnect = false
    private var hasConnectedOnce = false

    private val expectedChunkSizes = listOf(480, 480, 480, 480, 344)
    private val expectedTotalBytes = expectedChunkSizes.sum()

    private val receivedBuffers = mutableListOf<ByteArray>()
    private val fullDataBuffer = ByteArray(expectedTotalBytes)
    private var totalBytesWritten = 0
    var ledIntensityValues = IntArray(33) { 8 }

    val dataProcessor = DataParsingAndProcessing()

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

        this.ledIntensityValues = ledIntensityValues

        val service = bluetoothGatt?.getService(FNIRS_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(LED_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.e("BleDeviceConnection", "LED characteristic not found.")
            return
        }
        val value = hexStringToByteArray(getCommandString(this.ledIntensityValues))
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ?: false
        } else {
            characteristic.value = value
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        }

        dataProcessor.resetTimeStamps()

        Log.d("BleDeviceConnection", "Sent START stream command to $alias, success: $success")
        dataProcessor.startNewDataRound(this.ledIntensityValues)
    }

    fun getCommandString(intArray: IntArray): String {

        val hexString = StringBuilder()

        for (intValue in intArray) {
            val hexValue = String.format("%02X", intValue)
            // Append the hex value to the result string
            hexString.append(hexValue)
        }

        return hexString.toString()
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
            bluetoothGatt?.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ?: false
        } else {
            characteristic.value = value
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        }
        Log.d("BleDeviceConnection", "Sent STOP stream command to $alias, success: $success")

        dataProcessor.resetTimeStamps();
    }

    fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                    Character.digit(s[i + 1], 16)).toByte()
        }
        return data
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

    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.d("BleDeviceConnection", "Attempting connection to $alias")
    }

    fun logStimulusEvent(event: StimulusEvent) {
        dataProcessor?.handleStimulusEvent(event)
    }

    fun getLatestSQIScores(): List<Float> {
        return dataProcessor.latestSQIScores
    }

    fun getChannelDisplayData(): List<DisplayChannelData> {
        return dataProcessor.channelDisplayData
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BleDeviceConnection", "Connected to $alias")
                    val mtuRequestSuccess = gatt.requestMtu(517)
                    Log.d("BleDeviceConnection", "MTU request sent for 517: $mtuRequestSuccess")
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
                            Toast.makeText(context, "Lost connection to $alias. Reconnecting ($retryCount/$maxRetries)...", Toast.LENGTH_SHORT).show()
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
            val characteristic = gatt.getService(FNIRS_SERVICE_UUID)?.getCharacteristic(uuid)
            if (characteristic != null) {
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                descriptor?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
                Log.d("BleDeviceConnection", "Notifications enabled for $uuid")
            } else {
                Log.w("BleDeviceConnection", "Characteristic $uuid not found on $alias")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value
            val size = data.size

            if (size !in expectedChunkSizes) {
                Log.w("BLEDeviceConnection", "[$alias] Unexpected packet size: $size")
                return
            }

            val buffer = ByteBuffer.wrap(data)
            val isRoundReady = dataProcessor.convertByteToChannelData(buffer)

        }

        private fun parseAndSumTimestamps() {
            val buffer = ByteBuffer.wrap(fullDataBuffer).order(ByteOrder.LITTLE_ENDIAN)

            val sourceIndex = 32
            val detectorIndex = 16
            val numDetectors = 17
            val bytesPerValue = 4
            val headerOffset = 4 // skip initial 4 bytes like original Java

            val index = sourceIndex * numDetectors + detectorIndex
            val byteOffset = headerOffset + index * bytesPerValue

            if (byteOffset + 4 <= fullDataBuffer.size) {
                val timestamp = buffer.getInt(byteOffset)
                Log.i("BLETimestamp", "[$alias] Timestamp for round: $timestamp ms (from Source 32, Detector 16 @ byte $byteOffset)")
            } else {
                Log.w("BLEDeviceConnection", "[$alias] Timestamp out of bounds at offset $byteOffset")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            Log.d("BleDeviceConnection", "MTU changed to $mtu for $alias (status=$status)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BleDeviceConnection", "MTU set to $mtu. Now discovering services.")
                gatt.discoverServices()
            } else {
                Log.w("BleDeviceConnection", "MTU request failed. Proceeding to discover services anyway.")
                gatt.discoverServices()
            }
        }

    }
}
