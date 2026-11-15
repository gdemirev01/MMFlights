package mmflights

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.test.runTest
import mmflights.app.Env
import mmflights.app.check
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
        assertEquals(3, rec.sent.size); assertContains(rec.sent.last(), "daily update")
    }
}
