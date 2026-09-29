package com.mertbek.sharescreen.control


data class Zoom(val scale: Float = 1f, val left: Float = 0f, val top: Float = 0f) {

    val size: Float get() = 1f / scale

    val isZoomed: Boolean get() = scale > 1f

    fun pictureX(viewX: Float): Float = left + viewX * size

    fun pictureY(viewY: Float): Float = top + viewY * size

    fun transformed(factor: Float, fromX: Float, fromY: Float, toX: Float, toY: Float): Zoom {
        val newScale = (scale * factor).coerceIn(1f, MAX_SCALE)
        val newSize = 1f / newScale
        val anchorX = pictureX(fromX)
        val anchorY = pictureY(fromY)
        return Zoom(
            scale = newScale,
            left = (anchorX - toX * newSize).coerceIn(0f, 1f - newSize),
            top = (anchorY - toY * newSize).coerceIn(0f, 1f - newSize),
        )
    }

    fun toggled(viewX: Float, viewY: Float): Zoom =
        if (isZoomed) Zoom() else transformed(DOUBLE_TAP_SCALE, viewX, viewY, viewX, viewY)

    companion object {
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

