package com.sc.gtradio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class GtaVolumeBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val maxBars = 17
    var currentBars = 12
        set(value) {
            field = value.coerceIn(0, maxBars)
            invalidate()
        }

    var continuousMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var activeBarColor: Int = Color.parseColor("#B8CFE6")
        set(value) {
            field = value
            invalidate()
        }

    var inactiveBarColor: Int = Color.parseColor("#2E4A63")
        set(value) {
            field = value
            invalidate()
        }

    var onVolumeBarsChanged: ((Int, Int) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (continuousMode) {
            val barH = (h * 0.48f).coerceAtLeast(8f)
            val top = (h - barH) / 2f
            val bottom = top + barH

            barPaint.color = inactiveBarColor
            canvas.drawRect(0f, top, w, bottom, barPaint)

            val fillRatio = (currentBars.toFloat() / maxBars.toFloat()).coerceIn(0f, 1f)
            if (fillRatio > 0f) {
                barPaint.color = activeBarColor
                canvas.drawRect(0f, top, w * fillRatio, bottom, barPaint)
            }
            return
        }

        val totalSlots = maxBars * 2 - 1
        val barWidth = w / totalSlots.toFloat()
        val gap = barWidth

        for (i in 0 until maxBars) {
            val left = i * (barWidth + gap)
            val right = left + barWidth
            val heightRatio = 0.25f + (0.75f * (i.toFloat() / (maxBars - 1).toFloat()))
            val barHeight = h * heightRatio
            val top = h - barHeight

            barPaint.color = if (i < currentBars) activeBarColor else inactiveBarColor
            canvas.drawRect(left, top, right, h, barPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val ratio = (event.x / width.toFloat()).coerceIn(0f, 1f)
                val newBars = (ratio * maxBars).roundToInt().coerceIn(0, maxBars)
                if (newBars != currentBars) {
                    currentBars = newBars
                    onVolumeBarsChanged?.invoke(currentBars, maxBars)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
