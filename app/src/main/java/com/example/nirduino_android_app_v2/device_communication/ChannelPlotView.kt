package com.example.nirduino_android_app_v2.device_communication

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class ChannelPlotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // === Styling (kept light/clean) ===
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val irPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 3f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY; textSize = 22f
    }
    private val axisTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY; textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY; textSize = 20f
    }
    private val legendBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (background as? ColorDrawable)?.color ?: Color.WHITE
        alpha = 230
    }

    // === Data ===
    private var ts: List<Float> = emptyList()
    private var red: List<Float> = emptyList()
    private var ir: List<Float> = emptyList()

    // Configure the rolling window (seconds)
    var windowSeconds: Float = 10f

    // === Stimulus highlight data ===
    private data class StimulusInterval(val label: String, val start: Float, var end: Float? = null)

    private val stimIntervals = mutableListOf<StimulusInterval>() // all events ever recorded
    private var stimPalette: Map<String, Int> = emptyMap()        // label -> base color

    // single fill paint reused for bands
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // alpha set per-color below
    }

    fun setStimulusPalette(palette: Map<String, Int>) {
        stimPalette = palette.toMap()
        invalidate()
    }

    /** Record a start/stop event with plot-time `t` (same timebase as your data). */
    fun addStimulusEvent(label: String, isStart: Boolean, t: Float) {
        if (isStart) {
            // start a new open interval
            stimIntervals.add(StimulusInterval(label, start = t, end = null))
        } else {
            // close the most recent open interval for this label
            for (i in stimIntervals.size - 1 downTo 0) {
                val itv = stimIntervals[i]
                if (itv.label == label && itv.end == null) {
                    itv.end = t
                    break
                }
            }
        }
        invalidate()
    }

    fun updateData(timestamps: List<Float>, redSeries: List<Float>, irSeries: List<Float>) {
        if (timestamps.isEmpty() || redSeries.size != timestamps.size || irSeries.size != timestamps.size) return

        // Keep only the latest increasing suffix
        var start = 0
        for (i in 1 until timestamps.size) {
            if (timestamps[i] < timestamps[i - 1]) start = i
        }
        val t = timestamps.subList(start, timestamps.size)
        val r = redSeries.subList(start, redSeries.size)
        val iR = irSeries.subList(start, irSeries.size)

        // Moving window
        val cutoff = t.last()- windowSeconds
        val firstIdx = t.indexOfFirst { it >= cutoff }.let { if (it == -1) 0 else it }
        ts  = t.subList(firstIdx, t.size)
        red = r.subList(firstIdx, r.size)
        ir  = iR.subList(firstIdx, iR.size)


        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (ts.size < 2) return

        val paddingLeft = 100f
        val paddingRight = 60f
        val paddingTop = 60f
        val paddingBottom = 70f

        val plotWidth = width - paddingLeft - paddingRight
        val plotHeight = height - paddingTop - paddingBottom

        val tMin = ts.first()
        val tMax = ts.last()
        val eps = 1e-6f

        // Y limits strictly from data
        val yMin = min(red.minOrNull() ?: 0f, ir.minOrNull() ?: 0f)
        val yMax = max(red.maxOrNull() ?: 1f, ir.maxOrNull() ?: 1f)

        fun xAt(t: Float) = paddingLeft + ((t - tMin) / (tMax - tMin + eps)) * plotWidth
        fun yAt(v: Float) = paddingTop + plotHeight * (1f - ((v - yMin) / (yMax - yMin + eps)))

        // ---- Draw stimulus highlight bands (behind curves) ----
        // Determine which labels have any overlap in the visible window
        val labelsInWindow = linkedSetOf<String>()
        for (itv in stimIntervals) {
            val s = max(itv.start, tMin)
            val e = min(itv.end ?: tMax, tMax)
            if (e > s) labelsInWindow.add(itv.label)
        }

        if (labelsInWindow.isNotEmpty()) {
            val bandHeight = plotHeight / labelsInWindow.size
            var i = 0
            for (label in labelsInWindow) {
                val topY = paddingTop + bandHeight * i
                val botY = topY + bandHeight

                val baseColor = (stimPalette[label] ?: Color.GRAY)
                // alpha 0.25
                val bandColor = (baseColor and 0x00FFFFFF) or (0x40 shl 24) // 0x40 ≈ 64/255 ≈ 0.25
                bandPaint.color = bandColor

                // draw each overlapping interval for this label
                for (itv in stimIntervals) {
                    if (itv.label != label) continue
                    val startX = xAt(max(itv.start, tMin))
                    val endX   = xAt(min(itv.end ?: tMax, tMax))
                    if (endX > startX) {
                        canvas.drawRect(startX, topY, endX, botY, bandPaint)
                    }
                }
                i++
            }
        }

        // ---- Ticks & labels ----
        val yTicks = 5
        for (k in 0..yTicks) {
            val yVal = yMin + k * (yMax - yMin) / yTicks
            val yPix = yAt(yVal)
            canvas.drawLine(paddingLeft - 10, yPix, paddingLeft, yPix, tickPaint)
            canvas.drawText(String.format("%.2f", yVal), 20f, yPix + 6f, labelPaint)
        }

        val xTicks = 5
        val xBase = height - paddingBottom
        for (k in 0..xTicks) {
            val tVal = tMin + k * (tMax - tMin) / xTicks
            val xPix = xAt(tVal)
            canvas.drawLine(xPix, xBase, xPix, xBase + 10, tickPaint)
            canvas.drawText(String.format("%.1f", tVal), xPix - 18f, xBase + 28f, labelPaint)
        }

        // ---- Axis titles ----
        drawTextBox(canvas, "Raw Voltage (V)", 2f, paddingTop - 30f, axisTitlePaint, legendBgPaint, Paint.Align.LEFT)
        drawTextBox(canvas, "Time (s)", width - paddingRight/2, xBase + 60f, axisTitlePaint, legendBgPaint, Paint.Align.RIGHT)

        // ---- Legend ----
        val legendX = width - paddingRight - 140f
        val legendTop = paddingTop + 6f
        drawLegendEntry(canvas, legendX, legendTop, "Red", redPaint)
        drawLegendEntry(canvas, legendX, legendTop + 28f, "IR", irPaint)

        // ---- Series paths ----
        fun buildPath(series: List<Float>): Path {
            val p = Path()
            for (idx in series.indices) {
                val x = xAt(ts[idx])
                val y = yAt(series[idx])
                if (idx == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }

        // Draw curves on top of highlights
        canvas.drawPath(buildPath(red), redPaint)
        canvas.drawPath(buildPath(ir), irPaint)
    }

    private fun drawTextBox(
        canvas: Canvas, text: String, x: Float, y: Float,
        paint: Paint, bgPaint: Paint, align: Paint.Align
    ) {
        val oldAlign = paint.textAlign
        paint.textAlign = align

        val padH = 8f; val padV = 4f
        val fm = paint.fontMetrics
        val textW = paint.measureText(text)

        val rect = when (align) {
            Paint.Align.LEFT   -> RectF(x - padH, y + fm.top - padV, x + textW + padH, y + fm.bottom + padV)
            Paint.Align.CENTER -> RectF(x - textW/2 - padH, y + fm.top - padV, x + textW/2 + padH, y + fm.bottom + padV)
            Paint.Align.RIGHT  -> RectF(x - textW - padH, y + fm.top - padV, x + padH, y + fm.bottom + padV)
        }

        canvas.drawRoundRect(rect, 10f, 10f, bgPaint)
        canvas.drawText(text, x, y, paint)
        paint.textAlign = oldAlign
    }

    private fun drawLegendEntry(canvas: Canvas, x: Float, baselineY: Float, label: String, linePaint: Paint) {
        val lineLen = 36f
        canvas.drawLine(x, baselineY, x + lineLen, baselineY, linePaint)
        drawTextBox(canvas, label, x + lineLen + 8f, baselineY + 2f, legendTextPaint, legendBgPaint, Paint.Align.LEFT)
    }
}
