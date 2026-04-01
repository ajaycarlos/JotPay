package com.example.moneylog

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class TimelineMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var items: List<TimelineItem> = emptyList()
    private val pointMap = mutableListOf<PointF>()
    private val transformMatrix = Matrix()
    private var scaleFactor = 1f

    // Premium Styling
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#70FFFFFF") // Subtle faint white
        strokeWidth = 3f // Thinner for a more technical feel
    }

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D3D3D3") // Light Grey
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val incomePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CAF50") }
    private val expensePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F44336") }
    private val textTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 38f; typeface = Typeface.DEFAULT_BOLD }
    private val textAmountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; typeface = Typeface.DEFAULT_BOLD }
    private val textBalancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B0B0B0"); textSize = 30f }

    private val dateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val symbol = CurrencyHelper.getSymbol(context)

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(0.2f, 3.0f)
            transformMatrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            transformMatrix.postTranslate(-distanceX, -distanceY)
            invalidate()
            return true
        }
    })
    private lateinit var timelinePath: Path

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        // SMART INTERCEPT: Allow downward swipe to close sheet, block horizontal/up for panning
        if (event.action == MotionEvent.ACTION_MOVE) {
            parent.requestDisallowInterceptTouchEvent(true)
        }

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    fun submitList(newItems: List<TimelineItem>) {
        items = newItems.reversed()
        if (height > 0) calculateCoordinates()
        invalidate()
    }

    private fun calculateCoordinates() {
        pointMap.clear()
        if (items.isEmpty()) return

        // 1. Sharp Vertical Frequency: Reducing this makes horizontal swings feel much steeper
        val spacingY = 250f // Reduced from 450f
        var currentY = 0f

        val balances = items.map { it.runningBalance }
        val minBalance = balances.minOrNull()?.toFloat() ?: 0f
        val maxBalance = balances.maxOrNull()?.toFloat() ?: 100f
        val rangeBalance = if (maxBalance - minBalance == 0f) 500f else (maxBalance - minBalance)

        // 2. Dramatic Horizontal Swing: Use more of the screen width to maximize the "winding" effect
        val leftPadding = width * 0.10f
        val rightPadding = width * 0.85f // Increased from 0.45f
        val availableWidth = rightPadding - leftPadding

        for (item in items) {
            val normalizedBalance = (item.runningBalance.toFloat() - minBalance) / rangeBalance
            val currentX = leftPadding + (normalizedBalance * availableWidth)
            pointMap.add(PointF(currentX, currentY))
            currentY += spacingY
        }
    }

    fun recenterToLast() {
        if (pointMap.isEmpty()) return

        val lastPoint = pointMap.last() // The most recent log

        transformMatrix.reset()
        scaleFactor = 0.8f

        // Center the view on the last point
        transformMatrix.postTranslate(width / 2f - lastPoint.x, height / 2f - lastPoint.y)
        transformMatrix.postScale(scaleFactor, scaleFactor, width / 2f, height / 2f)
        invalidate()
    }

    fun setZoom(level: Float) {
        val scaleChange = level / scaleFactor
        scaleFactor = level
        transformMatrix.postScale(scaleChange, scaleChange, width / 2f, height / 2f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw Panning Blueprint Grid (Locked to World)
        val values = FloatArray(9)
        transformMatrix.getValues(values)
        val dx = values[Matrix.MTRANS_X]
        val dy = values[Matrix.MTRANS_Y]
        val currentScale = values[Matrix.MSCALE_X]

// Grid size scales with zoom to maintain perspective
        val gridSize = 300f * currentScale
        val offsetX = (dx % gridSize + gridSize) % gridSize
        val offsetY = (dy % gridSize + gridSize) % gridSize

        var curX = offsetX
        while (curX < width) {
            canvas.drawLine(curX, 0f, curX, height.toFloat(), gridPaint)
            curX += gridSize
        }
        var curY = offsetY
        while (curY < height) {
            canvas.drawLine(0f, curY, width.toFloat(), curY, gridPaint)
            curY += gridSize
        }

        canvas.save()
        canvas.concat(transformMatrix)
        if (pointMap.isEmpty()) { canvas.restore(); return }

        // FIX: Reuse a single Path object to prevent GC thrashing at 60fps
        if (!::timelinePath.isInitialized) timelinePath = Path()
        timelinePath.reset()
        timelinePath.moveTo(pointMap.first().x, pointMap.first().y)

        for (i in 0 until pointMap.size - 1) {
            val p1 = pointMap[i]
            val p2 = pointMap[i+1]

            val tension = (p2.y - p1.y) * 0.8f
            timelinePath.cubicTo(p1.x, p1.y + tension, p2.x, p2.y - tension, p2.x, p2.y)
        }
        canvas.drawPath(timelinePath, pathPaint)

        // 3. Draw Waypoints & Multi-Layered Text
        for (i in items.indices) {
            val item = items[i]
            val point = pointMap[i]
            val paint = if (item.amount > 0) incomePaint else expensePaint

            // Solid Circle Node
            canvas.drawCircle(point.x, point.y, 22f, paint)

            val labelX = point.x + 48f

            // Layer 1: Description (Bold White)
            canvas.drawText(item.description, labelX, point.y - 15f, textTitlePaint)

            // Layer 2: Amount (Color-Matched)
            textAmountPaint.color = paint.color
            val amtStr = if (item.amount > 0) "+ $symbol ${fmt(item.amount)}" else "- $symbol ${fmt(abs(item.amount))}"

            canvas.drawText(amtStr, labelX, point.y + 35f, textAmountPaint)

            // Layer 3: Balance & Date (Secondary Grey)
            val dateStr = dateFormatter.format(Date(item.timestamp))
            val metaStr = "Bal: $symbol ${fmt(item.runningBalance)}  •  $dateStr"
            canvas.drawText(metaStr, labelX, point.y + 80f, textBalancePaint)
        }
        canvas.restore()
    }

    // Hoisted reusable formatter
    private val numberFormatter = java.text.NumberFormat.getInstance(java.util.Locale.getDefault()).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }

    private fun fmt(cents: Long): String {
        val d = cents / 100.0
        return numberFormatter.format(d)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (items.isNotEmpty()) calculateCoordinates()
    }
}