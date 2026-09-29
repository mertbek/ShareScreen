package com.mertbek.sharescreen.control

import kotlin.math.min

data class FittedRect(val left: Float, val top: Float, val width: Float, val height: Float) {

    fun fractionX(x: Float): Float = if (width <= 0f) 0f else ((x - left) / width).coerceIn(0f, 1f)

    fun fractionY(y: Float): Float = if (height <= 0f) 0f else ((y - top) / height).coerceIn(0f, 1f)

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= left + width && y >= top && y <= top + height
}

fun fitVideo(containerWidth: Float, containerHeight: Float, videoWidth: Int, videoHeight: Int): FittedRect {
    if (videoWidth <= 0 || videoHeight <= 0 || containerWidth <= 0f || containerHeight <= 0f) {
        return FittedRect(0f, 0f, 0f, 0f)
    }
    val scale = min(containerWidth / videoWidth, containerHeight / videoHeight)
    val width = videoWidth * scale
    val height = videoHeight * scale
    return FittedRect((containerWidth - width) / 2f, (containerHeight - height) / 2f, width, height)
}
