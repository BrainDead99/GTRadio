package com.sc.gtradio

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Custom radio selector wheel supporting a full circular perimeter layout
 * and a one-handed left/right revolving arc carousel with a fixed spotlight ring.
 */
class RadioWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class WheelMode {
        FULL_CIRCLE,
        ARC_LEFT,
        ARC_RIGHT
    }

    data class WheelSlot(
        val mediaId: String?,
        val title: String,
        var icon: Bitmap? = null,
        val isOffSlot: Boolean = (mediaId == null)
    )

    var wheelMode: WheelMode = WheelMode.FULL_CIRCLE
        set(value) {
            if (field != value) {
                field = value
                cancelArcAnimator()
                scrollIndex = highlightedSlotIndex.toFloat()
                invalidate()
            }
        }

    var accentColor: Int = Color.parseColor("#5EC2F2")
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var inactiveDiscColor: Int = Color.parseColor("#78000000")
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var activeDiscColor: Int = Color.parseColor("#D8000000")
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onSlotHovered: ((WheelSlot) -> Unit)? = null
    var onSlotCommitted: ((WheelSlot) -> Unit)? = null

    private val slots = ArrayList<WheelSlot>()
    private var offSlotIndex: Int = -1
    private var highlightedSlotIndex: Int = 0
    private var hoveredSlotIndex: Int = -1

    // Continuous slot index positioned at the fixed middle spotlight in arc modes.
    private var scrollIndex: Float = 0f
    private var arcAnimator: ValueAnimator? = null

    private var activeMediaId: String? = null
    private var isRadioPlaying: Boolean = false

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var isTrackingTouch = false
    private var isDragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastMoveY = 0f

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val offSymbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val drawRect = RectF()
    private val stageRect = RectF()

    private data class CircleGeometry(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val iconRadius: Float
    )

    private data class ArcGeometry(
        val cx: Float,
        val cy: Float,
        val arcRadius: Float,
        val spotlightX: Float,
        val iconRadius: Float,
        val slotAngleStep: Float
    )

    fun setSlots(newSlots: List<WheelSlot>) {
        slots.clear()
        slots.addAll(newSlots)
        if (slots.none { it.isOffSlot }) {
            slots.add(WheelSlot(mediaId = null, title = "Radio Off", icon = null, isOffSlot = true))
        }
        offSlotIndex = slots.indexOfFirst { it.isOffSlot }.coerceAtLeast(0)
        syncTargetFromActiveState(animate = false)
        invalidate()
    }

    fun updateSlotIcon(mediaId: String?, bitmap: Bitmap?) {
        if (bitmap == null) return
        var changed = false
        for (slot in slots) {
            val matches = if (mediaId == null) slot.isOffSlot else slot.mediaId == mediaId
            if (matches) {
                slot.icon = bitmap
                changed = true
            }
        }
        if (changed) {
            invalidate()
        }
    }

    fun setActiveMediaId(mediaId: String?, isPlaying: Boolean) {
        activeMediaId = mediaId
        isRadioPlaying = isPlaying
        if (!isTrackingTouch) {
            syncTargetFromActiveState(animate = wheelMode != WheelMode.FULL_CIRCLE && width > 0)
            invalidate()
        }
    }

    private fun syncTargetFromActiveState(animate: Boolean) {
        if (slots.isEmpty()) {
            highlightedSlotIndex = 0
            scrollIndex = 0f
            return
        }
        val targetIndex = if (!isRadioPlaying || activeMediaId.isNullOrBlank()) {
            offSlotIndex.coerceIn(0, slots.lastIndex)
        } else {
            val found = slots.indexOfFirst { !it.isOffSlot && it.mediaId == activeMediaId }
            if (found >= 0) found else offSlotIndex.coerceIn(0, slots.lastIndex)
        }
        highlightedSlotIndex = targetIndex
        if (wheelMode == WheelMode.FULL_CIRCLE || !animate) {
            cancelArcAnimator()
            scrollIndex = targetIndex.toFloat()
        } else {
            animateArcToSlot(targetIndex, commitOnEnd = false, durationMs = 240L)
        }
    }

    fun getMetadataStageRect(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            stageRect.set(0f, 0f, 0f, 0f)
            return stageRect
        }

        if (wheelMode == WheelMode.FULL_CIRCLE) {
            val g = computeCircleGeometry(w, h)
            val innerClear = (g.radius - g.iconRadius * 1.24f - 6f * density).coerceAtLeast(36f * density)
            val halfSpan = innerClear * 0.70f
            stageRect.set(g.cx - halfSpan, g.cy - halfSpan, g.cx + halfSpan, g.cy + halfSpan)
            return stageRect
        }

        val g = computeArcGeometry(w, h)
        val stageHalfH = min(h * 0.42f, 178f * density)
        val sinMax = (stageHalfH / g.arcRadius).coerceIn(0f, 0.86f)
        val arcBulge = g.arcRadius * (1f - sqrt(1f - sinMax * sinMax))
        val edgePad = 14f * density
        val safeGap = g.iconRadius * 1.24f + arcBulge + 10f * density

        if (wheelMode == WheelMode.ARC_LEFT) {
            val left = (g.spotlightX + safeGap).coerceAtMost(w * 0.52f)
            val right = (w - edgePad).coerceAtLeast(left + 64f * density)
            stageRect.set(left, g.cy - stageHalfH, right, g.cy + stageHalfH)
        } else {
            val right = (g.spotlightX - safeGap).coerceAtLeast(w * 0.48f)
            val left = edgePad.coerceAtMost(right - 64f * density)
            stageRect.set(left, g.cy - stageHalfH, right, g.cy + stageHalfH)
        }
        return stageRect
    }

    private fun computeCircleGeometry(w: Float, h: Float): CircleGeometry {
        val count = slots.size.coerceAtLeast(1)
        val outerPad = 8f * density
        val maxHalf = (min(w, h) / 2f - outerPad).coerceAtLeast(48f * density)
        val sinHalfStep = sin(PI / count).toFloat().coerceAtLeast(0.05f)
        // Balance icon radius against perimeter spacing so icons never collide.
        val targetRing = maxHalf * 0.82f
        val maxIconByChord = targetRing * sinHalfStep * 0.80f
        val iconRadius = min(23f * density, maxIconByChord).coerceAtLeast(12f * density)
        val ringRadius = (maxHalf - iconRadius * 1.20f).coerceAtLeast(40f * density)
        return CircleGeometry(
            cx = w / 2f,
            cy = h / 2f,
            radius = ringRadius,
            iconRadius = iconRadius
        )
    }

    private fun computeArcGeometry(w: Float, h: Float): ArcGeometry {
        val iconRadius = 27f * density
        val edgeMargin = 14f * density
        val spotlightOffset = edgeMargin + iconRadius * 1.18f
        val arcRadius = (h * 0.64f).coerceIn(205f * density, 310f * density)
        val slotArcStep = 60f * density
        val slotAngleStep = slotArcStep / arcRadius
        val cy = h / 2f
        return if (wheelMode == WheelMode.ARC_LEFT) {
            val spotlightX = spotlightOffset
            ArcGeometry(
                cx = spotlightX + arcRadius,
                cy = cy,
                arcRadius = arcRadius,
                spotlightX = spotlightX,
                iconRadius = iconRadius,
                slotAngleStep = slotAngleStep
            )
        } else {
            val spotlightX = w - spotlightOffset
            ArcGeometry(
                cx = spotlightX - arcRadius,
                cy = cy,
                arcRadius = arcRadius,
                spotlightX = spotlightX,
                iconRadius = iconRadius,
                slotAngleStep = slotAngleStep
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slots.isEmpty() || width <= 0 || height <= 0) return

        if (wheelMode == WheelMode.FULL_CIRCLE) {
            drawFullCircle(canvas)
        } else {
            drawArcCarousel(canvas)
        }
    }

    private fun drawFullCircle(canvas: Canvas) {
        val g = computeCircleGeometry(width.toFloat(), height.toFloat())
        val count = slots.size
        val stepRad = (2.0 * PI / count).toFloat()
        val offIdx = offSlotIndex.coerceIn(0, count - 1)
        val activeIdx = if (isTrackingTouch && hoveredSlotIndex in slots.indices) hoveredSlotIndex else highlightedSlotIndex

        var activeCenterX = 0f
        var activeCenterY = 0f

        for (i in 0 until count) {
            val angle = (PI.toFloat() / 2f) + (i - offIdx) * stepRad
            val sx = g.cx + g.radius * cos(angle)
            val sy = g.cy + g.radius * sin(angle)
            if (i == activeIdx) {
                activeCenterX = sx
                activeCenterY = sy
            } else {
                drawSlotNode(canvas, slots[i], sx, sy, g.iconRadius, isActive = false, fadeAlpha = 1f)
            }
        }

        if (activeIdx in slots.indices) {
            drawSlotNode(canvas, slots[activeIdx], activeCenterX, activeCenterY, g.iconRadius, isActive = true, fadeAlpha = 1f)
        }
    }

    private fun drawArcCarousel(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val g = computeArcGeometry(w, h)
        val count = slots.size
        val baseAngle = if (wheelMode == WheelMode.ARC_LEFT) PI.toFloat() else 0f
        val dirSign = if (wheelMode == WheelMode.ARC_LEFT) -1f else 1f
        val halfAngleLimit = (PI.toFloat() / 2f) + 0.15f
        val spotlightIdx = wrapSlotIndex(scrollIndex.roundToInt(), count)

        var activeX = 0f
        var activeY = 0f
        var activeFade = 1f

        for (i in 0 until count) {
            val delta = wrappedDelta(i - scrollIndex, count)
            val offsetAngle = delta * g.slotAngleStep
            if (abs(offsetAngle) > halfAngleLimit) continue

            val angle = baseAngle + dirSign * offsetAngle
            val sx = g.cx + g.arcRadius * cos(angle)
            val sy = g.cy + g.arcRadius * sin(angle)
            if (sy < -g.iconRadius || sy > h + g.iconRadius) continue

            // Fade smoothly near top and bottom bounds of the arc stage.
            val edgeDist = min(sy, h - sy)
            val fade = ((edgeDist - 4f * density) / (28f * density)).coerceIn(0f, 1f)
            if (fade <= 0.01f) continue

            if (i == spotlightIdx && abs(delta) < 0.45f) {
                activeX = sx
                activeY = sy
                activeFade = fade
            } else {
                drawSlotNode(canvas, slots[i], sx, sy, g.iconRadius, isActive = false, fadeAlpha = fade)
            }
        }

        if (spotlightIdx in slots.indices && activeFade > 0.01f) {
            drawSlotNode(canvas, slots[spotlightIdx], activeX, activeY, g.iconRadius, isActive = true, fadeAlpha = activeFade)
        }

        // Fixed middle spotlight ring locked at 9 o'clock (ARC_LEFT) or 3 o'clock (ARC_RIGHT).
        drawSpotlightRing(canvas, g.spotlightX, g.cy, g.iconRadius * 1.16f)
    }

    private fun drawSlotNode(
        canvas: Canvas,
        slot: WheelSlot,
        cx: Float,
        cy: Float,
        baseRadius: Float,
        isActive: Boolean,
        fadeAlpha: Float
    ) {
        val scale = if (isActive) 1.16f else 1.0f
        val discR = baseRadius * scale

        discPaint.color = if (isActive) activeDiscColor else inactiveDiscColor
        if (fadeAlpha < 1f) {
            val origAlpha = Color.alpha(discPaint.color)
            discPaint.alpha = (origAlpha * fadeAlpha).roundToInt().coerceIn(0, 255)
        }
        canvas.drawCircle(cx, cy, discR, discPaint)

        val iconAlpha = ((if (isActive) 255 else 182) * fadeAlpha).roundToInt().coerceIn(0, 255)
        val bmp = slot.icon
        if (bmp != null && !bmp.isRecycled) {
            val insetR = discR * 0.78f
            val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
            val halfW: Float
            val halfH: Float
            if (aspect >= 1f) {
                halfW = insetR
                halfH = insetR / aspect
            } else {
                halfH = insetR
                halfW = insetR * aspect
            }
            drawRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            iconPaint.alpha = iconAlpha
            canvas.drawBitmap(bmp, null, drawRect, iconPaint)
        } else if (slot.isOffSlot) {
            drawOffVector(canvas, cx, cy, discR * 0.44f, iconAlpha)
        }

        if (isActive && wheelMode == WheelMode.FULL_CIRCLE) {
            drawSpotlightRing(canvas, cx, cy, discR)
        }
    }

    private fun drawSpotlightRing(canvas: Canvas, cx: Float, cy: Float, ringRadius: Float) {
        glowPaint.color = accentColor
        glowPaint.alpha = 72
        glowPaint.strokeWidth = 6.5f * density
        canvas.drawCircle(cx, cy, ringRadius + 1.5f * density, glowPaint)

        ringPaint.color = accentColor
        ringPaint.alpha = 255
        ringPaint.strokeWidth = 3.0f * density
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)
    }

    private fun drawOffVector(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Int) {
        offSymbolPaint.color = Color.WHITE
        offSymbolPaint.alpha = alpha
        offSymbolPaint.strokeWidth = 2.4f * density
        canvas.drawCircle(cx, cy, r, offSymbolPaint)
        val d = r * 0.707f
        canvas.drawLine(cx - d, cy - d, cx + d, cy + d, offSymbolPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (slots.isEmpty() || width <= 0 || height <= 0) return false
        return if (wheelMode == WheelMode.FULL_CIRCLE) {
            handleFullCircleTouch(event)
        } else {
            handleArcTouch(event)
        }
    }

    private fun handleFullCircleTouch(event: MotionEvent): Boolean {
        val g = computeCircleGeometry(width.toFloat(), height.toFloat())
        val dx = event.x - g.cx
        val dy = event.y - g.cy
        val dist = hypot(dx, dy)
        val innerHit = g.radius - g.iconRadius * 1.65f
        val outerHit = g.radius + g.iconRadius * 1.85f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dist < innerHit || dist > outerHit) {
                    return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                isTrackingTouch = true
                isDragging = true
                downX = event.x
                downY = event.y
                updateFullCircleHover(dx, dy)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTrackingTouch) return false
                if (dist >= innerHit * 0.55f) {
                    updateFullCircleHover(dx, dy)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isTrackingTouch) return false
                if (dist >= innerHit * 0.55f) {
                    updateFullCircleHover(dx, dy)
                }
                val committed = hoveredSlotIndex
                isTrackingTouch = false
                isDragging = false
                hoveredSlotIndex = -1
                if (committed in slots.indices) {
                    highlightedSlotIndex = committed
                    invalidate()
                    onSlotCommitted?.invoke(slots[committed])
                } else {
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isTrackingTouch = false
                isDragging = false
                hoveredSlotIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFullCircleHover(dx: Float, dy: Float) {
        val count = slots.size
        if (count <= 0) return
        val stepRad = (2.0 * PI / count).toFloat()
        val offIdx = offSlotIndex.coerceIn(0, count - 1)
        val touchAngle = atan2(dy, dx)
        var rawIdx = offIdx + ((touchAngle - (PI.toFloat() / 2f)) / stepRad).roundToInt()
        rawIdx = wrapSlotIndex(rawIdx, count)
        if (rawIdx != hoveredSlotIndex) {
            hoveredSlotIndex = rawIdx
            highlightedSlotIndex = rawIdx
            invalidate()
            onSlotHovered?.invoke(slots[rawIdx])
        }
    }

    private fun handleArcTouch(event: MotionEvent): Boolean {
        val g = computeArcGeometry(width.toFloat(), height.toFloat())
        val count = slots.size

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dx = event.x - g.cx
                val dy = event.y - g.cy
                val dist = hypot(dx, dy)
                val onCorrectSide = if (wheelMode == WheelMode.ARC_LEFT) dx < 0f else dx > 0f
                val band = g.iconRadius * 2.35f
                if (!onCorrectSide || dist < g.arcRadius - band || dist > g.arcRadius + band) {
                    return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                cancelArcAnimator()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                isTrackingTouch = true
                isDragging = false
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                lastMoveY = event.y
                hoveredSlotIndex = wrapSlotIndex(scrollIndex.roundToInt(), count)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTrackingTouch) return false
                velocityTracker?.addMovement(event)
                val totalDist = hypot(event.x - downX, event.y - downY)
                if (!isDragging && totalDist >= touchSlop) {
                    isDragging = true
                }
                if (isDragging) {
                    val prevAngle = atan2(lastTouchY - g.cy, lastTouchX - g.cx)
                    val curAngle = atan2(event.y - g.cy, event.x - g.cx)
                    var dAngle = curAngle - prevAngle
                    while (dAngle > PI.toFloat()) dAngle -= (2f * PI.toFloat())
                    while (dAngle < -PI.toFloat()) dAngle += (2f * PI.toFloat())

                    val indexDelta = if (wheelMode == WheelMode.ARC_LEFT) {
                        dAngle / g.slotAngleStep
                    } else {
                        -dAngle / g.slotAngleStep
                    }
                    scrollIndex = wrapFloatIndex(scrollIndex + indexDelta, count)
                    val nearest = wrapSlotIndex(scrollIndex.roundToInt(), count)
                    if (nearest != hoveredSlotIndex) {
                        hoveredSlotIndex = nearest
                        highlightedSlotIndex = nearest
                        onSlotHovered?.invoke(slots[nearest])
                    }
                    invalidate()
                }
                lastTouchX = event.x
                lastTouchY = event.y
                lastMoveY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isTrackingTouch) return false
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val yVel = velocityTracker?.yVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                val wasDragging = isDragging
                isTrackingTouch = false
                isDragging = false

                if (!wasDragging) {
                    val tappedIdx = findTappedArcSlot(g, event.x, event.y)
                    val curIdx = wrapSlotIndex(scrollIndex.roundToInt(), count)
                    if (tappedIdx >= 0) {
                        if (tappedIdx == curIdx) {
                            onSlotHovered?.invoke(slots[tappedIdx])
                        }
                        animateArcToSlot(tappedIdx, commitOnEnd = true, durationMs = 200L)
                    } else {
                        animateArcToSlot(curIdx, commitOnEnd = false, durationMs = 150L)
                    }
                } else {
                    val slotArcPx = g.arcRadius * g.slotAngleStep
                    // Dragging down (yVel > 0) decreases scrollIndex (brings upper items down).
                    val slotsPerSec = -(yVel / slotArcPx)
                    val projectedDelta = (slotsPerSec * 0.16f).coerceIn(-6f, 6f)
                    val targetUnwrapped = (scrollIndex + projectedDelta).roundToInt()
                    val duration = (150L + (abs(projectedDelta) * 55f).toLong()).coerceIn(150L, 420L)
                    animateArcToUnwrappedTarget(targetUnwrapped.toFloat(), commitOnEnd = true, durationMs = duration)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                isTrackingTouch = false
                isDragging = false
                val snapIdx = wrapSlotIndex(scrollIndex.roundToInt(), count)
                animateArcToSlot(snapIdx, commitOnEnd = false, durationMs = 160L)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findTappedArcSlot(g: ArcGeometry, tx: Float, ty: Float): Int {
        val count = slots.size
        val baseAngle = if (wheelMode == WheelMode.ARC_LEFT) PI.toFloat() else 0f
        val dirSign = if (wheelMode == WheelMode.ARC_LEFT) -1f else 1f
        val hitR = g.iconRadius * 1.48f
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until count) {
            val delta = wrappedDelta(i - scrollIndex, count)
            val offsetAngle = delta * g.slotAngleStep
            if (abs(offsetAngle) > (PI.toFloat() / 2f) + 0.15f) continue
            val angle = baseAngle + dirSign * offsetAngle
            val sx = g.cx + g.arcRadius * cos(angle)
            val sy = g.cy + g.arcRadius * sin(angle)
            val d = hypot(tx - sx, ty - sy)
            if (d <= hitR && d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun animateArcToSlot(targetIdx: Int, commitOnEnd: Boolean, durationMs: Long) {
        val count = slots.size
        if (count <= 0) return
        val delta = wrappedDelta(targetIdx - scrollIndex, count)
        animateArcToUnwrappedTarget(scrollIndex + delta, commitOnEnd, durationMs)
    }

    private fun animateArcToUnwrappedTarget(targetUnwrapped: Float, commitOnEnd: Boolean, durationMs: Long) {
        val count = slots.size
        if (count <= 0) return
        cancelArcAnimator()
        val start = scrollIndex
        if (abs(targetUnwrapped - start) < 0.005f) {
            scrollIndex = wrapFloatIndex(targetUnwrapped, count)
            val settledIdx = wrapSlotIndex(scrollIndex.roundToInt(), count)
            highlightedSlotIndex = settledIdx
            invalidate()
            if (commitOnEnd) {
                onSlotCommitted?.invoke(slots[settledIdx])
            }
            return
        }

        val anim = ValueAnimator.ofFloat(start, targetUnwrapped).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val cur = va.animatedValue as Float
                scrollIndex = wrapFloatIndex(cur, count)
                val nearest = wrapSlotIndex(scrollIndex.roundToInt(), count)
                if (nearest != hoveredSlotIndex) {
                    hoveredSlotIndex = nearest
                    highlightedSlotIndex = nearest
                    if (commitOnEnd) {
                        onSlotHovered?.invoke(slots[nearest])
                    }
                }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (arcAnimator === animation) {
                        arcAnimator = null
                    }
                    if (!cancelled) {
                        scrollIndex = wrapFloatIndex(targetUnwrapped, count)
                        val settledIdx = wrapSlotIndex(scrollIndex.roundToInt(), count)
                        highlightedSlotIndex = settledIdx
                        hoveredSlotIndex = -1
                        invalidate()
                        if (commitOnEnd) {
                            onSlotCommitted?.invoke(slots[settledIdx])
                        }
                    }
                }
            })
        }
        arcAnimator = anim
        anim.start()
    }

    private fun cancelArcAnimator() {
        arcAnimator?.cancel()
        arcAnimator = null
    }

    private fun wrapSlotIndex(idx: Int, count: Int): Int {
        if (count <= 0) return 0
        val mod = idx % count
        return if (mod < 0) mod + count else mod
    }

    private fun wrapFloatIndex(value: Float, count: Int): Float {
        if (count <= 0) return 0f
        var v = value % count.toFloat()
        if (v < 0f) v += count.toFloat()
        return v
    }

    private fun wrappedDelta(rawDelta: Float, count: Int): Float {
        if (count <= 0) return 0f
        var d = rawDelta
        val half = count / 2f
        while (d > half) d -= count
        while (d < -half) d += count
        return d
    }

    override fun onDetachedFromWindow() {
        cancelArcAnimator()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }
}
