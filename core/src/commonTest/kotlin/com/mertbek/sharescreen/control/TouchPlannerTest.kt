package com.mertbek.sharescreen.control

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class TouchPlannerTest {

    private val planner = TouchPlanner()

    private fun p(x: Float, y: Float) = ScreenPoint(x, y)

    @Test
    fun `a tap touches down at once and lifts on release`() {
        planner.update(1_000, listOf(1L to p(10f, 20f)))
        assertEquals(
            listOf(StrokePlan(1, listOf(p(10f, 20f)), 1, continues = false, willContinue = true)),
            planner.nextBatch(),
        )
        planner.onBatchFinished(completed = true)
        assertNull(planner.nextBatch())

        planner.update(1_080, emptyList())
        assertEquals(
            listOf(StrokePlan(1, listOf(p(10f, 20f), p(10f, 20f)), 1, continues = true, willContinue = false)),
            planner.nextBatch(),
        )
        planner.onBatchFinished(completed = true)
        assertNull(planner.nextBatch())
    }

    @Test
    fun `positions arriving during a batch go into the next one, at their own pace`() {
        planner.update(0, listOf(1L to p(0f, 0f)))
        planner.nextBatch()
        planner.update(16, listOf(1L to p(5f, 0f)))
        planner.update(32, listOf(1L to p(10f, 0f)))
        assertNull(planner.nextBatch())

        planner.onBatchFinished(completed = true)
        assertEquals(
            listOf(StrokePlan(1, listOf(p(0f, 0f), p(5f, 0f), p(10f, 0f)), 16, continues = true, willContinue = true)),
            planner.nextBatch(),
        )
    }

    @Test
    fun `a long burst is replayed faster to catch up`() {
        planner.update(0, listOf(1L to p(0f, 0f)))
        planner.nextBatch()
        for (i in 1..30) planner.update(i * 16L, listOf(1L to p(i.toFloat(), 0f)))
        planner.onBatchFinished(completed = true)

        assertEquals(100, planner.nextBatch()!!.single().durationMillis)
    }

    @Test
    fun `a tap that arrived while busy keeps how long it was held`() {
        planner.update(0, listOf(1L to p(0f, 0f)))
        planner.nextBatch()
        planner.update(10, emptyList())
        planner.update(20, listOf(2L to p(50f, 50f)))
        planner.update(90, emptyList())
        planner.onBatchFinished(completed = true)

        val batch = planner.nextBatch()!!
        assertEquals(listOf(1L, 2L), batch.map { it.pointerId })
        assertEquals(StrokePlan(2, listOf(p(50f, 50f), p(50f, 50f)), 70, continues = false, willContinue = false), batch[1])
        assertEquals(false, batch[0].willContinue)
    }

    @Test
    fun `fingers that stay still are continued alongside moving ones`() {
        planner.update(0, listOf(1L to p(0f, 0f), 2L to p(100f, 0f)))
        planner.nextBatch()
        planner.onBatchFinished(completed = true)

        planner.update(16, listOf(1L to p(0f, 0f), 2L to p(90f, 0f)))
        val batch = planner.nextBatch()!!
        assertEquals(listOf(true, true), batch.map { it.continues })
        assertEquals(listOf(p(100f, 0f), p(90f, 0f)), batch.single { it.pointerId == 2L }.points)
    }

    @Test
    fun `a still finger is kept down while another one touches down`() {
        planner.update(0, listOf(1L to p(0f, 0f)))
        planner.nextBatch()
        planner.onBatchFinished(completed = true)

        planner.update(40, listOf(1L to p(0f, 0f), 2L to p(100f, 0f)))
        val batch = planner.nextBatch()!!
        assertEquals(StrokePlan(1, listOf(p(0f, 0f), p(0f, 0f)), 1, continues = true, willContinue = true), batch[0])
        assertEquals(StrokePlan(2, listOf(p(100f, 0f)), 1, continues = false, willContinue = true), batch[1])
    }

    @Test
    fun `a cancelled gesture is ignored until its fingers lift`() {
        planner.update(0, listOf(1L to p(0f, 0f)))
        planner.nextBatch()
        planner.onBatchFinished(completed = false)

        planner.update(16, listOf(1L to p(5f, 0f)))
        assertNull(planner.nextBatch())
        planner.update(32, emptyList())
        assertNull(planner.nextBatch())

        planner.update(48, listOf(2L to p(1f, 1f)))
        assertEquals(listOf(2L), planner.nextBatch()!!.map { it.pointerId })
    }

    @Test
    fun `a flood of positions does not grow the queue without limit`() {
        repeat(10_000) { planner.update(it.toLong(), listOf(1L to p(it.toFloat(), 0f))) }
        val points = planner.nextBatch()!!.single().points
        assertTrue(points.size <= 256, "queued ${points.size}")
        assertEquals(9_999f, points.last().x)
    }

    @Test
    fun `only a limited number of fingers is tracked`() {
        planner.update(0, (1L..100L).map { it to p(it.toFloat(), 0f) })
        assertEquals(20, planner.nextBatch()!!.size)
    }

    @Test
    fun `releasing lifts every finger where it is`() {
        planner.update(0, listOf(1L to p(3f, 4f)))
        planner.nextBatch()
        planner.onBatchFinished(completed = true)

        planner.releaseAll()
        assertEquals(
            listOf(StrokePlan(1, listOf(p(3f, 4f)), 1, continues = true, willContinue = false)),
            planner.nextBatch(),
        )
        planner.onBatchFinished(completed = true)
        assertNull(planner.nextBatch())
    }
}
