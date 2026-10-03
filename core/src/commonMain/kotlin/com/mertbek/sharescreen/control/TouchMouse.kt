package com.mertbek.sharescreen.control

import kotlin.math.hypot

/** A finger on the picture: where it is on the viewer's screen, to tell taps from drags, and on the picture. */
data class Fingertip(val id: Long, val x: Float, val y: Float, val pictureX: Float, val pictureY: Float)

/**
 * Turns fingers on the picture of a computer's screen into its mouse. A tap clicks, a drag drags with the left
 * button held, a finger held still for a second right clicks and two fingers moving up and down turn the wheel.
 * The button goes down only once it is clear which of these a touch is, so a right click or a scroll never
 * clicks first.
 */
class TouchMouse(private val slop: Float) {

    private enum class Mode { IDLE, PRESSED, DRAGGING, SCROLLING, DONE }

    private var mode = Mode.IDLE
    private var start: Fingertip? = null
    private var last: Fingertip? = null
    private var downTime = 0L
    private var wheelX = 0f
    private var wheelY = 0f
    private var scrollIds = emptySet<Long>()
    private var scrollY = 0f
    private var notches = 0f

    /** When a finger held still since it touched becomes a right click, or null while no finger waits for it. */
    val holdDeadline: Long? get() = if (mode == Mode.PRESSED) downTime + HOLD_MILLIS else null

    fun update(time: Long, fingers: List<Fingertip>): List<ControlMessage.Pointer> = buildList {
        addAll(hold(time))
        when (mode) {
            Mode.IDLE -> when {
                fingers.size > 1 -> startScroll(fingers)
                fingers.size == 1 -> {
                    val finger = fingers.single()
                    start = finger
                    last = finger
                    downTime = time
                    mode = Mode.PRESSED
                    add(pointer(PointerAction.MOVE, finger))
                }
            }
            Mode.PRESSED -> {
                val start = start ?: return@buildList
                val finger = fingers.firstOrNull { it.id == start.id }
                when {
                    fingers.size > 1 -> startScroll(fingers)
                    finger == null -> {
                        add(pointer(PointerAction.DOWN, start))
                        add(pointer(PointerAction.UP, start))
                        mode = if (fingers.isEmpty()) Mode.IDLE else Mode.DONE
                    }
                    hypot(finger.x - start.x, finger.y - start.y) > slop -> {
                        add(pointer(PointerAction.DOWN, start))
                        add(pointer(PointerAction.MOVE, finger))
                        last = finger
                        mode = Mode.DRAGGING
                    }
                }
            }
            Mode.DRAGGING -> {
                val finger = fingers.firstOrNull { it.id == start?.id }
                if (finger == null) {
                    last?.let { add(pointer(PointerAction.UP, it)) }
                    mode = if (fingers.isEmpty()) Mode.IDLE else Mode.DONE
                } else if (finger != last) {
                    add(pointer(PointerAction.MOVE, finger))
                    last = finger
                }
            }
            Mode.SCROLLING -> {
                if (fingers.isEmpty()) {
                    mode = Mode.IDLE
                    return@buildList
                }
                val ids = fingers.mapTo(HashSet()) { it.id }
                val y = fingers.map { it.pictureY }.average().toFloat()
                // A finger coming or going moves the middle of them, which is no scroll.
                if (ids != scrollIds) {
                    scrollIds = ids
                    scrollY = y
                    return@buildList
                }
                // Fingers moving up drag the page up, as the wheel turned down does.
                notches += (scrollY - y) / NOTCH_HEIGHT
                scrollY = y
                val whole = notches.toInt()
                if (whole != 0) {
                    notches -= whole
                    add(ControlMessage.Pointer(PointerAction.SCROLL, wheelX, wheelY, scrollY = whole.toFloat()))
                }
            }
            Mode.DONE -> if (fingers.isEmpty()) mode = Mode.IDLE
        }
    }

    /** The right click of a finger held still until [holdDeadline], once [time] has reached it. */
    fun hold(time: Long): List<ControlMessage.Pointer> {
        val start = start
        if (mode != Mode.PRESSED || start == null || time < downTime + HOLD_MILLIS) return emptyList()
        mode = Mode.DONE
        return listOf(pointer(PointerAction.DOWN, start, PointerButton.RIGHT), pointer(PointerAction.UP, start, PointerButton.RIGHT))
    }

    private fun startScroll(fingers: List<Fingertip>) {
        mode = Mode.SCROLLING
        scrollIds = fingers.mapTo(HashSet()) { it.id }
        scrollY = fingers.map { it.pictureY }.average().toFloat()
        notches = 0f
        wheelX = fingers.map { it.pictureX }.average().toFloat()
        wheelY = scrollY
    }

    private fun pointer(action: PointerAction, at: Fingertip, button: PointerButton = PointerButton.LEFT) =
        ControlMessage.Pointer(action, at.pictureX, at.pictureY, button)

    private companion object {
        const val HOLD_MILLIS = 1_000L

        /** How far up the picture two fingers go for a notch of the wheel, about as far as the page then moves. */
        const val NOTCH_HEIGHT = 1 / 16f
    }
}
