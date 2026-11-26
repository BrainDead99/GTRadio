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
import kotlin.math.max
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
                cancelArcAnimator(notifyEnd = true)
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

    var activeDiscColor: Int = Color.parseColor("#E2000000")
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onSlotHovered: ((WheelSlot) -> Unit)? = null
    var onSlotCommitted: ((WheelSlot) -> Unit)? = null
    var onInteractionStart: (() -> Unit)? = null
    var onInteractionEnd: (() -> Unit)? = null

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
    private var isInteractionActive = false
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

    private fun notifyInteractionStart() {
        if (!isInteractionActive) {
            isInteractionActive = true
            onInteractionStart?.invoke()
        }
    }

    private fun notifyInteractionEnd() {
        if (isInteractionActive) {
            isInteractionActive = false
            onInteractionEnd?.invoke()
        }
    }

    fun setActiveMediaId(mediaId: String?, isPlaying: Boolean) {
        activeMediaId = mediaId
        isRadioPlaying = isPlaying
        if (!isTrackingTouch && !isInteractionActive && arcAnimator == null) {
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
            cancelArcAnimator(notifyEnd = false)
            scrollIndex = targetIndex.toFloat()
        } else {
            animateArcToSlot(targetIndex, commitOnEnd = false, durationMs = 220L)
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
            val innerR = (g.radius - g.iconRadius * 1.18f - 4f * density).coerceAtLeast(36f * density)
            val halfW = innerR * 0.84f
            val halfH = innerR * 0.92f
            stageRect.set(g.cx - halfW, g.cy - halfH, g.cx + halfW, g.cy + halfH)
            return stageRect
        }

        val g = computeArcGeometry(w, h)
        val stageHalfH = min(h * 0.36f, 154f * density)
        val sinMax = (stageHalfH / g.arcRadius).coerceIn(0f, 0.86f)
        val arcBulge = g.arcRadius * (1f - sqrt(1f - sinMax * sinMax))
        val edgePad = 14f * density
        val safeGap = g.iconRadius * 1.24f + arcBulge + 8f * density
        val minPocketW = min(172f * density, w - edgePad * 2f)

        val top = max(g.cy - stageHalfH, 54f * density)
        val bottom = (g.cy + stageHalfH).coerceAtLeast(top + 80f * density)

        if (wheelMode == WheelMode.ARC_LEFT) {
            val maxLeftForPocket = (w - edgePad - minPocketW).coerceAtLeast(g.spotlightX + g.iconRadius * 1.20f)
            val left = (g.spotlightX + safeGap).coerceAtMost(maxLeftForPocket)
            val right = (w - edgePad).coerceAtLeast(left + 64f * density)
            stageRect.set(left, top, right, bottom)
        } else {
            val minRightForPocket = (edgePad + minPocketW).coerceAtMost(g.spotlightX - g.iconRadius * 1.20f)
            val right = (g.spotlightX - safeGap).coerceAtLeast(minRightForPocket)
            val left = edgePad.coerceAtMost(right - 64f * density)
            stageRect.set(left, top, right, bottom)
        }
        return stageRect
    }

    private fun computeCircleGeometry(w: Float, h: Float): CircleGeometry {
        val count = slots.size.coerceAtLeast(1)
        val outerPad = 4f * density
        val maxHalf = (min(w, h) / 2f - outerPad).coerceAtLeast(48f * density)
        val sinHalfStep = sin(PI / count).toFloat().coerceAtLeast(0.05f)
        val maxIconByChord = (maxHalf * 0.90f) * sinHalfStep * 0.86f
        val iconRadius = min(22.5f * density, maxIconByChord).coerceAtLeast(12f * density)
        val ringRadius = (maxHalf - iconRadius * 1.14f).coerceAtLeast(40f * density)
        return CircleGeometry(
            cx = w / 2f,
            cy = h / 2f,
            radius = ringRadius,
            iconRadius = iconRadius
        )
    }

    private fun computeArcGeometry(w: Float, h: Float): ArcGeometry {
        val widthDp = w / density
        val baseIconDp = if (widthDp < 340f) {
            26f + ((widthDp - 280f) / 60f).coerceIn(0f, 1f) * 7f
        } else {
            33f
        }
        val iconRadius = baseIconDp * density
        val edgeMargin = 14f * density
        val spotlightOffset = edgeMargin + iconRadius * 1.18f
        val arcRadius = (h * 0.56f).coerceIn(195f * density, 265f * density)
        val slotArcStep = iconRadius * 2.26f
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
                drawSlotNode(
                    canvas = canvas,
                    slot = slots[i],
                    cx = sx,
                    cy = sy,
                    baseRadius = g.iconRadius,
                    focus = 0f,
                    fadeAlpha = 1f,
                    drawCircleRing = false
                )
            }
        }

        if (activeIdx in slots.indices) {
            drawSlotNode(
                canvas = canvas,
                slot = slots[activeIdx],
                cx = activeCenterX,
                cy = activeCenterY,
                baseRadius = g.iconRadius,
                focus = 1f,
                fadeAlpha = 1f,
                drawCircleRing = true
            )
        }
    }

    private fun drawArcCarousel(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val g = computeArcGeometry(w, h)
        val count = slots.size
        val baseAngle = if (wheelMode == WheelMode.ARC_LEFT) PI.toFloat() else 0f
        val dirSign = if (wheelMode == WheelMode.ARC_LEFT) -1f else 1f
        val halfAngleLimit = (PI.toFloat() / 2f) + 0.08f
        val angularFadeStart = 1.16f
        val spotlightIdx = wrapSlotIndex(scrollIndex.roundToInt(), count)

        var activeX = g.spotlightX
        var activeY = g.cy
        var activeFade = 0f
        var activeFocus = 0f
        var activeDeltaAbs = 1f
        var hasActive = false

        for (i in 0 until count) {
            val delta = wrappedDelta(i - scrollIndex, count)
            val offsetAngle = delta * g.slotAngleStep
            val absOffset = abs(offsetAngle)
            if (absOffset > halfAngleLimit) continue

            val angle = baseAngle + dirSign * offsetAngle
            val sx = g.cx + g.arcRadius * cos(angle)
            val sy = g.cy + g.arcRadius * sin(angle)

            // Smooth vertical + angular alpha fade so discs dissolve cleanly instead of hard clipping.
            val edgeDist = min(sy, h - sy)
            val verticalFade = ((edgeDist - g.iconRadius * 0.98f) / (26f * density)).coerceIn(0f, 1f)
            val angularFade = if (absOffset <= angularFadeStart) {
                1f
            } else {
                ((halfAngleLimit - absOffset) / (halfAngleLimit - angularFadeStart)).coerceIn(0f, 1f)
            }
            val fade = min(verticalFade, angularFade)
            if (fade <= 0.01f) continue

            val focus = (1f - abs(delta)).coerceIn(0f, 1f)
            if (i == spotlightIdx && abs(delta) <= 0.5f) {
                activeX = sx
                activeY = sy
                activeFade = fade
                activeFocus = focus
                activeDeltaAbs = abs(delta)
                hasActive = true
            } else {
                drawSlotNode(
                    canvas = canvas,
                    slot = slots[i],
                    cx = sx,
                    cy = sy,
                    baseRadius = g.iconRadius,
                    focus = focus,
                    fadeAlpha = fade,
                    drawCircleRing = false
                )
            }
        }

        if (hasActive && spotlightIdx in slots.indices) {
            drawSlotNode(
                canvas = canvas,
                slot = slots[spotlightIdx],
                cx = activeX,
                cy = activeY,
                baseRadius = g.iconRadius,
                focus = activeFocus,
                fadeAlpha = activeFade,
                drawCircleRing = false
            )
        }

        // Smooth radial lock-in: centered at 9/3 o'clock mid-scroll and tracks nearest slot as it settles.
        val lockT = if (hasActive) (1f - activeDeltaAbs / 0.5f).coerceIn(0f, 1f) else 0f
        val ringCx = if (hasActive) g.spotlightX + (activeX - g.spotlightX) * lockT else g.spotlightX
        val ringCy = if (hasActive) g.cy + (activeY - g.cy) * lockT else g.cy
        val ringScale = 1.08f + (1.18f - 1.08f) * lockT
        val ringStrokeDp = 2.2f + (3.2f - 2.2f) * lockT
        val ringAlphaFactor = 0.55f + 0.45f * lockT
        drawSpotlightRing(
            canvas = canvas,
            cx = ringCx,
            cy = ringCy,
            ringRadius = g.iconRadius * ringScale,
            strokeWidthDp = ringStrokeDp,
            alphaFactor = ringAlphaFactor
        )
    }

    private fun drawSlotNode(
        canvas: Canvas,
        slot: WheelSlot,
        cx: Float,
        cy: Float,
        baseRadius: Float,
        focus: Float,
        fadeAlpha: Float,
        drawCircleRing: Boolean
    ) {
        val maxScale = if (wheelMode == WheelMode.FULL_CIRCLE) 1.16f else 1.18f
        val scale = 1.0f + (maxScale - 1.0f) * focus
        val discR = baseRadius * scale

        discPaint.color = blendColor(inactiveDiscColor, activeDiscColor, focus)
        val origAlpha = Color.alpha(discPaint.color)
        discPaint.alpha = (origAlpha * fadeAlpha).roundToInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, discR, discPaint)

        val iconAlpha = ((175f + (255f - 175f) * focus) * fadeAlpha).roundToInt().coerceIn(0, 255)
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

        if (drawCircleRing) {
            drawSpotlightRing(canvas, cx, cy, discR, strokeWidthDp = 3.2f, alphaFactor = 1f)
        }
    }

    private fun blendColor(fromColor: Int, toColor: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val a = (Color.alpha(fromColor) + (Color.alpha(toColor) - Color.alpha(fromColor)) * clamped).roundToInt()
        val r = (Color.red(fromColor) + (Color.red(toColor) - Color.red(fromColor)) * clamped).roundToInt()
        val g = (Color.green(fromColor) + (Color.green(toColor) - Color.green(fromColor)) * clamped).roundToInt()
        val b = (Color.blue(fromColor) + (Color.blue(toColor) - Color.blue(fromColor)) * clamped).roundToInt()
        return Color.argb(a.coerceIn(0, 255), r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun drawSpotlightRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        ringRadius: Float,
        strokeWidthDp: Float = 3.2f,
        alphaFactor: Float = 1f
    ) {
        val factor = alphaFactor.coerceIn(0f, 1f)
        glowPaint.color = accentColor
        glowPaint.alpha = (80f * factor).roundToInt().coerceIn(0, 255)
        glowPaint.strokeWidth = (strokeWidthDp + 3.5f) * density
        canvas.drawCircle(cx, cy, ringRadius + 1.5f * density, glowPaint)

        ringPaint.color = accentColor
        ringPaint.alpha = (255f * factor).roundToInt().coerceIn(0, 255)
        ringPaint.strokeWidth = strokeWidthDp * density
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
                notifyInteractionStart()
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
                notifyInteractionEnd()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isTrackingTouch = false
                isDragging = false
                hoveredSlotIndex = -1
                invalidate()
                notifyInteractionEnd()
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
                val band = g.iconRadius * 2.15f
                if (!onCorrectSide || dist < g.arcRadius - band || dist > g.arcRadius + band) {
                    return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                cancelArcAnimator(notifyEnd = false)
                notifyInteractionStart()
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
                    val dy = event.y - lastTouchY
                    // 1:1 vertical thumb tracking: dragging down (dy > 0) rotates arc icons down.
                    val deltaAngle = if (wheelMode == WheelMode.ARC_LEFT) {
                        dy / g.arcRadius
                    } else {
                        -(dy / g.arcRadius)
                    }
                    val indexDelta = if (wheelMode == WheelMode.ARC_LEFT) {
                        -deltaAngle / g.slotAngleStep
                    } else {
                        deltaAngle / g.slotAngleStep
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
                        animateArcToSlot(tappedIdx, commitOnEnd = true, durationMs = 220L)
                    } else {
                        animateArcToSlot(curIdx, commitOnEnd = false, durationMs = 220L)
                    }
                } else {
                    val slotArcPx = g.arcRadius * g.slotAngleStep
                    // Dragging down (yVel > 0) decreases scrollIndex (brings upper items down).
                    val slotsPerSec = -(yVel / slotArcPx)
                    val projectedDelta = if (abs(slotsPerSec) < 0.45f) {
                        0f
                    } else {
                        (slotsPerSec * 0.16f).coerceIn(-6f, 6f)
                    }
                    val targetUnwrapped = (scrollIndex + projectedDelta).roundToInt()
                    val duration = if (abs(projectedDelta) < 0.5f) {
                        220L
                    } else {
                        (220L + (abs(projectedDelta) * 35f).toLong()).coerceIn(220L, 420L)
                    }
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
                animateArcToSlot(snapIdx, commitOnEnd = false, durationMs = 220L)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findTappedArcSlot(g: ArcGeometry, tx: Float, ty: Float): Int {
        val count = slots.size
        val baseAngle = if (wheelMode == WheelMode.ARC_LEFT) PI.toFloat() else 0f
        val dirSign = if (wheelMode == WheelMode.ARC_LEFT) -1f else 1f
        val hitR = g.iconRadius * 1.35f
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until count) {
            val delta = wrappedDelta(i - scrollIndex, count)
            val offsetAngle = delta * g.slotAngleStep
            if (abs(offsetAngle) > (PI.toFloat() / 2f) + 0.08f) continue
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
        cancelArcAnimator(notifyEnd = false)
        val start = scrollIndex
        if (abs(targetUnwrapped - start) < 0.005f) {
            scrollIndex = wrapFloatIndex(targetUnwrapped, count)
            val settledIdx = wrapSlotIndex(scrollIndex.roundToInt(), count)
            highlightedSlotIndex = settledIdx
            hoveredSlotIndex = -1
            invalidate()
            if (commitOnEnd) {
                onSlotCommitted?.invoke(slots[settledIdx])
            }
            notifyInteractionEnd()
            return
        }

        val anim = ValueAnimator.ofFloat(start, targetUnwrapped).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.5f)
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
                        notifyInteractionEnd()
                    }
                }
            })
        }
        arcAnimator = anim
        anim.start()
    }

    private fun cancelArcAnimator(notifyEnd: Boolean = false) {
        arcAnimator?.cancel()
        arcAnimator = null
        if (notifyEnd) {
            notifyInteractionEnd()
        }
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
        cancelArcAnimator(notifyEnd = true)
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }
}
