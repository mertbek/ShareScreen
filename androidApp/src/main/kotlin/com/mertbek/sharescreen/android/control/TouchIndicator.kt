package com.mertbek.sharescreen.android.control

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.mertbek.sharescreen.control.ScreenPoint

internal class TouchIndicator(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: IndicatorView? = null

    fun show(points: List<ScreenPoint>) {
        val view = view ?: attach() ?: return
        if (points.isEmpty()) {
            view.animate().alpha(0f).setDuration(FADE_MILLIS)
            return
        }
        view.points = points
        view.animate().cancel()
        view.alpha = 1f
        view.invalidate()
    }

    fun remove() {
        val view = view ?: return
        this.view = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun attach(): IndicatorView? {
        val view = IndicatorView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            windowManager.addView(view, params)
            this.view = view
            view
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not show touch indicator", e)
            null
        }
    }

    @SuppressLint("ViewConstructor")
    private class IndicatorView(context: Context) : View(context) {
        var points: List<ScreenPoint> = emptyList()

        private val density = context.resources.displayMetrics.density
        private val radius = RADIUS_DP * density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL_COLOR }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RING_COLOR
            style = Paint.Style.STROKE
            strokeWidth = RING_WIDTH_DP * density
        }
        private val location = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(location)
            for (point in points) {
                val x = point.x - location[0]
                val y = point.y - location[1]
                canvas.drawCircle(x, y, radius, fill)
                canvas.drawCircle(x, y, radius, ring)
            }
        }
    }

    private companion object {
        const val TAG = "TouchIndicator"
        const val FADE_MILLIS = 300L
        const val RADIUS_DP = 18f
        const val RING_WIDTH_DP = 2f
        const val FILL_COLOR = 0x66FFFFFF
        const val RING_COLOR = 0xCC000000.toInt()
    }
}
