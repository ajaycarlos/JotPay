package com.example.moneylog

import android.graphics.Canvas
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EdgeEffect
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Temporarily lifts the transaction rows when the user keeps dragging past
 * the bottom of the list. The rows stay displaced while the finger is down
 * and animate back on release.
 *
 * The top edge is deliberately left to the parent SwipeRefreshLayout.
 */
class BottomBounceEdgeEffectFactory(
    private val recyclerView: RecyclerView
) : RecyclerView.EdgeEffectFactory() {

    override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
        if (direction != DIRECTION_BOTTOM) {
            return super.createEdgeEffect(view, direction)
        }

        return object : EdgeEffect(view.context) {
            private var pullDistance = 0f

            override fun onPull(deltaDistance: Float) {
                updatePull(deltaDistance)
            }

            override fun onPull(deltaDistance: Float, displacement: Float) {
                updatePull(deltaDistance)
            }

            private fun updatePull(deltaDistance: Float) {
                pullDistance = (pullDistance + abs(deltaDistance))
                    .coerceAtMost(MAX_PULL_DISTANCE)

                recyclerView.animate().cancel()
                val maxLiftPx = MAX_LIFT_DP * recyclerView.resources.displayMetrics.density
                val liftPx = (pullDistance / MAX_PULL_DISTANCE) * maxLiftPx

                // Translate the actual rows, not the RecyclerView viewport or
                // the buttons. This reveals the final log while the gesture
                // is still held.
                for (index in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(index)
                    child.animate().cancel()
                    child.translationY = -liftPx
                }
            }

            override fun onRelease() {
                if (pullDistance == 0f) return

                pullDistance = 0f
                for (index in 0 until recyclerView.childCount) {
                    recyclerView.getChildAt(index).animate()
                        .translationY(0f)
                        .setDuration(BOUNCE_BACK_DURATION_MS)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }

            override fun onAbsorb(velocity: Int) {
                pullDistance = (abs(velocity).toFloat() / ABSORB_VELOCITY_DIVISOR)
                    .coerceAtMost(MAX_PULL_DISTANCE)
                val maxLiftPx = MAX_LIFT_DP * recyclerView.resources.displayMetrics.density
                val liftPx = (pullDistance / MAX_PULL_DISTANCE) * maxLiftPx

                for (index in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(index)
                    child.animate().cancel()
                    child.translationY = -liftPx
                }
                onRelease()
            }

            override fun draw(canvas: Canvas): Boolean = false

            override fun isFinished(): Boolean = pullDistance == 0f

            override fun finish() {
                pullDistance = 0f
                recyclerView.animate().cancel()
                for (index in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(index)
                    child.animate().cancel()
                    child.translationY = 0f
                }
            }
        }
    }

    private companion object {
        // Allows a longer drag so the last transaction can clear the
        // sign-entry controls, while retaining a restrained rubber-band feel.
        const val MAX_PULL_DISTANCE = 0.22f
        const val MAX_LIFT_DP = 104f
        const val BOUNCE_BACK_DURATION_MS = 280L
        const val ABSORB_VELOCITY_DIVISOR = 30000f
    }
}
