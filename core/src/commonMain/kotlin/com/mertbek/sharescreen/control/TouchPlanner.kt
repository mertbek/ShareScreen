package com.mertbek.sharescreen.control

data class ScreenPoint(val x: Float, val y: Float)

data class StrokePlan(
    val pointerId: Long,
    val points: List<ScreenPoint>,
    val durationMillis: Long,
    val continues: Boolean,
    val willContinue: Boolean,
)

class TouchPlanner {

    private class TimedPoint(val time: Long, val point: ScreenPoint)

    private class Track(var last: ScreenPoint) {
        val pending = ArrayDeque<TimedPoint>()
        var started = false
        var up = false
    }

    private class InFlight(val pointerId: Long, val pointCount: Int, val willContinue: Boolean)

    private val tracks = LinkedHashMap<Long, Track>()
    private val abandoned = HashSet<Long>()
    private var inFlight: List<InFlight>? = null

    fun update(time: Long, pointers: List<Pair<Long, ScreenPoint>>) {
        val ids = pointers.mapTo(HashSet()) { it.first }
        abandoned.retainAll(ids)
        for ((id, point) in pointers) {
            if (id in abandoned) continue
            val track = tracks.getOrPut(id) { Track(point) }
            if (!track.up) track.pending += TimedPoint(time, point)
        }
        for ((id, track) in tracks) {
            if (id !in ids && !track.up) {
                track.up = true
                track.pending += TimedPoint(time, track.pending.lastOrNull()?.point ?: track.last)
            }
        }
    }

    fun releaseAll() {
        for (track in tracks.values) track.up = true
    }

    fun reset() {
        tracks.clear()
        abandoned.clear()
        inFlight = null
    }

    fun nextBatch(): List<StrokePlan>? {
        if (inFlight != null) return null
        if (tracks.values.none { it.pending.isNotEmpty() || it.up }) return null

        val span = tracks.values.maxOf { track ->
            if (track.pending.isEmpty()) 0L else track.pending.last().time - track.pending.first().time
        }
        val duration = span.coerceIn(1L, MAX_BATCH_MILLIS)
        val plans = tracks.map { (id, track) ->
            val points = buildList {
                if (track.started || track.pending.isEmpty()) add(track.last)
                track.pending.mapTo(this) { it.point }
            }
            StrokePlan(id, points, duration, continues = track.started, willContinue = !track.up)
        }
        inFlight = plans.map { InFlight(it.pointerId, tracks.getValue(it.pointerId).pending.size, it.willContinue) }
        return plans
    }

    fun onBatchFinished(completed: Boolean) {
        val batch = inFlight ?: return
        inFlight = null
        if (!completed) {
            tracks.filterValues { !it.up }.keys.forEach(abandoned::add)
            tracks.clear()
            return
        }
        for (entry in batch) {
            val track = tracks[entry.pointerId] ?: continue
            repeat(entry.pointCount) { track.last = track.pending.removeFirst().point }
            if (entry.willContinue) track.started = true else tracks.remove(entry.pointerId)
        }
    }

    private companion object {
        const val MAX_BATCH_MILLIS = 100L
    }
}
