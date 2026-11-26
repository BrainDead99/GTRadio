package com.sc.gtradio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.roundToInt

/**
 * Single-line marquee TextView with an optional rounded stroke outline pass
 * so text remains legible over bright or high-contrast backgrounds.
 */
class OutlinedMarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var strokeColor: Int = Color.BLACK
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var strokeWidthDp: Float = 0f
        set(value) {
            val clamped = value.coerceAtLeast(0f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    private var isDrawingOutline = false

    init {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength((14f * resources.displayMetrics.density).roundToInt())
        isSelected = true
    }

    override fun isFocused(): Boolean {
        return ellipsize == TextUtils.TruncateAt.MARQUEE || super.isFocused()
    }

    override fun invalidate() {
        if (isDrawingOutline) return
        super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (strokeWidthDp <= 0f || text.isNullOrEmpty()) {
            super.onDraw(canvas)
            return
        }

        val savedColors = textColors
        val savedStyle = paint.style
        val savedStrokeWidth = paint.strokeWidth
        val savedJoin = paint.strokeJoin
        val savedCap = paint.strokeCap

        isDrawingOutline = true
        try {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidthDp * resources.displayMetrics.density
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            setTextColor(strokeColor)
            super.onDraw(canvas)

            paint.style = Paint.Style.FILL
            paint.strokeWidth = savedStrokeWidth
            paint.strokeJoin = savedJoin
            paint.strokeCap = savedCap
            setTextColor(savedColors)
            super.onDraw(canvas)
        } finally {
            paint.style = savedStyle
            paint.strokeWidth = savedStrokeWidth
            paint.strokeJoin = savedJoin
            paint.strokeCap = savedCap
            setTextColor(savedColors)
            isDrawingOutline = false
        }
    }
}
