package mmflights

import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.test.runTest
import mmflights.Fixtures.offer
import mmflights.Fixtures.tracker
import mmflights.app.Env
import mmflights.app.completeCheapest
import mmflights.domain.*
import mmflights.providers.FlightProvider
import mmflights.providers.ProviderError
import mmflights.store.InMemoryStore
import java.time.Duration
import java.time.LocalTime
import kotlin.test.*

class FlightTimesTest {
    private val limits = FlightTimes(Duration.ofHours(5), LocalTime.of(7, 0), LocalTime.of(23, 30))

    @Test fun `legs are checked against duration, departure and arrival limits`() {
        assertTrue(offer("1", depart = "2026-12-30T09:00", arrive = "2026-12-30T10:30", minutes = 90).fits(limits))
        assertFalse(offer("1", minutes = 400).fits(limits), "too long")
        assertFalse(offer("1", depart = "2026-12-30T05:30", arrive = "2026-12-30T07:00").fits(limits), "too early")
        assertFalse(offer("1", depart = "2026-12-30T22:30", arrive = "2026-12-31T00:10").fits(limits), "arrives after midnight")
        assertTrue(offer("1", minutes = null, depart = "2026-12-30T09:00", arrive = "2026-12-30T15:00").fits(limits), "unknown duration doesn't disqualify")
    }

    @Test fun `ranking applies time limits and prefers shorter flights at equal price`() {
        val offers = listOf(
            offer("100", "a", depart = "2026-12-30T05:00"),                                    // too early
            offer("150", "b", depart = "2026-12-30T09:00", arrive = "2026-12-30T14:00", minutes = 300),
            offer("150", "c", depart = "2026-12-30T10:00", arrive = "2026-12-30T11:30", minutes = 90),
        )
        val (ranked, _) = rankOffers(tracker(times = limits), offers)
        assertEquals(listOf("c0", "b0"), ranked.map { it.legs[0].segments[0].flightNumber })
    }
}

class CompletionTest {
    /** Simulates Google: outbound-only offers, return legs loaded on demand. */
    private class TwoStep(private val returns: Map<String, List<Offer>>) : FlightProvider {
        override val name = "g"
        var calls = 0
        override suspend fun search(query: SearchQuery): Either<ProviderError, List<Offer>> = emptyList<Offer>().right()
        override suspend fun complete(offer: Offer, accept: (Offer) -> Boolean): Either<ProviderError, Offer?> {
            calls++
            return returns[offer.legs[0].segments[0].flightNumber].orEmpty().filter(accept).minByOrNull { it.price.amount }.right()
        }
    }

    private fun outbound(price: String, flight: String) = offer(price, flight, provider = "g", depart = "2026-12-30T09:00", arrive = "2026-12-30T10:30")
    private fun full(price: String, flight: String, returnDepart: String) =
        outbound(price, flight).let { it.copy(legs = it.legs + it.legs[0].copy(segments = it.legs[0].segments.map { s -> s.copy(departAt = returnDepart, arriveAt = returnDepart) })) }

    @Test fun `cheapest is completed, and dropped when its only return is inadequate`() = runTest {
        val limits = FlightTimes(departAfter = LocalTime.of(7, 0))
        val p = TwoStep(mapOf(
            "a0" to listOf(full("100", "a", "2027-01-03T05:00")),   // return too early → reject
            "b0" to listOf(full("130", "b", "2027-01-03T12:00")),
        ))
        val env = Env(listOf(p), emptyList(), InMemoryStore(), 1)
        val (result, warnings) = env.completeCheapest(tracker(times = limits), listOf(outbound("100", "a"), outbound("120", "b"), outbound("125", "c")))

        assertTrue(warnings.isEmpty())
        // a dropped; b completed at 130 → c (125, incomplete) is now cheapest → tried, has no returns → dropped
        assertEquals(listOf("130"), result.map { it.price.amount.toPlainString() })
        assertFalse(result.first().missingReturn)
        assertEquals(3, p.calls)
    }
}
