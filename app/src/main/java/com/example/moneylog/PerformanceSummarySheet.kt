package com.example.moneylog

import android.app.Dialog
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import android.widget.SeekBar
import com.example.moneylog.R


class PerformanceSummarySheet : BottomSheetDialogFragment() {

    private val viewModel: TransactionViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState) as BottomSheetDialog
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as BottomSheetDialog
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return

        bottomSheet.setBackgroundResource(android.R.color.transparent)
        // Keep the sheet MATCH_PARENT so the map can expand to the top later
        bottomSheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_performance, container, false)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)



        val symbol = CurrencyHelper.getSymbol(requireContext())

        // FIX: Cast as NestedScrollView to match XML and prevent crashes
        val summaryContainer = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.summaryContainer)
        val tvDragHint = view.findViewById<TextView>(R.id.tvDragHint)
        val tvEmptyTimeline = view.findViewById<TextView>(R.id.tvEmptyTimeline) // <-- ADD THIS LINE

        // BIND MAP COMPONENTS
        val timelineMapView = view.findViewById<TimelineMapView>(R.id.timelineMapView)
        val zoomSlider = view.findViewById<Slider>(R.id.zoomSlider)
        val btnRecenter = view.findViewById<ImageButton>(R.id.btnRecenter)
        val mapControls = view.findViewById<LinearLayout>(R.id.mapControls)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = RenderEffect.createBlurEffect(45f, 45f, Shader.TileMode.CLAMP)
            mapControls.setRenderEffect(blur)
        }

        // BIND NEW CLOSE BUTTON
        val btnCloseMap = view.findViewById<ImageButton>(R.id.btnCloseMap)
        btnCloseMap.setOnClickListener { dismiss() }

        val tvDateRangeLabel = view.findViewById<TextView>(R.id.tvDateRangeLabel)
        val dateSlider = view.findViewById<RangeSlider>(R.id.dateSlider)

        val tvPeriodNet = view.findViewById<TextView>(R.id.tvPeriodNet)
        val tvPeriodCredits = view.findViewById<TextView>(R.id.tvPeriodCredits)
        val tvPeriodDebits = view.findViewById<TextView>(R.id.tvPeriodDebits)
        val progressCashFlow = view.findViewById<LinearProgressIndicator>(R.id.progressCashFlow)

        // BIND AND ENLARGE TEXT SIZES FOR BOXES
        val tvTodayIncome = view.findViewById<TextView>(R.id.tvTodayIncome).apply { textSize = 15f }
        val tvTodayExpense = view.findViewById<TextView>(R.id.tvTodayExpense).apply { textSize = 15f }
        val tvTodayNet = view.findViewById<TextView>(R.id.tvTodayNet).apply { textSize = 18f }

        val tvWeekIncome = view.findViewById<TextView>(R.id.tvWeekIncome).apply { textSize = 15f }
        val tvWeekExpense = view.findViewById<TextView>(R.id.tvWeekExpense).apply { textSize = 15f }
        val tvWeekNet = view.findViewById<TextView>(R.id.tvWeekNet).apply { textSize = 18f }

        val tvMonthIncome = view.findViewById<TextView>(R.id.tvMonthIncome).apply { textSize = 15f }
        val tvMonthExpense = view.findViewById<TextView>(R.id.tvMonthExpense).apply { textSize = 15f }
        val tvMonthNet = view.findViewById<TextView>(R.id.tvMonthNet).apply { textSize = 18f }

        // Initialize Map Controls
        mapControls.alpha = 0f

        btnRecenter.setOnClickListener {
            timelineMapView.recenterToLast()
            zoomSlider.value = 0.8f
        }

        zoomSlider.addOnChangeListener { _, value, _ ->
            timelineMapView.setZoom(value)
        }

        summaryContainer.post {
            val bottomSheet = (view.parent as View)
            val behavior = BottomSheetBehavior.from(bottomSheet)

            // FIX: Dynamically measure the exact height of your summary content
            // This guarantees the sheet stops exactly under the "Drag up for Timeline" text on ANY device
            behavior.peekHeight = summaryContainer.height
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED

            behavior.isHideable = true
            behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    timelineMapView.isEnabled = newState == BottomSheetBehavior.STATE_EXPANDED

                    if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                        // 1. Prevent the sheet from being hidden (swiped off screen)
                        behavior.isHideable = false
                        // 2. DISABLE ALL DRAGGING so it doesn't collapse back to the summary
                        behavior.isDraggable = false
                        btnCloseMap.visibility = View.VISIBLE
                    } else {
                        // Re-enable dragging and hideability for the summary view
                        behavior.isHideable = true
                        behavior.isDraggable = true
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    // Dashboard Fade & Slide Animation
                    summaryContainer.alpha = 1f - (slideOffset * 1.2f).coerceAtMost(1f)
                    tvDragHint.alpha = 1f - slideOffset

                    // Slide summary off the top only when moving UP
                    if (slideOffset > 0) {
                        summaryContainer.translationY = -(summaryContainer.height * slideOffset)
                    } else {
                        summaryContainer.translationY = 0f
                    }

                    // Fade in 'X' button and Map controls
                    btnCloseMap.alpha = if (slideOffset > 0.8f) (slideOffset - 0.8f) * 5f else 0f
                    btnCloseMap.visibility = if (slideOffset > 0.8f) View.VISIBLE else View.GONE
                    mapControls.alpha = if (slideOffset > 0.9f) (slideOffset - 0.9f) * 10f else 0f
                }
            })
        }

        // Keep rest of observation logic intact
        var isSliderInitialized = false
        val sliderDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        viewModel.setDateRange(0L, Long.MAX_VALUE)
        tvDateRangeLabel.text = "ALL TIME"

        viewModel.performanceStats.observe(viewLifecycleOwner) { stats ->
            val days = stats.availableTimestamps

            if (days.size > 1 && !isSliderInitialized) {
                dateSlider.visibility = View.VISIBLE
                dateSlider.valueFrom = 0f
                dateSlider.valueTo = (days.size - 1).toFloat()
                dateSlider.stepSize = 1f

                val startIdx = days.indexOfFirst { it >= (viewModel.dateRange.value?.first ?: 0L) }.coerceAtLeast(0)
                val endIdx = days.indexOfLast { it <= (viewModel.dateRange.value?.second ?: Long.MAX_VALUE) }.coerceAtMost(days.size - 1)
                dateSlider.values = listOf(startIdx.toFloat(), endIdx.toFloat())

                dateSlider.addOnChangeListener { slider, _, _ ->
                    val sIdx = slider.values[0].toInt()
                    val eIdx = slider.values[1].toInt()
                    tvDateRangeLabel.text = "${sliderDateFormat.format(Date(days[sIdx]))}  —  ${sliderDateFormat.format(Date(days[eIdx]))}".uppercase()
                }

                dateSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: RangeSlider) {}
                    override fun onStopTrackingTouch(slider: RangeSlider) {
                        val startTs = days[slider.values[0].toInt()]
                        val endTs = days[slider.values[1].toInt()]
                        viewModel.setDateRange(startTs, endTs)
                    }
                })
                isSliderInitialized = true
            } else if (days.size <= 1) {
                dateSlider.visibility = View.GONE
                tvDateRangeLabel.text = "ALL TIME"
            }

            if (days.isNotEmpty() && dateSlider.visibility == View.VISIBLE) {
                val sIdx = dateSlider.values[0].toInt()
                val eIdx = dateSlider.values[1].toInt()
                tvDateRangeLabel.text = "${sliderDateFormat.format(Date(days[sIdx]))}  —  ${sliderDateFormat.format(Date(days[eIdx]))}".uppercase()
            }

            tvTodayNet.setTextColor(requireContext().getColor(if (stats.todayNet > 0L) R.color.income_green else if (stats.todayNet < 0L) R.color.expense_red else R.color.text_primary))
            tvWeekNet.setTextColor(requireContext().getColor(if (stats.weekNet > 0L) R.color.income_green else if (stats.weekNet < 0L) R.color.expense_red else R.color.text_primary))
            tvMonthNet.setTextColor(requireContext().getColor(if (stats.monthNet > 0L) R.color.income_green else if (stats.monthNet < 0L) R.color.expense_red else R.color.text_primary))

            animateText(tvPeriodNet, stats.periodNet) { v -> "Net: $symbol ${fmt(v)}" }
            animateText(tvPeriodCredits, stats.periodCredits) { v -> "+ $symbol ${fmt(abs(v))}" }
            animateText(tvPeriodDebits, stats.periodDebits) { v -> "- $symbol ${fmt(abs(v))}" }

            // FIX: Accurate Cash Flow Progress Bar using Percentages
            val totalVolume = stats.periodCredits + abs(stats.periodDebits)
            if (totalVolume == 0L) {
                // If 0, make it a neutral empty bar so it doesn't look like a huge expense
                progressCashFlow.trackColor = requireContext().getColor(R.color.text_tertiary)
                progressCashFlow.setIndicatorColor(requireContext().getColor(R.color.text_tertiary))
                progressCashFlow.max = 100
                progressCashFlow.setProgressCompat(0, true)
            } else {
                // Reset colors to normal (Green for Income, Red for Expense track)
                progressCashFlow.trackColor = requireContext().getColor(R.color.expense_red)
                progressCashFlow.setIndicatorColor(requireContext().getColor(R.color.income_green))

                // Use percentages to completely avoid Int.MAX_VALUE crashes
                progressCashFlow.max = 100
                val incomePercentage = ((stats.periodCredits.toDouble() / totalVolume.toDouble()) * 100).toInt()
                progressCashFlow.setProgressCompat(incomePercentage, true)
            }

            animateText(tvTodayIncome, stats.todayCredits) { v -> "+ $symbol ${fmt(abs(v))}" }
            animateText(tvTodayExpense, stats.todayDebits) { v -> "- $symbol ${fmt(abs(v))}" }
            animateText(tvTodayNet, stats.todayNet) { v ->
                val sign = if (v > 0) "+ " else if (v < 0) "- " else ""
                "$sign$symbol ${fmt(abs(v))}"
            }

            animateText(tvWeekIncome, stats.weekCredits) { v -> "+ $symbol ${fmt(abs(v))}" }
            animateText(tvWeekExpense, stats.weekDebits) { v -> "- $symbol ${fmt(abs(v))}" }
            animateText(tvWeekNet, stats.weekNet) { v ->
                val sign = if (v > 0) "+ " else if (v < 0) "- " else ""
                "$sign$symbol ${fmt(abs(v))}"
            }

            animateText(tvMonthIncome, stats.monthCredits) { v -> "+ $symbol ${fmt(abs(v))}" }
            animateText(tvMonthExpense, stats.monthDebits) { v -> "- $symbol ${fmt(abs(v))}" }
            animateText(tvMonthNet, stats.monthNet) { v ->
                val sign = if (v > 0) "+ " else if (v < 0) "- " else ""
                "$sign$symbol ${fmt(abs(v))}"
            }

            // --- EMPTY STATE LOGIC ---
            if (stats.timeline.isEmpty()) {
                tvEmptyTimeline.visibility = View.VISIBLE
                timelineMapView.visibility = View.INVISIBLE
                mapControls.visibility = View.GONE
                tvDragHint.visibility = View.GONE
            } else {
                tvEmptyTimeline.visibility = View.GONE
                timelineMapView.visibility = View.VISIBLE
                mapControls.visibility = View.VISIBLE
                tvDragHint.visibility = View.VISIBLE
            }

            // --- EMPTY STATE LOGIC ---
            if (stats.timeline.isEmpty()) {
                tvEmptyTimeline.visibility = View.VISIBLE
                timelineMapView.visibility = View.INVISIBLE
                mapControls.visibility = View.GONE
                tvDragHint.visibility = View.GONE
            } else {
                tvEmptyTimeline.visibility = View.GONE
                timelineMapView.visibility = View.VISIBLE
                mapControls.visibility = View.VISIBLE
                tvDragHint.visibility = View.VISIBLE
            }

            // PUSH DATA TO MAP
            // FIX: Delay heavy map rendering slightly so the BottomSheet can slide up instantly without dropping frames
            view?.postDelayed({
                if (isAdded) timelineMapView.submitList(stats.timeline)
            }, 200)
        }
    }

    // Hoisted reusable formatter (Add this as a class property near the top/bottom)
    private val numberFormatter = java.text.NumberFormat.getInstance(java.util.Locale.getDefault()).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }

    private fun fmt(cents: Long): String {
        val d = cents / 100.0
        return numberFormatter.format(d)
    }

    private fun animateText(tv: TextView, targetCents: Long, format: (Long) -> String) {
        val startCents = (tv.tag as? Long) ?: 0L
        if (startCents == targetCents && tv.tag != null) return
        tv.tag = targetCents

        val animator = android.animation.ValueAnimator.ofFloat(startCents.toFloat(), targetCents.toFloat())
        animator.duration = 800
        animator.interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        animator.addUpdateListener { anim ->
            tv.text = format((anim.animatedValue as Float).toLong())
        }
        animator.start()
    }
}