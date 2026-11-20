package com.example.moneylog

import android.graphics.Canvas
import android.os.Build
import android.view.animation.DecelerateInterpolator
import android.widget.EdgeEffect
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Adds a restrained rubber-band response when the transaction list is pulled
 * past its bottom edge. The top edge is left to SwipeRefreshLayout so
 * pull-to-refresh keeps its existing behaviour.
 *
 * Android 12+ uses the platform's native stretch effect. Earlier Android
 * versions use the custom scale-based fallback below.
 */
class BottomBounceEdgeEffectFactory(
    private val recyclerView: RecyclerView
) : RecyclerView.EdgeEffectFactory() {

    override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
        if (direction != DIRECTION_BOTTOM || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
                pullDistance = (pullDistance + abs(deltaDistance)).coerceAtMost(MAX_PULL_DISTANCE)
                recyclerView.animate().cancel()
                recyclerView.pivotY = 0f
                recyclerView.scaleY = 1f - (pullDistance * MAX_SCALE_REDUCTION)
            }

            override fun onRelease() {
                if (pullDistance == 0f) return

                pullDistance = 0f
                recyclerView.animate()
                    .scaleY(1f)
                    .setDuration(BOUNCE_BACK_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            override fun onAbsorb(velocity: Int) {
                pullDistance = (abs(velocity).toFloat() / ABSORB_VELOCITY_DIVISOR)
                    .coerceAtMost(MAX_PULL_DISTANCE)
                recyclerView.pivotY = 0f
                recyclerView.scaleY = 1f - (pullDistance * MAX_SCALE_REDUCTION)
                onRelease()
            }

            override fun draw(canvas: Canvas): Boolean = false

            override fun isFinished(): Boolean = pullDistance == 0f

            override fun finish() {
                pullDistance = 0f
                recyclerView.animate().cancel()
                recyclerView.scaleY = 1f
            }
        }
    }

    private companion object {
        const val MAX_PULL_DISTANCE = 0.10f
        const val MAX_SCALE_REDUCTION = 0.45f
        const val BOUNCE_BACK_DURATION_MS = 260L
        const val ABSORB_VELOCITY_DIVISOR = 30000f
    }
}
