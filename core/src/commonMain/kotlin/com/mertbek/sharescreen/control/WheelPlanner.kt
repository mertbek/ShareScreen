package com.mertbek.sharescreen.control

import kotlin.math.abs

/** A swipe standing in for a turn of the mouse wheel. */
data class WheelSwipe(val from: ScreenPoint, val to: ScreenPoint)

/**
 * Turns the mouse wheel into swipes, as a phone has none. Each swipe drags what is under the pointer as far as
 * the phone would scroll for the wheel, plus the [slop] a finger moves before the phone takes it for a drag. It
 * keeps clear of the screen's edges, where a swipe goes home or opens the notifications, and the wheel turned
 * while a swipe plays out is kept for the next one.
 */
class WheelPlanner(private val density: Float, private val slop: Float) {

    private var pointer = ScreenPoint(0f, 0f)
    private var pendingX = 0f
    private var pendingY = 0f

    fun add(at: ScreenPoint, scrollX: Float, scrollY: Float) {
        pointer = at
        pendingX = (pendingX + scrollX.finiteOrZero()).coerceIn(-MAX_PENDING_NOTCHES, MAX_PENDING_NOTCHES)
        pendingY = (pendingY + scrollY.finiteOrZero()).coerceIn(-MAX_PENDING_NOTCHES, MAX_PENDING_NOTCHES)
    }

    fun clear() {
        pendingX = 0f
        pendingY = 0f
    }

    /** The next swipe for a screen of the given size, or null while the wheel has not turned far enough. */
    fun next(width: Int, height: Int): WheelSwipe? {
        val vertical = abs(pendingY) >= MIN_NOTCHES
        if (!vertical && abs(pendingX) < MIN_NOTCHES) return null
        val pending = if (vertical) pendingY else pendingX
        val size = if (vertical) height else width
        val notch = NOTCH_DP * density
        val margin = MARGIN_DP * density
        val length = minOf(abs(pending) * notch + slop, MAX_SWIPE_DP * density, size - 2 * margin)
        if (length <= slop) {
            clear()
            return null
        }
        val used = (length - slop) / notch
        if (vertical) pendingY -= if (pending > 0) used else -used else pendingX -= if (pending > 0) used else -used

        // Scrolling down drags the content up, and scrolling right drags it to the left.
        val step = if (pending > 0) -length else length
        val along = (if (vertical) pointer.y else pointer.x).within(margin + maxOf(0f, -step), size - margin - maxOf(0f, step))
        val across = if (vertical) pointer.x.within(margin, width - margin) else pointer.y.within(margin, height - margin)
        return if (vertical) {
            WheelSwipe(ScreenPoint(across, along), ScreenPoint(across, along + step))
        } else {
            WheelSwipe(ScreenPoint(along, across), ScreenPoint(along + step, across))
        }
    }

    private fun Float.finiteOrZero() = if (isFinite()) this else 0f

    private fun Float.within(low: Float, high: Float) = if (low <= high) coerceIn(low, high) else (low + high) / 2

    private companion object {
        /** How far a phone scrolls for a notch of the wheel when a mouse is plugged into it. */
        const val NOTCH_DP = 64f
        const val MARGIN_DP = 64f
        const val MAX_SWIPE_DP = 480f
        const val MIN_NOTCHES = 0.25f
        const val MAX_PENDING_NOTCHES = 12f
    }
}
