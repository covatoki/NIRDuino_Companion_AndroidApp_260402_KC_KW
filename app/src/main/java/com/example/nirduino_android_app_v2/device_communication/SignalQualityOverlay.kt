package com.example.nirduino_android_app_v2.device_communication

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.example.nirduino_android_app_v2.device_communication_management.DisplayChannelData
import com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement

class SignalQualityOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var sources: List<OverlayElement> = emptyList()
    var detectors: List<OverlayElement> = emptyList()
    var channelPositions: List<DisplayChannelData> = emptyList()
    var sqiList: List<Float> = emptyList()

    enum class ChannelType {
        LONG, SHORT
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setOverlayData(
        sourceList: List<OverlayElement>,
        detectorList: List<OverlayElement>,
        channelList: List<DisplayChannelData>
    ) {
        // Collect all X and Y values across sources, detectors, and channels
        val allX = (sourceList + detectorList).map { it.x } + channelList.map { it.x }
        val allY = (sourceList + detectorList).map { it.y } + channelList.map { it.y }

        val minX = allX.minOrNull() ?: 0f
        val maxX = allX.maxOrNull() ?: 1f
        val minY = allY.minOrNull() ?: 0f
        val maxY = allY.maxOrNull() ?: 1f

        val spanX = maxX - minX
        val spanY = maxY - minY
        val margin = 0.05f

        fun normalizeX(x: Float): Float = margin + ((x - minX) / spanX) * (1f - 2 * margin)
        fun normalizeY(y: Float): Float = margin + ((1f - (y - minY) / spanY) * (1f - 2 * margin))  // flipped Y

        sources = sourceList.map { it.copy(x = normalizeX(it.x), y = normalizeY(it.y)) }
        detectors = detectorList.map { it.copy(x = normalizeX(it.x), y = normalizeY(it.y)) }
        channelPositions = channelList.map { it.copy(x = normalizeX(it.x), y = normalizeY(it.y) ) }

        invalidate()
    }

    fun updateSQI(newSQI: List<Float>) {
        sqiList = newSQI
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

//        Log.d("SQI_OVERLAY_ONDRAW", sqiList.toString())

        canvas.drawColor(Color.WHITE)

        // Draw sources (gray circles)
        paint.color = Color.LTGRAY
        sources.forEach {
            val x = it.x * width
            val y = it.y * height
//            Log.d("source", "$x,$y")
            canvas.drawCircle(x, y, 10f, paint)
        }

        // Draw detectors (gray squares)
        detectors.forEach {
            val x = it.x * width
            val y = it.y * height
//            Log.d("detector", "$x,$y")

            val size = 20f
            canvas.drawRect(x - size / 2, y - size / 2, x + size / 2, y + size / 2, paint)
        }

//        Log.d("SQI_OVERLAY_ONDRAW_channelList", channelPositions.size.toString())

        // Draw channels (diamond shape) with SQI
        channelPositions.forEachIndexed { i, channelData ->
            val x = channelData.x * width
            val y = channelData.y * height

            val sqi = sqiList.getOrNull(i) ?: 0f
            val color = when {
                sqi <= 1f -> Color.RED
                sqi <= 2f -> Color.parseColor("#FFC107") // amber
                else -> Color.parseColor("#44AA99") // green-accessible
            }

            // Draw diamond for channel
            paint.color = color
            val path = Path().apply {
                moveTo(x, y - 10f)
                lineTo(x + 10f, y)
                lineTo(x, y + 10f)
                lineTo(x - 10f, y)
                close()
            }
            canvas.drawPath(path, paint)

            // Draw label
            paint.color = Color.BLACK
            paint.textSize = 20f
            canvas.drawText("C${channelData.channelNumber + 1}", x + 10f, y + 20f, paint)
        }

    }
}
