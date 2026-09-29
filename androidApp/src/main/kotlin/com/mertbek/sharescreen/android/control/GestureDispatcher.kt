package com.mertbek.sharescreen.android.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.Log
import com.mertbek.sharescreen.control.ScreenPoint
import com.mertbek.sharescreen.control.StrokePlan

internal class GestureDispatcher(private val service: AccessibilityService) {

    private class Stroke(val description: GestureDescription.StrokeDescription, val end: ScreenPoint)

    private val strokes = HashMap<Long, Stroke>()

    fun dispatch(plans: List<StrokePlan>, onFinished: (completed: Boolean) -> Unit) {
        val built = HashMap<Long, Stroke>()
        val gesture = try {
            val builder = GestureDescription.Builder()
            for (plan in plans) {
                val stroke = build(plan)
                builder.addStroke(stroke.description)
                built[plan.pointerId] = stroke
            }
            builder.build()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not build gesture", e)
            strokes.clear()
            onFinished(false)
            return
        }

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                strokes.clear()
                for (plan in plans) if (plan.willContinue) strokes[plan.pointerId] = built.getValue(plan.pointerId)
                onFinished(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                strokes.clear()
                onFinished(false)
            }
        }
        if (!service.dispatchGesture(gesture, callback, null)) {
            strokes.clear()
            onFinished(false)
        }
    }

    private fun build(plan: StrokePlan): Stroke {
        val previous = if (plan.continues) strokes[plan.pointerId] else null
        val points = if (previous != null) listOf(previous.end) + plan.points.drop(1) else plan.points
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (point in points.drop(1)) lineTo(point.x, point.y)
        }
        val description = previous?.description?.continueStroke(path, 0, plan.durationMillis, plan.willContinue)
            ?: GestureDescription.StrokeDescription(path, 0, plan.durationMillis, plan.willContinue)
        return Stroke(description, endOf(path))
    }

    private fun endOf(path: Path): ScreenPoint {
        val position = FloatArray(2)
        val measure = PathMeasure(path, false)
        if (measure.length == 0f) {
            val probe = Path(path).apply { lineTo(-1f, -1f) }
            PathMeasure(probe, false).getPosTan(0f, position, null)
        } else {
            measure.getPosTan(measure.length, position, null)
        }
        return ScreenPoint(position[0], position[1])
    }

    private companion object {
        const val TAG = "GestureDispatcher"
    }
}
