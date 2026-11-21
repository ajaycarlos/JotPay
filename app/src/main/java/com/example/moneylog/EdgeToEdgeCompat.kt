package com.example.moneylog

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Applies edge-to-edge layout while keeping each screen's original content padding.
 * System bars/cutouts are added outside the content area; the IME is handled as a
 * bottom inset so controls remain reachable while the keyboard is visible.
 */
object EdgeToEdgeCompat {
    fun apply(window: android.view.Window, root: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            view.setPadding(
                initialLeft + safeInsets.left,
                initialTop + safeInsets.top,
                initialRight + safeInsets.right,
                initialBottom + max(safeInsets.bottom, imeInsets.bottom)
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
