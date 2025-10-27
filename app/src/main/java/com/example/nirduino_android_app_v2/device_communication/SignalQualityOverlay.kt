package com.example.nirduino_android_app_v2.device_communication

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.nirduino_android_app_v2.device_communication_management.DisplayChannelData
import com.example.nirduino_android_app_v2.layout_studio_files.OverlayElement
import kotlin.math.max
import kotlin.math.min

class SignalQualityOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var sources: List<OverlayElement> = emptyList()
    var detectors: List<OverlayElement> = emptyList()
    var channelPositions: List<DisplayChannelData> = emptyList()
    var sqiList: List<Float> = emptyList()

    enum class ChannelType { LONG, SHORT }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
    }

    /**
     * Update overlay data. Works for both 2D layouts and 1D (all X or all Y are the same).
     * If span along an axis is ~0, we center everything along that axis.
     */
    fun setOverlayData(
        sourceList: List<OverlayElement>,
        detectorList: List<OverlayElement>,
        channelList: List<DisplayChannelData>
    ) {
        // Collect all X and Y values across sources, detectors, and channels
        val allX = (sourceList + detectorList).map { it.x } + channelList.map { it.x }
        val allY = (sourceList + detectorList).map { it.y } + channelList.map { it.y }

        // Handle completely empty case
        if (allX.isEmpty() || allY.isEmpty()) {
            sources = emptyList()
            detectors = emptyList()
            channelPositions = emptyList()
            invalidate()
            return
        }

        val minX = allX.minOrNull() ?: 0f
        val maxX = allX.maxOrNull() ?: 1f
        val minY = allY.minOrNull() ?: 0f
        val maxY = allY.maxOrNull() ?: 1f

        val spanX = maxX - minX
        val spanY = maxY - minY

        // For 1D layouts, span may be 0 (all points have the same X or Y).
        // When that happens, we center along that axis so nothing collapses.
        val eps = 1e-6f
        val margin = 0.05f
        val inner = 1f - 2f * margin

        fun clamp01(v: Float): Float = min(1f, max(0f, v))

        fun normalizeX(x: Float): Float {
            return if (spanX < eps) {
                0.5f // center horizontally for 1D vertical layouts
            } else {
                val t = (x - minX) / max(spanX, eps)
                clamp01(margin + t * inner)
            }
        }

        // flipped Y for canvas coordinates
        fun normalizeY(y: Float): Float {
            return if (spanY < eps) {
                0.5f // center vertically for 1D horizontal layouts
            } else {
                val t = (y - minY) / max(spanY, eps)
                clamp01(margin + (1f - t) * inner)
            }
        }

        sources = sourceList.map { it.copy(x = normalizeX(it.x), y = normalizeY(it.y)) }
        detectors = detectorList.map { it.copy(x = normalizeX(it.x), y = normalizeY(it.y)) }
        channelPositions = channelList.map { it.copy(x = normalizeX(it.x), y = normalizeY(it.y)) }

        invalidate()
    }

    fun updateSQI(newSQI: List<Float>) {
        sqiList = newSQI
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.WHITE)

        // Draw sources (gray circles)
        paint.color = Color.LTGRAY
        sources.forEach {
            val x = it.x * width
            val y = it.y * height
            canvas.drawCircle(x, y, 10f, paint)
        }

        // Draw detectors (gray squares)
        detectors.forEach {
            val x = it.x * width
            val y = it.y * height
            val size = 20f
            canvas.drawRect(x - size / 2, y - size / 2, x + size / 2, y + size / 2, paint)
        }

        // Draw channels (diamond shape) with SQI
        channelPositions.forEachIndexed { i, channelData ->
            val x = channelData.x * width
            val y = channelData.y * height

            val sqi = sqiList.getOrNull(i) ?: 0f
            val color = when {
                sqi <= 1f -> Color.RED
                sqi <= 2f -> Color.parseColor("#FFC107") // amber
                else -> Color.parseColor("#44AA99")      // green-accessible
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
