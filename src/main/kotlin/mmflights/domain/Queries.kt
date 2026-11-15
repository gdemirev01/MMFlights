package mmflights.domain

import java.time.LocalDate

/** Result of turning a tracker into concrete provider queries. */
data class Expansion(val queries: List<SearchQuery>, val totalCombinations: Int) {
    val truncated: Boolean get() = totalCombinations > queries.size
}

/** All (departure, return?) date pairs a trip allows, skipping dates before [today]. */
fun Trip.datePairs(today: LocalDate): List<Pair<LocalDate, LocalDate?>> = when (this) {
    is Trip.OneWay -> departure.days().filterNot { it.isBefore(today) }.map { it to null }
    is Trip.Return -> departure.days()
        .filterNot { it.isBefore(today) }
        .flatMap { dep -> returnDatesFor(dep).map { ret -> dep to ret } }
}

private fun Trip.Return.returnDatesFor(dep: LocalDate): List<LocalDate> {
    val fromStay = stay?.let { s -> (s.minNights..s.maxNights).map { dep.plusDays(it.toLong()) } }
    val candidates = fromStay ?: returnWindow?.days().orEmpty()
    return candidates
        .filter { it.isAfter(dep) || it == dep }
        .filter { returnWindow == null || it in returnWindow }
}

/**
 * Cartesian product origins × destinations × date pairs, capped at [Tracker.maxQueries].
 * Dates are interleaved first so that a truncated plan still covers every route.
 */
fun Tracker.expand(today: LocalDate): Expansion {
    val routes = origins.toList().flatMap { o -> destinations.filter { it != o }.map { d -> o to d } }
    val dates = trip.datePairs(today)
    val all = dates.flatMap { (dep, ret) ->
        routes.map { (o, d) ->
            SearchQuery(o, d, dep, ret, passengers, cabin, maxStops, currency)
        }
    }
    return Expansion(all.take(maxQueries), all.size)
}
