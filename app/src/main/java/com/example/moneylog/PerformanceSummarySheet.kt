package com.example.moneylog

import android.app.Dialog
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
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        return dialog
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

        val summaryContainer = view.findViewById<LinearLayout>(R.id.summaryContainer)
        val tvDragHint = view.findViewById<TextView>(R.id.tvDragHint)

        // BIND NEW MAP COMPONENTS
        val timelineMapView = view.findViewById<TimelineMapView>(R.id.timelineMapView)
        val zoomSlider = view.findViewById<Slider>(R.id.zoomSlider)
        val btnRecenter = view.findViewById<ImageButton>(R.id.btnRecenter)
        val mapControls = view.findViewById<LinearLayout>(R.id.mapControls)

        val tvDateRangeLabel = view.findViewById<TextView>(R.id.tvDateRangeLabel)
        val dateSlider = view.findViewById<RangeSlider>(R.id.dateSlider)

        val tvPeriodNet = view.findViewById<TextView>(R.id.tvPeriodNet)
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
            timelineMapView.recenterToMiddle()
            zoomSlider.value = 0.8f
        }

        zoomSlider.addOnChangeListener { _, value, _ ->
            timelineMapView.setZoom(value)
        }

        summaryContainer.post {
            val bottomSheet = (view.parent as View)
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.peekHeight = summaryContainer.height
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
            behavior.isHideable = true // FIX: Re-enables dragging down to close

            behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    // FIX: Only allow Map touch interaction when fully expanded
                    timelineMapView.isEnabled = newState == BottomSheetBehavior.STATE_EXPANDED
                }
                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    // SHADE IN ANIMATION
                    summaryContainer.alpha = 1f - (slideOffset * 1.2f).coerceAtMost(1f)
                    tvDragHint.alpha = 1f - slideOffset

                    // PHYSICALLY slide it off the top so it doesn't block the map
                    summaryContainer.translationY = - (summaryContainer.height * slideOffset)

                    // Map controls appear at the very end
                    mapControls.alpha = if (slideOffset > 0.9f) (slideOffset - 0.9f) * 10f else 0f
                }
            })
        }

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

            tvTodayNet.setTextColor(requireContext().getColor(if (stats.todayNet > 0) R.color.income_green else if (stats.todayNet < 0) R.color.expense_red else R.color.text_primary))
            tvWeekNet.setTextColor(requireContext().getColor(if (stats.weekNet > 0) R.color.income_green else if (stats.weekNet < 0) R.color.expense_red else R.color.text_primary))
            tvMonthNet.setTextColor(requireContext().getColor(if (stats.monthNet > 0) R.color.income_green else if (stats.monthNet < 0) R.color.expense_red else R.color.text_primary))

            animateText(tvPeriodNet, stats.periodNet) { v -> "Net: $symbol ${fmt(v)}" }
            animateText(tvPeriodCredits, stats.periodCredits) { v -> "+ $symbol ${fmt(abs(v))}" }
            animateText(tvPeriodDebits, stats.periodDebits) { v -> "- $symbol ${fmt(abs(v))}" }

            val maxProgress = if (stats.periodCredits > 0) stats.periodCredits else 1.0
            val currentProgress = abs(stats.periodDebits)
            progressCashFlow.max = maxProgress.toInt()
            progressCashFlow.setProgressCompat(currentProgress.toInt(), true)

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
            timelineMapView.submitList(stats.timeline)
        }
    }

    private fun fmt(d: Double): String {
        return if (d % 1.0 == 0.0) d.toLong().toString() else String.format("%.2f", d)
    }

    private fun animateText(tv: TextView, target: Double, format: (Double) -> String) {
        val start = (tv.tag as? Double) ?: 0.0
        if (start == target && tv.text.isNotEmpty()) return
        tv.tag = target

        val animator = android.animation.ValueAnimator.ofFloat(start.toFloat(), target.toFloat())
        animator.duration = 800
        animator.interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        animator.addUpdateListener { anim ->
            tv.text = format((anim.animatedValue as Float).toDouble())
        }
        animator.start()
    }
}