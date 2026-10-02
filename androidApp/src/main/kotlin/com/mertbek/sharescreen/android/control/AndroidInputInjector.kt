package com.mertbek.sharescreen.android.control

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.ViewConfiguration
import com.mertbek.sharescreen.control.ControlKey
import com.mertbek.sharescreen.control.ControlMessage
import com.mertbek.sharescreen.control.HostPlatform
import com.mertbek.sharescreen.control.NavAction
import com.mertbek.sharescreen.control.PointerAction
import com.mertbek.sharescreen.control.ScreenPoint
import com.mertbek.sharescreen.control.StrokePlan
import com.mertbek.sharescreen.control.TouchPlanner
import com.mertbek.sharescreen.control.TouchPointer
import com.mertbek.sharescreen.control.WheelPlanner
import com.mertbek.sharescreen.platform.InputInjector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidInputInjector(private val context: Context) : InputInjector {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isAvailable = MutableStateFlow(false)

    override val platform = HostPlatform.ANDROID
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private var service: AccessibilityService? = null
    private var gestures: GestureDispatcher? = null
    private var indicator: TouchIndicator? = null
    private val planner = TouchPlanner()
    private var wheel: WheelPlanner? = null
    private var scrolling = false
    private val textInput = TextInput()

    internal fun attach(service: AccessibilityService) {
        this.service = service
        gestures = GestureDispatcher(service)
        indicator = TouchIndicator(service)
        planner.reset()
        wheel = WheelPlanner(service.resources.displayMetrics.density, ViewConfiguration.get(service).scaledTouchSlop.toFloat())
        _isAvailable.value = true
    }

    internal fun detach(service: AccessibilityService) {
        if (this.service !== service) return
        this.service = null
        gestures = null
        indicator?.remove()
        indicator = null
        planner.reset()
        wheel = null
        scrolling = false
        _isAvailable.value = false
    }

    override fun touch(time: Long, pointers: List<TouchPointer>) = onMain {
        if (gestures == null) return@onMain
        wheel?.clear()
        val (width, height) = displaySize()
        val points = pointers.take(MAX_POINTERS).map { it.id to ScreenPoint(toPixels(it.x, width), toPixels(it.y, height)) }
        planner.update(time, points)
        dispatchPending()
        indicator?.show(points.map { it.second })
    }

    override fun releaseInput() = onMain {
        wheel?.clear()
        planner.releaseAll()
        dispatchPending()
        indicator?.remove()
    }

    override fun navigate(action: NavAction) = onMain {
        service?.performGlobalAction(
            when (action) {
                NavAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                NavAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                NavAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            }
        )
    }

    override fun type(deleteBefore: Int, text: String) = onMain {
        service?.let { textInput.type(it, deleteBefore.coerceIn(0, MAX_EDIT_LENGTH), text.take(MAX_EDIT_LENGTH)) }
    }

    override fun press(key: ControlKey) = onMain {
        val service = service ?: return@onMain
        when (key) {
            ControlKey.ENTER -> textInput.enter(service)
        }
    }

    /** Only the wheel comes this way, as a mouse's buttons reach a phone as touches. */
    override fun pointer(message: ControlMessage.Pointer) = onMain {
        val wheel = wheel ?: return@onMain
        if (message.action != PointerAction.SCROLL || !planner.isIdle) return@onMain
        val (width, height) = displaySize()
        wheel.add(ScreenPoint(toPixels(message.x, width), toPixels(message.y, height)), message.scrollX, message.scrollY)
        if (!scrolling) scrollNext()
    }

    override fun keyboard(message: ControlMessage.Keyboard) = Unit

    private fun dispatchPending() {
        val gestures = gestures ?: return
        val batch = planner.nextBatch() ?: return
        gestures.dispatch(batch) { completed ->
            planner.onBatchFinished(completed)
            dispatchPending()
        }
    }

    /** Swipes for the wheel, each held still at its end for a moment so the content stops there instead of flinging on. */
    private fun scrollNext() {
        val gestures = gestures ?: return
        if (!planner.isIdle) return
        val (width, height) = displaySize()
        val swipe = wheel?.next(width, height) ?: return
        scrolling = true
        val drag = StrokePlan(WHEEL_POINTER, listOf(swipe.from, swipe.to), SWIPE_MILLIS, continues = false, willContinue = true)
        gestures.dispatch(listOf(drag)) { dragged ->
            if (!dragged) {
                scrolling = false
                wheel?.clear()
                return@dispatch
            }
            val hold = StrokePlan(WHEEL_POINTER, listOf(swipe.to, swipe.to), HOLD_MILLIS, continues = true, willContinue = false)
            gestures.dispatch(listOf(hold)) { held ->
                scrolling = false
                if (held) scrollNext() else wheel?.clear()
            }
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun toPixels(fraction: Float, size: Int): Float =
        (fraction.takeUnless { it.isNaN() } ?: 0f).coerceIn(0f, 1f) * (size - 1)

    private inline fun onMain(crossinline block: () -> Unit) {
        mainHandler.post { block() }
    }

    private companion object {
        const val MAX_POINTERS = 10
        const val WHEEL_POINTER = -1L
        const val SWIPE_MILLIS = 100L
        const val HOLD_MILLIS = 120L
        const val MAX_EDIT_LENGTH = 10_000
    }
}
