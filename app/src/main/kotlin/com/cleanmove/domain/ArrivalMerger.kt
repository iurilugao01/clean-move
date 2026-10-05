package com.cleanmove.domain

private const val DAY = 1440

class ArrivalMerger(
    private val toleranceMinutes: Int = 15,
    private val windowMinutes: Int = 60,
) {
    fun merge(
        live: List<LiveArrival>,
        scheduled: List<ScheduledArrival>,
        nowMinuteOfDay: Int,
    ): List<Arrival> {
        val codes = (live.map { it.lineCode } + scheduled.map { it.lineCode }).distinct()
        return codes
            .flatMap { code ->
                mergeLine(
                    code,
                    live.filter { it.lineCode == code }.sortedBy { it.minutesAway },
                    scheduled.filter { it.lineCode == code }.map { Math.floorMod(it.minuteOfDay, DAY) },
                    nowMinuteOfDay,
                )
            }
            .sortedBy { eta(it, nowMinuteOfDay) }
    }

    private fun mergeLine(code: String, live: List<LiveArrival>, scheduled: List<Int>, now: Int): List<Arrival> {
        val pool = scheduled.toMutableList()
        val result = mutableListOf<Arrival>()
        for (arrival in live) {
            val expected = Math.floorMod(now + arrival.minutesAway, DAY)
            val match = pool.minByOrNull { circularDistance(it, expected) }
            if (match != null && circularDistance(match, expected) <= toleranceMinutes) {
                pool.remove(match)
                result += Arrival(code, arrival.minutesAway, match)
            } else {
                result += Arrival(code, arrival.minutesAway, null)
            }
        }
        pool.filter { Math.floorMod(it - now, DAY) <= windowMinutes }
            .forEach { result += Arrival(code, null, it) }
        return result
    }

    private fun eta(arrival: Arrival, now: Int): Int =
        arrival.liveMinutes ?: Math.floorMod(arrival.scheduledMinuteOfDay!! - now, DAY)

    private fun circularDistance(a: Int, b: Int): Int =
        minOf(Math.floorMod(a - b, DAY), Math.floorMod(b - a, DAY))
}
