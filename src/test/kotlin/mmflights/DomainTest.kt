package mmflights

import mmflights.Fixtures.offer
import mmflights.Fixtures.range
import mmflights.Fixtures.snapshot
import mmflights.Fixtures.tracker
import mmflights.domain.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.*

class QueriesTest {
    private val today = LocalDate.parse("2026-11-15")

    @Test fun `return trip expands dates x stays x routes`() {
        val e = tracker().expand(today)
        // 2 departure days × 2 stay lengths × 2 destinations
        assertEquals(8, e.queries.size)
        assertFalse(e.truncated)
        assertTrue(e.queries.all { q -> q.returnDate!!.toEpochDay() - q.departDate.toEpochDay() in 7..8 })
    }

    @Test fun `return window intersects with stay`() {
        val trip = Trip.Return(range("2026-12-18", "2026-12-20"), range("2026-12-26", "2026-12-26"), StayRange(6, 8))
        val pairs = trip.datePairs(today)
        assertEquals(setOf("2026-12-18", "2026-12-19", "2026-12-20"), pairs.map { it.first.toString() }.toSet())
        assertTrue(pairs.all { it.second == LocalDate.parse("2026-12-26") })
    }

    @Test fun `past departure dates are skipped`() {
        val trip = Trip.OneWay(range("2026-11-10", "2026-11-16"))
        assertEquals(listOf("2026-11-15", "2026-11-16"), trip.datePairs(today).map { it.first.toString() })
    }

    @Test fun `truncation keeps every route covered`() {
        val e = tracker(maxQueries = 3).expand(today)
        assertTrue(e.truncated)
        assertEquals(setOf("LTN", "STN"), e.queries.map { it.destination }.toSet())
    }
}

class RankingTest {
    @Test fun `sorts, dedupes across providers and respects stop limit`() {
        val offers = listOf(offer("300"), offer("250", provider = "a"), offer("240", provider = "b"), offer("100", "9", stops = 2), offer("280", "7"))
        val (ranked, _) = rankOffers(tracker(maxStops = 1), offers)
        assertEquals(listOf("240", "280"), ranked.map { it.price.amount.toPlainString() })
        assertEquals("b", ranked.first().provider)
    }

    @Test fun `falls back to the dominant currency`() {
        val (ranked, warnings) = rankOffers(tracker(), listOf(offer("10", currency = "GBP"), offer("20", "2", currency = "GBP"), offer("5", "3", currency = "USD")))
        assertEquals(listOf("GBP", "GBP"), ranked.map { it.price.currency })
        assertEquals(2, warnings.size)
    }
}

class ChangesTest {
    private val p = Fixtures.policy

    @Test fun `first result is reported`() =
        assertIs<PriceChange.FirstSeen>(detectChange(null, snapshot(offer("100")), p))

    @Test fun `drop and rise`() {
        assertIs<PriceChange.Dropped>(detectChange(snapshot(offer("100")), snapshot(offer("90")), p))
        assertIs<PriceChange.Rose>(detectChange(snapshot(offer("100")), snapshot(offer("110")), p))
    }

    @Test fun `small moves are ignored`() {
        val strict = p.copy(minChangePercent = BigDecimal(5))
        assertNull(detectChange(snapshot(offer("100")), snapshot(offer("97")), strict))
        assertNull(detectChange(snapshot(offer("100")), snapshot(offer("100.50")), p)) // < minChangeAmount
    }

    @Test fun `drops-only policy ignores rises`() =
        assertNull(detectChange(snapshot(offer("100")), snapshot(offer("150")), p.copy(alertOn = AlertOn.DROPS_ONLY)))

    @Test fun `total provider failure is not a disappearance`() {
        assertNull(detectChange(snapshot(offer("100")), snapshot(failed = 4), p))
        assertIs<PriceChange.Disappeared>(detectChange(snapshot(offer("100")), snapshot(), p))
    }

    @Test fun `ceiling filters alerts`() {
        val t = tracker(maxPrice = BigDecimal(95))
        assertTrue(PriceChange.Dropped(offer("100"), offer("90")).respectsCeiling(t))
        assertFalse(PriceChange.Dropped(offer("120"), offer("100")).respectsCeiling(t))
    }

    @Test fun `blind snapshots keep the last good one`() {
        val s = TrackerState().record(snapshot(offer("100"))).record(snapshot(failed = 4, at = "2026-11-15T10:00:00Z"))
        assertEquals("100", s.last!!.best!!.price.amount.toPlainString())
        assertEquals(2, s.history.size)
        assertEquals("100", s.lowestEver!!.price!!.amount.toPlainString())
    }
}

class ScheduleTest {
    private val zone = ZoneId.of("Europe/Sofia") // UTC+2 in November

    @Test fun `later today`() =
        assertEquals(Instant.parse("2026-11-15T07:00:00Z"), nextDailyRun(Instant.parse("2026-11-15T05:00:00Z"), LocalTime.of(9, 0), zone))

    @Test fun `tomorrow when already past`() =
        assertEquals(Instant.parse("2026-11-16T07:00:00Z"), nextDailyRun(Instant.parse("2026-11-15T07:00:00Z"), LocalTime.of(9, 0), zone))
}

class MessagesTest {
    @Test fun `summary mentions price, trend and alternatives`() {
        val t = tracker()
        val prev = snapshot(offer("320"))
        val now = snapshot(offer("300"), offer("310", "5"))
        val text = Messages.dailySummary(t, now, prev, TrackerState().record(prev).record(now))
        assertContains(text, "Cheapest: 300.00 EUR")
        assertContains(text, "▼ 20.00 EUR")
        assertContains(text, "2. 310.00 EUR")
    }

    @Test fun `chunking respects the limit and keeps all lines`() {
        val text = (1..50).joinToString("\n") { "line number $it" }
        val parts = Messages.chunk(text, 100)
        assertTrue(parts.all { it.length <= 100 })
        assertEquals(text.lines(), parts.flatMap { it.lines() })
    }
}
