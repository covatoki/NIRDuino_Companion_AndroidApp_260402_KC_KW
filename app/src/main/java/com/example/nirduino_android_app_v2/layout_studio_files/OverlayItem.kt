package com.example.nirduino_android_app_v2.layout_studio_files

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.hypot

sealed class OverlayItem(var x: Float, var y: Float, val id: Int) {

    abstract fun draw(canvas: Canvas, sizePx: Float)
    abstract fun contains(px: Float, py: Float, sizePx: Float): Boolean

    class Source(x: Float, y: Float, id: Int) : OverlayItem(x, y, id) {
        override fun draw(canvas: Canvas, sizePx: Float) {
            val paint = Paint().apply { color = Color.rgb(255, 165, 0) } // Orange
            canvas.drawCircle(x, y, sizePx / 2, paint)

            val textPaint = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                textSize = sizePx * 0.7f
            }
            canvas.drawText("S${toSubscript(id)}", x, y + sizePx * 0.3f, textPaint)
        }

        override fun contains(px: Float, py: Float, sizePx: Float): Boolean {
            return hypot(px - x, py - y) <= sizePx / 2
        }
    }

    class Detector(x: Float, y: Float, id: Int) : OverlayItem(x, y, id) {
        override fun draw(canvas: Canvas, sizePx: Float) {
            val rect = RectF(x - sizePx / 2, y - sizePx / 2, x + sizePx / 2, y + sizePx / 2)
            val fillPaint = Paint().apply { color = Color.LTGRAY }
            val borderPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }

            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, borderPaint)

            val textPaint = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                textSize = sizePx * 0.7f
            }
            canvas.drawText("D${toSubscript(id)}", x, y + sizePx * 0.3f, textPaint)
        }

        override fun contains(px: Float, py: Float, sizePx: Float): Boolean {
            return hypot(px - x, py - y) <= sizePx / 2
        }
    }

    companion object {
        fun toSubscript(n: Int): String {
            val subscriptDigits = mapOf(
                '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
                '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉'
            )
            return n.toString().map { subscriptDigits[it] ?: it }.joinToString("")
        }
    }
}
