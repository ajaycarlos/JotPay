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


class PerformanceSummarySheet : BottomSheetDialogFragment() {

    private val viewModel: TransactionViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState) as BottomSheetDialog
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as BottomSheetDialog
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)

        // FIX: Apply background and stable height rules BEFORE the animation starts
        bottomSheet.setBackgroundResource(android.R.color.transparent)
        bottomSheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT

        // FIX: Set a stable initial peak height (e.g., 60% of screen) to prevent the "snap"
        val stablePeek = (resources.displayMetrics.heightPixels * 0.60).toInt()
        behavior.peekHeight = stablePeek
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
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
        val tvPeriodMargin = view.findViewById<TextView>(R.id.tvPeriodMargin)
        val tvPresetWeek = view.findViewById<TextView>(R.id.tvPresetWeek)
        val tvPresetMonth = view.findViewById<TextView>(R.id.tvPresetMonth)
        val tvPresetAll = view.findViewById<TextView>(R.id.tvPresetAll)
        val tvPeriodCredits = view.findViewById<TextView>(R.id.tvPeriodCredits)
        val tvPeriodDebits = view.findViewById<TextView>(R.id.tvPeriodDebits)
        val progressCashFlow = view.findViewById<LinearProgressIndicator>(R.id.progressCashFlow)

        val tvTodayIncome = view.findViewById<TextView>(R.id.tvTodayIncome)
        val tvTodayExpense = view.findViewById<TextView>(R.id.tvTodayExpense)
        val tvTodayNet = view.findViewById<TextView>(R.id.tvTodayNet)

        val tvWeekIncome = view.findViewById<TextView>(R.id.tvWeekIncome)
        val tvWeekExpense = view.findViewById<TextView>(R.id.tvWeekExpense)
        val tvWeekNet = view.findViewById<TextView>(R.id.tvWeekNet)

        val tvMonthIncome = view.findViewById<TextView>(R.id.tvMonthIncome)
        val tvMonthExpense = view.findViewById<TextView>(R.id.tvMonthExpense)
        val tvMonthNet = view.findViewById<TextView>(R.id.tvMonthNet)

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

            // REMOVED: Redundant peekHeight/state setup here to stop the jumping issue

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
        var availableDays: List<Long> = emptyList()
        val sliderDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        fun selectPreset(selected: TextView?) {
            val presets = listOf(tvPresetWeek, tvPresetMonth, tvPresetAll)
            presets.forEach { preset ->
                val isSelected = preset == selected
                preset.setBackgroundResource(
                    if (isSelected) R.drawable.bg_summary_preset_selected
                    else R.drawable.bg_summary_preset
                )
                preset.setTextColor(requireContext().getColor(
                    if (isSelected) R.color.text_primary else R.color.text_secondary
                ))
            }
        }

        fun applyPreset(daysBack: Long?) {
            val days = availableDays
            if (days.isEmpty()) return

            val endIndex = days.lastIndex
            val startIndex = if (daysBack == null) {
                0
            } else {
                val threshold = days[endIndex] - daysBack
                days.indexOfFirst { it >= threshold }.let { if (it < 0) 0 else it }
            }

            if (days.size > 1 && dateSlider.visibility == View.VISIBLE) {
                dateSlider.values = listOf(startIndex.toFloat(), endIndex.toFloat())
            }

            val startTimestamp = if (daysBack == null) 0L else days[startIndex]
            val endTimestamp = if (daysBack == null) Long.MAX_VALUE else days[endIndex]
            viewModel.setDateRange(startTimestamp, endTimestamp)

            tvDateRangeLabel.text = if (daysBack == null) {
                "ALL TIME"
            } else {
                "${sliderDateFormat.format(Date(days[startIndex]))}  —  ${sliderDateFormat.format(Date(days[endIndex]))}".uppercase()
            }
        }

        tvPresetWeek.setOnClickListener {
            applyPreset(7L * 24L * 60L * 60L * 1000L)
            selectPreset(tvPresetWeek)
        }
        tvPresetMonth.setOnClickListener {
            applyPreset(30L * 24L * 60L * 60L * 1000L)
            selectPreset(tvPresetMonth)
        }
        tvPresetAll.setOnClickListener {
            applyPreset(null)
            selectPreset(tvPresetAll)
        }

        viewModel.setDateRange(0L, Long.MAX_VALUE)
        tvDateRangeLabel.text = "ALL TIME"
        selectPreset(tvPresetAll)

        viewModel.performanceStats.observe(viewLifecycleOwner) { stats ->
            val days = stats.availableTimestamps
            availableDays = days

            if (days.size > 1 && !isSliderInitialized) {
                dateSlider.visibility = View.VISIBLE
                dateSlider.valueFrom = 0f
                dateSlider.valueTo = (days.size - 1).toFloat()
                dateSlider.stepSize = 1f

                val startIdxRaw = days.indexOfFirst { it >= (viewModel.dateRange.value?.first ?: 0L) }
                val endIdxRaw = days.indexOfLast { it <= (viewModel.dateRange.value?.second ?: Long.MAX_VALUE) }
                val startIdx = if (startIdxRaw < 0) 0 else startIdxRaw
                val endIdx = if (endIdxRaw < 0) days.lastIndex else endIdxRaw
                dateSlider.values = listOf(minOf(startIdx, endIdx).toFloat(), maxOf(startIdx, endIdx).toFloat())

                dateSlider.addOnChangeListener { slider, _, _ ->
                    val sIdx = slider.values[0].toInt().coerceIn(days.indices)
                    val eIdx = slider.values[1].toInt().coerceIn(days.indices)
                    tvDateRangeLabel.text = "${sliderDateFormat.format(Date(days[sIdx]))}  —  ${sliderDateFormat.format(Date(days[eIdx]))}".uppercase()
                    selectPreset(null)
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

            val income = stats.periodCredits
            val expense = abs(stats.periodDebits)
            val netSign = when {
                stats.periodNet > 0L -> "+ "
                stats.periodNet < 0L -> "- "
                else -> ""
            }
            animateText(tvPeriodNet, stats.periodNet) { v -> "$netSign$symbol ${fmt(abs(v))}" }
            tvPeriodNet.setTextColor(requireContext().getColor(
                when {
                    stats.periodNet > 0L -> R.color.income_green
                    stats.periodNet < 0L -> R.color.expense_red
                    else -> R.color.text_primary
                }
            ))
            animateText(tvPeriodCredits, income) { v -> "+ $symbol ${fmt(abs(v))}" }
            animateText(tvPeriodDebits, stats.periodDebits) { v -> "- $symbol ${fmt(abs(v))}" }

            val marginPercent = if (income > 0L) {
                ((income - expense).toDouble() / income.toDouble()) * 100.0
            } else {
                0.0
            }
            tvPeriodMargin.text = when {
                income <= 0L -> "— no income"
                marginPercent >= 0.0 -> "↗ +${String.format(Locale.getDefault(), "%.1f", marginPercent)}% margin"
                else -> "↘ ${String.format(Locale.getDefault(), "%.1f", marginPercent)}% deficit"
            }
            tvPeriodMargin.setTextColor(requireContext().getColor(
                if (marginPercent >= 0.0) R.color.income_muted else R.color.expense_muted
            ))

            // Income and expenses share one proportional cash-flow bar.
            val total = income + expense

            progressCashFlow.max = 100

            if (total > 0L) {
                val incomeRatio = ((income.toDouble() / total.toDouble()) * 100).toInt()
                progressCashFlow.setProgressCompat(incomeRatio, true)

                // FIX: UI Rendering - Remove the "green dot" or "red edge" bugs by dynamically syncing colors on 0-values
                val indicatorColor = if (income == 0L) R.color.expense_red else R.color.income_green
                val trackColor = if (expense == 0L) R.color.income_green else R.color.expense_red

                progressCashFlow.setIndicatorColor(requireContext().getColor(indicatorColor))
                progressCashFlow.trackColor = requireContext().getColor(trackColor)
            } else {
                // Edge Case: Empty/Neutral bar when both are 0
                progressCashFlow.setProgressCompat(0, false)
                progressCashFlow.setIndicatorColor(requireContext().getColor(R.color.text_tertiary))
                progressCashFlow.trackColor = requireContext().getColor(R.color.text_tertiary)
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