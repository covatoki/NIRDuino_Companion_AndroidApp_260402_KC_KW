package com.example.nirduino_android_app_v2.device_communication

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class ChannelPlotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private var plotData: List<Float> = emptyList()

    fun updateData(newData: List<Float>) {
        plotData = newData
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (plotData.size < 2) return

        val maxVal = plotData.maxOrNull() ?: 1f
        val minVal = plotData.minOrNull() ?: 0f
        val plotHeight = height.toFloat()
        val plotWidth = width.toFloat()
        val step = plotWidth / (plotData.size - 1)

        val path = Path()
        for (i in plotData.indices) {
            val x = i * step
            val normalizedY = (plotData[i] - minVal) / (maxVal - minVal + 1e-6f)
            val y = plotHeight * (1f - normalizedY)

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
