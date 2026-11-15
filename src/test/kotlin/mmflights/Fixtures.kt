package mmflights

import arrow.core.nonEmptyListOf
import mmflights.domain.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object Fixtures {
    val policy = NotifyPolicy(
        onChange = true, alertOn = AlertOn.ANY_CHANGE,
        minChangePercent = BigDecimal.ZERO, minChangeAmount = BigDecimal.ONE,
        dailyAt = LocalTime.of(9, 0), zone = ZoneId.of("Europe/Sofia"),
    )

    fun tracker(
        trip: Trip = Trip.Return(range("2026-12-18", "2026-12-19"), null, StayRange(7, 8)),
        maxQueries: Int = 100,
        maxStops: Int? = null,
        maxPrice: BigDecimal? = null,
        times: FlightTimes = FlightTimes(),
        notify: NotifyPolicy = policy,
    ) = Tracker(
        id = "t1", name = "Sofia → London", origins = nonEmptyListOf("SOF"), destinations = nonEmptyListOf("LTN", "STN"),
        trip = trip, passengers = Passengers(2, listOf(8)), cabin = Cabin.ECONOMY, maxStops = maxStops, times = times,
        maxPrice = maxPrice, currency = "EUR", notify = notify, maxQueries = maxQueries, topN = 3,
    )

    fun range(from: String, to: String) = DateRange(LocalDate.parse(from), LocalDate.parse(to))

    val query = SearchQuery("SOF", "LTN", LocalDate.parse("2026-12-18"), LocalDate.parse("2026-12-25"), Passengers(2), Cabin.ECONOMY, null, "EUR")

    fun offer(
        price: String, flight: String = "123", stops: Int = 0, currency: String = "EUR", provider: String = "p",
        depart: String = "2026-12-18T06:10:00", arrive: String = "2026-12-18T07:55:00", minutes: Int? = 165,
        legs: Int = 1,
    ) = Offer(
        provider = provider, query = query, price = Money(BigDecimal(price), currency), airlines = listOf("Wizz Air"),
        legs = List(legs) { Leg(List(stops + 1) { i -> Segment("W6", "$flight$i", "SOF", "LTN", depart, arrive) }, minutes) },
    )

    fun snapshot(vararg offers: Offer, failed: Int = 0, at: String = "2026-11-15T07:00:00Z") =
        Snapshot("t1", Instant.parse(at), offers.toList(), queriesRun = 4, queriesFailed = failed)
}
