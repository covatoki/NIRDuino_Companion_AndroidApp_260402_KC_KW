package com.example.nirduino_android_app_v2.device_communication_management

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.*

class CsvSessionLogger(
    private val context: Context,
    private val deviceAlias: String,
    private val layoutName: String
) {
    private var writer: BufferedWriter? = null
    var fileName: String = ""
        private set
    var absolutePath: String = ""
        private set

    fun start(getHeader: () -> String) {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val stamp = sdf.format(java.util.Date())
        fileName = "${deviceAlias}_${layoutName}_$stamp.csv"

        // App-specific external storage: safe under scoped storage; no runtime permission
        val baseDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "NIRDuinoCompanion/RawSessionData"
        )
        if (!baseDir.exists()) baseDir.mkdirs()

        val file = File(baseDir, fileName)
        absolutePath = file.absolutePath

        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, false)))
        writer!!.write(getHeader())
        writer!!.newLine()
        writer!!.flush()
        Log.i("CsvSessionLogger", "Started CSV: $absolutePath")
    }

    @Synchronized
    fun appendLine(line: String) {
        writer?.apply {
            write(line)
            newLine()
            // keep flush; you can throttle if you want fewer fsyncs
            flush()
        }
    }

    fun close() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {}
        writer = null
    }
}
