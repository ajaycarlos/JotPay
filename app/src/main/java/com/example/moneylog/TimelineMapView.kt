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
        color = Color.parseColor("#12FFFFFF") // Subtle grid
        strokeWidth = 2f
    }

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF") // Brighter path
        style = Paint.Style.STROKE
        strokeWidth = 10f
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

        val spacingX = 700f // Wider spacing for a better "winding" feel
        var currentX = 0f

        val balances = items.map { it.runningBalance }
        val minBalance = balances.minOrNull()?.toFloat() ?: 0f
        val maxBalance = balances.maxOrNull()?.toFloat() ?: 100f
        val rangeY = if (maxBalance - minBalance == 0f) 500f else (maxBalance - minBalance)

        // Use more screen height for the curve (15% to 85%)
        val topPadding = height * 0.15f
        val bottomPadding = height * 0.85f
        val availableHeight = bottomPadding - topPadding

        for (item in items) {
            val normalizedY = (item.runningBalance.toFloat() - minBalance) / rangeY
            val currentY = bottomPadding - (normalizedY * availableHeight)
            pointMap.add(PointF(currentX, currentY))
            currentX += spacingX
        }
        recenterToMiddle()
    }

    fun recenterToMiddle() {
        if (pointMap.isEmpty()) return
        val mapCenterX = (pointMap.first().x + pointMap.last().x) / 2f
        val mapCenterY = pointMap.map { it.y }.average().toFloat()

        transformMatrix.reset()
        scaleFactor = 0.7f // Default zoom level improved
        transformMatrix.postTranslate(width / 2f - mapCenterX, height / 2f - mapCenterY)
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

        // 1. Draw Global Grid (Matches Visualization)
        val gridSize = 120f
        for (x in 0..(width / gridSize).toInt() + 10) canvas.drawLine(x * gridSize, 0f, x * gridSize, height.toFloat(), gridPaint)
        for (y in 0..(height / gridSize).toInt() + 10) canvas.drawLine(0f, y * gridSize, width.toFloat(), y * gridSize, gridPaint)

        canvas.save()
        canvas.concat(transformMatrix)
        if (pointMap.isEmpty()) { canvas.restore(); return }

        // 2. Draw the Winding Road (High Tension Curves)
        val path = Path().apply { moveTo(pointMap.first().x, pointMap.first().y) }
        for (i in 0 until pointMap.size - 1) {
            val p1 = pointMap[i]
            val p2 = pointMap[i+1]
            val tension = (p2.x - p1.x) / 2
            path.cubicTo(p1.x + tension, p1.y, p2.x - tension, p2.y, p2.x, p2.y)
        }
        canvas.drawPath(path, pathPaint)

        // 3. Draw Nodes & Text Labels
        for (i in items.indices) {
            val item = items[i]
            val point = pointMap[i]
            val paint = if (item.amount > 0) incomePaint else expensePaint

            canvas.drawCircle(point.x, point.y, 22f, paint)

            val labelX = point.x + 40f
            canvas.drawText(item.description, labelX, point.y - 10f, textTitlePaint)

            textAmountPaint.color = paint.color
            val amtStr = if (item.amount > 0) "+ $symbol ${fmt(item.amount)}" else "- $symbol ${fmt(abs(item.amount))}"
            canvas.drawText(amtStr, labelX, point.y + 40f, textAmountPaint)
            canvas.drawText("Bal: $symbol ${fmt(item.runningBalance)}", labelX, point.y + 85f, textBalancePaint)
        }
        canvas.restore()
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else String.format("%.2f", d)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (items.isNotEmpty()) calculateCoordinates()
    }
}