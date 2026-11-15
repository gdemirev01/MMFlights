package mmflights.providers

import arrow.core.Either
import arrow.core.right
import mmflights.domain.Leg
import mmflights.domain.Money
import mmflights.domain.Offer
import mmflights.domain.SearchQuery
import mmflights.domain.Segment
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.random.Random

/**
 * Fake airline prices for trying the tracker without API keys. Prices depend on the query and the
 * current day, so they are stable within a day and drift from one day to the next.
 */
class DemoProvider(private val today: () -> LocalDate = LocalDate::now) : FlightProvider {
    override val name = "demo"
    private val airlines = listOf("FR" to "Ryanair", "W6" to "Wizz Air", "LH" to "Lufthansa", "OS" to "Austrian", "FB" to "Bulgaria Air")

    override suspend fun search(query: SearchQuery): Either<ProviderError, List<Offer>> {
        val rnd = Random((query.label + today()).hashCode())
        return List(rnd.nextInt(2, 6)) { i ->
            val (code, airline) = airlines[rnd.nextInt(airlines.size)]
            val perPerson = 40 + rnd.nextInt(0, 260) + i * 7
            val trips = if (query.returnDate != null) 2 else 1
            val total = BigDecimal(perPerson * query.passengers.total * trips).setScale(2, RoundingMode.HALF_UP)
            fun leg(from: String, to: String, date: LocalDate): Leg {
                val depart = date.atTime(5 + rnd.nextInt(17), rnd.nextInt(4) * 15)
                val minutes = 80 + rnd.nextInt(0, 6) * 45
                return Leg(
                    listOf(Segment(code, "${100 + rnd.nextInt(900)}", from, to, depart.toString(), depart.plusMinutes(minutes.toLong()).toString(), airline)),
                    durationMinutes = minutes,
                )
            }
            Offer(
                provider = name,
                query = query,
                price = Money(total, query.currency),
                airlines = listOf(airline),
                legs = listOfNotNull(
                    leg(query.origin, query.destination, query.departDate),
                    query.returnDate?.let { leg(query.destination, query.origin, it) },
                ),
            )
        }.right()
    }
}
