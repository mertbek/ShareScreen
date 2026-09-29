package com.mertbek.sharescreen.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CaptureSize(val width: Int, val height: Int, val densityDpi: Int) {

    companion object {
        const val DEFAULT_MAX_LONG_EDGE = 1280

        fun forDefaultDisplay(context: Context, maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE): CaptureSize {
            val display = context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            return scaled(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, maxLongEdge)
        }

        fun scaled(width: Int, height: Int, densityDpi: Int, maxLongEdge: Int): CaptureSize {
            val scale = min(1f, maxLongEdge.toFloat() / max(width, height))
            return CaptureSize(
                width = toEven((width * scale).roundToInt()),
                height = toEven((height * scale).roundToInt()),
                densityDpi = densityDpi,
            )
        }

        private fun toEven(value: Int): Int = value and 1.inv()
    }
}
