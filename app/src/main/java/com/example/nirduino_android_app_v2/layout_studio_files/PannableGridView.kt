package com.example.nirduino_android_app_v2.layout_studio_files

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility")
class PannableGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ===== Grid =====
    private val gridPaint = Paint()
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var scaleFactor = 1.0f
    private var mmPerCell = 1.0f
    private lateinit var gridShader: BitmapShader

    // mm <-> px helpers
    private val dpi = context.resources.displayMetrics.xdpi
    fun mmToPx(mm: Float): Float = mm * dpi / 25.4f
    fun pxToMm(px: Float): Float = px * 25.4f / dpi

    private val gridSpacingMm = 1f // 1 mm per cell

    // ===== Overlays =====
    private val overlays = mutableListOf<OverlayItem>()
    private var draggingItem: OverlayItem? = null

    // ===== Coordinate Badge (drawn in screen space) =====
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity

    private val coordTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 14f * scaledDensity
        typeface = Typeface.MONOSPACE
    }
    private val coordBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE0FFFFFF.toInt() // semi-opaque white
        style = Paint.Style.FILL
    }
    private val coordBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF444444.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val coordPadH = 8f * density
    private val coordPadV = 6f * density
    private val coordCorner = 8f * density
    private val coordNudge = 10f * density  // offset label a bit from finger/shape

    init {
        generateGridShader()
        gridPaint.shader = gridShader

        setOnTouchListener { _, event ->
            val canvasX = (event.x - offsetX) / scaleFactor
            val canvasY = (event.y - offsetY) / scaleFactor

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    draggingItem = overlays.findLast {
                        it.contains(canvasX, canvasY, mmToPx(4f))
                    }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y

                    if (draggingItem != null) {
                        val rawX = (event.x - offsetX) / scaleFactor
                        val rawY = (event.y - offsetY) / scaleFactor

                        // Snap to nearest 1 mm grid in *canvas* space
                        val cellPx = mmToPx(gridSpacingMm)
                        val snappedX = floor((rawX / cellPx).roundToInt() * cellPx)
                        val snappedY = floor((rawY / cellPx).roundToInt() * cellPx)

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
                    invalidate()
                }
            }
            true
        }
    }

    // ===== Grid drawing =====
    fun generateGridShader() {
        val baseMmPerCell = 1.0f
        val cellSizePx = (mmToPx(baseMmPerCell) * scaleFactor).toInt().coerceAtLeast(1)

        val gridBitmap = Bitmap.createBitmap(cellSizePx, cellSizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(gridBitmap)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }
        // Top/Left lines to form a repeating grid
        c.drawLine(0f, 0f, cellSizePx.toFloat(), 0f, linePaint)
        c.drawLine(0f, 0f, 0f, cellSizePx.toFloat(), linePaint)

        gridShader = BitmapShader(gridBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        gridPaint.shader = gridShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ---- 1) Draw grid + items in *canvas* space (affected by pan+zoom) ----
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scaleFactor, scaleFactor)

        canvas.drawPaint(gridPaint)

        val visualSize = mmToPx(1f) * scaleFactor
        overlays.forEach { it.draw(canvas, visualSize) }

        canvas.restore()

        // ---- 2) Draw coordinate badge ABOVE item + ARROW ----
        draggingItem?.let { item ->
            val screenX = offsetX + item.x * scaleFactor
            val screenY = offsetY + item.y * scaleFactor

            val xMm = pxToMm(item.x)
            val yMm = pxToMm(item.y)
            val label = "x=%.1f mm, y=%.1f mm".format(xMm, yMm)

            // Measure text
            val textWidth = coordTextPaint.measureText(label)
            val fm = coordTextPaint.fontMetrics
            val textHeight = (fm.bottom - fm.top)

            // Badge width/height
            val badgeWidth = textWidth + 2 * coordPadH
            val badgeHeight = textHeight + 2 * coordPadV

            // ---- BADGE POSITION ABOVE ITEM ----
            val arrowHeight = 12f * density
            val spacing = 6f * density

            val centerX = screenX          // item center
            val badgeLeft = centerX - badgeWidth / 2f
            val badgeBottom = screenY - spacing - arrowHeight
            val badgeTop = badgeBottom - badgeHeight
            val badgeRight = badgeLeft + badgeWidth

            val rect = RectF(badgeLeft, badgeTop, badgeRight, badgeBottom)

            // Keep inside screen X bounds
            val shiftX =
                when {
                    rect.left < 0 -> -rect.left + 4 * density
                    rect.right > width -> width - rect.right - 4 * density
                    else -> 0f
                }

            rect.offset(shiftX, 0f)

            // ---- DRAW BADGE ----
            canvas.drawRoundRect(rect, coordCorner, coordCorner, coordBgPaint)
            canvas.drawRoundRect(rect, coordCorner, coordCorner, coordBorderPaint)

            // ---- TEXT ----
            val textX = rect.left + coordPadH
            val textY = rect.top + coordPadV - fm.top
            canvas.drawText(label, textX, textY, coordTextPaint)

            // ---- DRAW ARROW (small triangle) ----
            val arrowCenterX = rect.centerX()
            val arrowTopY = rect.bottom
            val arrowBottomY = arrowTopY + arrowHeight

            val arrowPath = Path().apply {
                moveTo(arrowCenterX, arrowBottomY)           // bottom point
                lineTo(arrowCenterX - 10f * density, arrowTopY) // left
                lineTo(arrowCenterX + 10f * density, arrowTopY) // right
                close()
            }

            canvas.drawPath(arrowPath, coordBgPaint)
            canvas.drawPath(arrowPath, coordBorderPaint)
        }
    }

    // ===== Public controls =====
    fun zoomIn() {
        scaleFactor *= 1.1f
        generateGridShader()
        invalidate()
    }

    fun zoomOut() {
        scaleFactor /= 1.1f
        generateGridShader()
        invalidate()
    }

    fun recenterOnItems() {
        if (overlays.isEmpty()) return

        val minX = overlays.minOf { it.x }
        val maxX = overlays.maxOf { it.x }
        val minY = overlays.minOf { it.y }
        val maxY = overlays.maxOf { it.y }

        val contentCenterX = (minX + maxX) / 2f
        val contentCenterY = (minY + maxY) / 2f

        val viewCenterX = width / 2f
        val viewCenterY = height / 2f

        offsetX = viewCenterX - (contentCenterX * scaleFactor)
        offsetY = viewCenterY - (contentCenterY * scaleFactor)
        invalidate()
    }

    fun getMmPerCell(): Int = mmPerCell.roundToInt()

    fun loadFromLayoutItem(item: LayoutStudioItem) {
        overlays.clear()

        var x = 0f
        var y = 0f
        item.selectedSources.sorted().forEach { id ->
            overlays.add(OverlayItem.Source(mmToPx(x), mmToPx(y), id))
            x += mmPerCell
        }

        x = 0f
        y = mmPerCell
        item.selectedDetectors.sorted().forEach { id ->
            overlays.add(OverlayItem.Detector(mmToPx(x), mmToPx(y), id))
            x += mmPerCell
        }

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
                is OverlayItem.Source -> OverlayElement(pxToMm(it.x), pxToMm(it.y), true, it.id)
                is OverlayItem.Detector -> OverlayElement(pxToMm(it.x), pxToMm(it.y), false, it.id)
            }
        }
    }

    fun setOverlayElements(elements: List<OverlayElement>) {
        overlays.clear()
        for (e in elements) {
            val item = if (e.isSource)
                OverlayItem.Source(mmToPx(e.x), mmToPx(e.y), e.id)
            else
                OverlayItem.Detector(mmToPx(e.x), mmToPx(e.y), e.id)
            overlays.add(item)
        }
        flipLayoutY()
        invalidate()
    }
}
