package com.cleanmove.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalMergerTest {
    private val merger = ArrivalMerger()

    @Test
    fun `pairs a live arrival with the nearest scheduled one of the same line`() {
        val result = merger.merge(
            live = listOf(LiveArrival("0010", 7)),
            scheduled = listOf(ScheduledArrival("0010", 8 * 60 + 5), ScheduledArrival("0010", 8 * 60 + 35)),
            nowMinuteOfDay = 8 * 60,
        )
        assertEquals(
            listOf(
                Arrival("0010", 7, 8 * 60 + 5),
                Arrival("0010", null, 8 * 60 + 35),
            ),
            result,
        )
    }

    @Test
    fun `live arrival with no scheduled counterpart keeps scheduled null`() {
        val result = merger.merge(listOf(LiveArrival("0010", 4)), emptyList(), 600)
        assertEquals(listOf(Arrival("0010", 4, null)), result)
    }

    @Test
    fun `scheduled arrival with no live data is kept when inside the window`() {
        val result = merger.merge(emptyList(), listOf(ScheduledArrival("0010", 630)), 600)
        assertEquals(listOf(Arrival("0010", null, 630)), result)
    }

    @Test
    fun `scheduled arrival outside the window is dropped`() {
        val result = merger.merge(emptyList(), listOf(ScheduledArrival("0010", 600 + 61)), 600)
        assertEquals(emptyList<Arrival>(), result)
    }

    @Test
    fun `scheduled arrival already in the past and unmatched is dropped`() {
        val result = merger.merge(emptyList(), listOf(ScheduledArrival("0010", 590)), 600)
        assertEquals(emptyList<Arrival>(), result)
    }

    @Test
    fun `late bus pairs with a slightly past scheduled time`() {
        val result = merger.merge(listOf(LiveArrival("0010", 2)), listOf(ScheduledArrival("0010", 598)), 600)
        assertEquals(listOf(Arrival("0010", 2, 598)), result)
    }

    @Test
    fun `pairing wraps around midnight`() {
        val now = 23 * 60 + 55 // 23:55
        val result = merger.merge(listOf(LiveArrival("0010", 10)), listOf(ScheduledArrival("0010", 5)), now)
        assertEquals(listOf(Arrival("0010", 10, 5)), result)
    }

    @Test
    fun `never pairs across different lines`() {
        val result = merger.merge(
            listOf(LiveArrival("0010", 5)),
            listOf(ScheduledArrival("0022", 605)),
            600,
        )
        assertEquals(listOf(Arrival("0010", 5, null), Arrival("0022", null, 605)), result)
    }

    @Test
    fun `results are sorted by earliest expected arrival`() {
        val result = merger.merge(
            live = listOf(LiveArrival("0022", 12), LiveArrival("0010", 3)),
            scheduled = emptyList(),
            nowMinuteOfDay = 600,
        )
        assertEquals(listOf("0010", "0022"), result.map { it.lineCode })
    }
}
