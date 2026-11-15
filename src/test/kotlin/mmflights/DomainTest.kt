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
    @Test fun `summary shows total, per-person price, trend and alternatives`() {
        val t = tracker(maxStops = 1, times = FlightTimes(java.time.Duration.ofHours(5), LocalTime.of(7, 0), LocalTime.of(23, 30)))
            .copy(carryOnBags = 3) // 2 adults + 1 child = 3 people
        val prev = snapshot(offer("320"))
        val now = snapshot(offer("300"), offer("310", "5"))
        val text = Messages.dailySummary(t, now, prev, TrackerState().record(prev).record(now)).plain
        assertContains(text, "💶 €300 total\n👤 €100 per person")
        assertContains(text, "📉 €20 cheaper than last check\n🎉 lowest price so far")
        assertContains(text, "🛫 Outbound · Fri 18 Dec\n06:10 SOF → 07:55 LTN · 2h45m\nW6 1230 · direct")
        assertContains(text, "2. €310 · €103.33 pp\nWizz Air · direct · 06:10 · 2h45m")
        assertContains(text, "🔎 ≤1 stop · max 5h · 07:00–23:30")
        // every line fits a phone screen without wrapping
        text.lines().filterNot { it.startsWith("🔗") }.forEach { assertTrue(it.length <= 40, "too long: $it") }
    }

    @Test fun `money formatting`() {
        assertEquals("€1,108", Messages.money(Money(java.math.BigDecimal("1108.00"), "EUR")))
        assertEquals("€459.25", Messages.money(Money(java.math.BigDecimal("459.25"), "EUR")))
        assertEquals("1,200 BGN", Messages.money(Money(java.math.BigDecimal("1200"), "BGN")))
    }

    @Test fun `renderers apply each chat's markup and escaping`() {
        val m = message { line(text("a < b & "), bold("Bold"), text(" "), italic("it"), text(" "), link("Book", "https://x.y/?a=1&b=2")) }
        assertEquals("a &lt; b &amp; <b>Bold</b> <i>it</i> <a href=\"https://x.y/?a=1&amp;b=2\">Book</a>", TelegramHtml.render(m))
        assertEquals("a &lt; b &amp; *Bold* _it_ <https://x.y/?a=1&b=2|Book>", SlackMrkdwn.render(m))
        assertEquals("a < b & **Bold** *it* [Book](<https://x.y/?a=1&b=2>)", DiscordMarkdown.render(m))
        assertEquals("a < b & Bold it Book: https://x.y/?a=1&b=2", m.plain)
    }

    @Test fun `chunking respects the limit and keeps all lines`() {
        val text = (1..50).joinToString("\n") { "line number $it" }
        val parts = Messages.chunk(text, 100)
        assertTrue(parts.all { it.length <= 100 })
        assertEquals(text.lines(), parts.flatMap { it.lines() })
    }
}
