package com.example.nirduino_android_app_v2.layout_studio_files

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt
@SuppressLint("ClickableViewAccessibility")
class PannableGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Grid
    private val gridPaint = Paint()
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var scaleFactor = 1.0f
    private var mmPerCell = 1.0f
    private lateinit var gridShader: BitmapShader

    // mmto px
    val dpi = context.resources.displayMetrics.xdpi
    fun mmToPx(mm: Float): Float = mm * dpi / 25.4f

    private val gridSpacingMm = 1f  // or however many mm you want per cell

    // Overlays
    private val overlays = mutableListOf<OverlayItem>()
    private var draggingItem: OverlayItem? = null

    init {
        generateGridShader()
        gridPaint.shader = gridShader

//        // Add one test source and detector
//        overlays.add(OverlayItem.Source(200f, 200f, id = 1))
//        overlays.add(OverlayItem.Detector(500f, 300f, id = 1))

        setOnTouchListener { _, event ->
            val canvasX = (event.x - offsetX) / scaleFactor
            val canvasY = (event.y - offsetY) / scaleFactor

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    draggingItem = overlays.findLast {
                        it.contains(canvasX, canvasY, mmToPx(1f))
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y

                    if (draggingItem != null) {
                        val rawX = (event.x - offsetX) / scaleFactor
                        val rawY = (event.y - offsetY) / scaleFactor

                        // Snap to nearest grid
                        val snappedX = (rawX / mmToPx(gridSpacingMm)).roundToInt() * mmToPx(gridSpacingMm)
                        val snappedY = (rawY / mmToPx(gridSpacingMm)).roundToInt() * mmToPx(gridSpacingMm)

                        draggingItem?.x = snappedX
                        draggingItem?.y = snappedY

                        invalidate()
                    } else {
                        offsetX += dx
                        offsetY += dy
                        invalidate()
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingItem = null
                }
            }
            true
        }


    }

    private fun generateGridShader() {
        val cellSizePx = (mmPerCell * dpi / 25.4f).toInt().coerceAtLeast(4)
        val gridBitmap = Bitmap.createBitmap(cellSizePx, cellSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gridBitmap)
        val linePaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 0.5f
        }
        canvas.drawLine(0f, 0f, cellSizePx.toFloat(), 0f, linePaint)
        canvas.drawLine(0f, 0f, 0f, cellSizePx.toFloat(), linePaint)
        gridShader = BitmapShader(gridBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        gridPaint.shader = gridShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()

        // First apply pan (offsetX/Y), then zoom (scaleFactor)
        canvas.translate(offsetX, offsetY)
        canvas.scale(scaleFactor, scaleFactor)

        canvas.drawPaint(gridPaint)

        // Draw overlays with zoom-adjusted size
        val visualSize = mmToPx(1f) * scaleFactor
        overlays.forEach { it.draw(canvas, visualSize) }

        canvas.restore()
    }

    fun zoomIn() {
        scaleFactor *= 1.1f
        invalidate()
    }

    fun zoomOut() {
        scaleFactor /= 1.1f
        invalidate()
    }

    fun recenterOnItems() {
        if (overlays.isEmpty()) return

        // Compute bounding box of all items
        val minX = overlays.minOf { it.x }
        val maxX = overlays.maxOf { it.x }
        val minY = overlays.minOf { it.y }
        val maxY = overlays.maxOf { it.y }

        val contentCenterX = (minX + maxX) / 2
        val contentCenterY = (minY + maxY) / 2

        // Get view center
        val viewCenterX = width / 2f
        val viewCenterY = height / 2f

        // Update pan offsets
        offsetX = viewCenterX - (contentCenterX * scaleFactor)
        offsetY = viewCenterY - (contentCenterY * scaleFactor)

        invalidate()
    }

    fun getMmPerCell(): Int = mmPerCell.roundToInt()

    fun loadFromLayoutItem(item: LayoutStudioItem) {
        overlays.clear()

        var x = 0f
        var y = 0f
        item.selectedSources.sorted().forEachIndexed { i, id ->
            overlays.add(OverlayItem.Source(x, y, id))
            x += mmToPx(1f)
        }

        x = 0f
        y = mmToPx(1f)
        item.selectedDetectors.sorted().forEachIndexed { i, id ->
            overlays.add(OverlayItem.Detector(x, y, id))
            x += mmToPx(1f)
        }

        // 🔁 Flip after loading
        flipLayoutY()

        invalidate()
    }


    private fun flipLayoutY() {
        if (overlays.isEmpty()) return

        val maxY = overlays.maxOf { it.y }
        overlays.forEach { it.y = maxY - it.y }
    }


    fun getOverlayElements(): List<OverlayElement> {
        return overlays.map {
            when (it) {
                is OverlayItem.Source -> OverlayElement(it.x, it.y, true, it.id)
                is OverlayItem.Detector -> OverlayElement(it.x, it.y, false, it.id)
            }
        }
    }

    fun setOverlayElements(elements: List<OverlayElement>) {
        overlays.clear()
        for (e in elements) {
            val item = if (e.isSource) OverlayItem.Source(e.x, e.y, e.id)
            else OverlayItem.Detector(e.x, e.y, e.id)
            overlays.add(item)
        }

        // 🔁 Flip layout Y *after* loading real saved positions
        flipLayoutY()

        invalidate()
    }


}
