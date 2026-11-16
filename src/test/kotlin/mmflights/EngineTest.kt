package mmflights

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.test.runTest
import mmflights.app.Env
import mmflights.app.check
import mmflights.app.daily
import mmflights.domain.Offer
import mmflights.domain.SearchQuery
import mmflights.domain.Trigger
import mmflights.notify.Notifier
import mmflights.providers.FlightProvider
import mmflights.providers.ProviderError
import mmflights.store.InMemoryStore
import java.time.Instant
import kotlin.test.*

class EngineTest {
    private class FakeProvider(override val name: String, var price: String?) : FlightProvider {
        override suspend fun search(query: SearchQuery): Either<ProviderError, List<Offer>> =
            price?.let { listOf(Fixtures.offer(it, flight = query.label.hashCode().toString(), provider = name).copy(query = query)).right() }
                ?: ProviderError(name, "down").left()
    }

    private class Recorder : Notifier {
        override val name = "rec"
        val sent = mutableListOf<String>()
        override suspend fun send(message: mmflights.domain.Message): Either<String, Unit> = sent.add(message.plain).let { Unit.right() }
    }

    @Test fun `poll alerts only on changes, daily always summarises`() = runTest {
        val cheap = FakeProvider("cheap", "200")
        val pricey = FakeProvider("pricey", "250")
        val rec = Recorder()
        val env = Env(listOf(cheap, pricey), listOf(rec), InMemoryStore(), concurrency = 2, clock = { Instant.parse("2026-11-15T07:00:00Z") })
        val t = Fixtures.tracker()

        env.check(t, Trigger.POLL)
        assertEquals(1, rec.sent.size); assertContains(rec.sent.last(), "tracking started")

        env.check(t, Trigger.POLL)
        assertEquals(1, rec.sent.size, "no change → no message")

        cheap.price = "150"
        env.check(t, Trigger.POLL)
        assertEquals(2, rec.sent.size); assertContains(rec.sent.last(), "Price drop")

        cheap.price = null; pricey.price = null
        val outcome = env.check(t, Trigger.POLL)
        assertTrue(outcome.snapshot.isBlind)
        assertEquals(2, rec.sent.size, "outage is not reported as a price change")

        cheap.price = "150"
        env.check(t, Trigger.DAILY)
        assertEquals(3, rec.sent.size); assertContains(rec.sent.last(), "Daily update")
    }
}

class DailyTest {
    private class Counting(var price: String?) : FlightProvider {
        override val name = "p"
        var calls = 0
        override suspend fun search(query: SearchQuery): Either<ProviderError, List<Offer>> {
            calls++
            return price?.let { listOf(Fixtures.offer(it).copy(query = query)).right() } ?: ProviderError(name, "down").left()
        }
    }

    private class Inbox(var failing: Boolean = false) : Notifier {
        override val name = "inbox"
        val sent = mutableListOf<String>()
        override suspend fun send(message: mmflights.domain.Message): Either<String, Unit> =
            if (failing) "unreachable".left() else sent.add(message.plain).let { Unit.right() }
    }

    private var now = Instant.parse("2026-11-16T07:03:00Z") // 09:03 in Sofia
    private val provider = Counting("200")
    private val inbox = Inbox()
    private val env = Env(listOf(provider), listOf(inbox), InMemoryStore(), 1, clock = { now })
    private val tracker = Fixtures.tracker()

    @Test fun `backup runs on the same day send nothing and don't search`() = runTest {
        assertTrue(env.daily(tracker))
        assertEquals(1, inbox.sent.size)
        val searches = provider.calls

        now = Instant.parse("2026-11-16T07:33:00Z")
        assertTrue(env.daily(tracker))
        now = Instant.parse("2026-11-16T09:03:00Z")
        assertTrue(env.daily(tracker))
        assertEquals(1, inbox.sent.size)
        assertEquals(searches, provider.calls, "no extra searches")

        now = Instant.parse("2026-11-17T07:03:00Z")
        assertTrue(env.daily(tracker))
        assertEquals(2, inbox.sent.size, "next day sends again")
    }

    @Test fun `a failed search is retried by the next backup run`() = runTest {
        provider.price = null
        assertFalse(env.daily(tracker))
        provider.price = "200"
        now = Instant.parse("2026-11-16T07:33:00Z")
        assertTrue(env.daily(tracker))
        assertTrue(inbox.sent.last().contains("€200 total"))
    }

    @Test fun `an undelivered message is retried by the next backup run`() = runTest {
        inbox.failing = true
        assertFalse(env.daily(tracker))
        inbox.failing = false
        now = Instant.parse("2026-11-16T07:33:00Z")
        assertTrue(env.daily(tracker))
        assertEquals(1, inbox.sent.size)
    }

    @Test fun `console output alone doesn't count as delivered`() = runTest {
        val env2 = Env(listOf(provider), listOf(inbox, mmflights.notify.ConsoleNotifier), InMemoryStore(), 1, clock = { now })
        inbox.failing = true
        assertFalse(env2.daily(tracker))
    }

    @Test fun `stops after the trip dates have passed`() = runTest {
        now = Instant.parse("2026-12-20T07:03:00Z") // tracker departs 18-19 Dec
        assertTrue(env.daily(tracker))
        assertEquals(0, provider.calls)
        assertTrue(inbox.sent.isEmpty())
    }

    @Test fun `the day uses the tracker's time zone`() = runTest {
        now = Instant.parse("2026-11-16T21:30:00Z") // 23:30 Sofia, 16 Nov
        assertTrue(env.daily(tracker))
        now = Instant.parse("2026-11-16T22:30:00Z") // 00:30 Sofia, 17 Nov → new day
        assertTrue(env.daily(tracker))
        assertEquals(2, inbox.sent.size)
    }
}
