package mmflights.app

import arrow.core.Either
import arrow.fx.coroutines.parMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mmflights.domain.Message
import mmflights.domain.Messages
import mmflights.domain.Offer
import mmflights.domain.fits
import mmflights.domain.Snapshot
import mmflights.domain.Tracker
import mmflights.domain.TrackerState
import mmflights.domain.Trigger
import mmflights.domain.delayUntil
import mmflights.domain.detectChange
import mmflights.domain.expand
import mmflights.domain.nextDailyRun
import mmflights.domain.rankOffers
import mmflights.domain.record
import mmflights.domain.respectsCeiling
import mmflights.notify.Notifier
import mmflights.notify.broadcast
import mmflights.providers.FlightProvider
import mmflights.store.StateStore
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/** Everything effectful the engine needs, passed explicitly (no globals, easy to fake in tests). */
data class Env(
    val providers: List<FlightProvider>,
    val notifiers: List<Notifier>,
    val store: StateStore,
    val concurrency: Int,
    val clock: () -> Instant = Instant::now,
)

data class Outcome(val snapshot: Snapshot, val messages: List<Message>, val deliveryFailures: List<String>)

private val log = LoggerFactory.getLogger("mmflights")

/** Ask every provider about every query (in parallel, bounded) and keep the cheapest offers. */
suspend fun Env.search(tracker: Tracker): Snapshot {
    val now = clock()
    val plan = tracker.expand(today = now.atZone(tracker.notify.zone).toLocalDate())
    val jobs = plan.queries.flatMap { q -> providers.map { p -> p to q } }
    log.info("[{}] searching {} queries × {} providers", tracker.id, plan.queries.size, providers.size)

    val results = jobs.parMap(concurrency = concurrency) { (provider, query) ->
        provider.search(query).onLeft { log.warn("[{}] {} {}: {}", tracker.id, it.provider, query.label, it.message) }
    }
    val failures = results.mapNotNull(Either<*, *>::leftOrNull)
    val (initial, rankWarnings) = rankOffers(tracker, results.flatMap { it.getOrNull().orEmpty() })
    val (ranked, completionWarnings) = completeCheapest(tracker, initial)

    val warnings = buildList {
        if (plan.truncated) add("Only ${plan.queries.size} of ${plan.totalCombinations} date/route combinations searched (raise maxQueries to cover all).")
        addAll(rankWarnings)
        addAll(completionWarnings)
        if (failures.isNotEmpty()) add("${failures.size} provider call(s) failed, e.g. ${failures.first()}")
    }
    return Snapshot(tracker.id, now, ranked, jobs.size, failures.size, warnings)
}

/**
 * Some providers (Google Flights) list return trips by outbound flight only. Load the return leg
 * for the cheapest offer, apply the same stop/time limits to it, and re-rank. If no acceptable
 * return exists, that offer is dropped and the next one is tried (at most [budget] extra calls).
 */
suspend fun Env.completeCheapest(tracker: Tracker, ranked: List<Offer>, budget: Int = 3): Pair<List<Offer>, List<String>> {
    val top = ranked.firstOrNull()
    if (top == null || !top.missingReturn || budget == 0) return ranked to emptyList()
    val provider = providers.firstOrNull { it.name == top.provider } ?: return ranked to emptyList()
    val accept = { o: Offer -> o.fits(tracker.times) && (tracker.maxStops == null || o.maxStops <= tracker.maxStops) }

    return when (val result = provider.complete(top, accept)) {
        is Either.Left -> ranked to listOf("Couldn't load return flights: ${result.value.message}")
        is Either.Right -> {
            val rest = ranked.drop(1)
            val next = result.value?.let { completed ->
                (rest + completed).sortedWith(compareBy<Offer> { it.price.amount }.thenBy { it.totalMinutes })
            } ?: rest
            completeCheapest(tracker, next, budget - 1)
        }
    }
}

/** Pure decision: which messages a check produces. */
fun messagesFor(tracker: Tracker, trigger: Trigger, before: TrackerState, after: TrackerState, snapshot: Snapshot): List<Message> =
    when (trigger) {
        Trigger.DAILY, Trigger.MANUAL -> listOf(Messages.dailySummary(tracker, snapshot, before.last, after))
        Trigger.POLL -> detectChange(before.last, snapshot, tracker.notify)
            ?.takeIf { tracker.notify.onChange && it.respectsCeiling(tracker) }
            ?.let { listOf(Messages.changeAlert(tracker, it, after)) }
            .orEmpty()
    }

/** One full cycle: search → persist → decide → notify. */
suspend fun Env.check(tracker: Tracker, trigger: Trigger): Outcome {
    val before = store.load(tracker.id)
    val snapshot = search(tracker)
    val after = before.record(snapshot)
    store.save(tracker.id, after).onLeft { log.error("[{}] could not save state", tracker.id, it) }
    val messages = messagesFor(tracker, trigger, before, after, snapshot)
    val failures = messages.flatMap { notifiers.broadcast(it) }
    failures.forEach { log.error("[{}] notification failed: {}", tracker.id, it) }
    log.info("[{}] best={} sent={} ({})", tracker.id, snapshot.best?.price?.show() ?: "none", messages.size, trigger)
    return Outcome(snapshot, messages, failures)
}

/**
 * Long-running mode: for each tracker, poll every [pollInterval] (alerting on changes) and send a
 * summary every day at its configured local time. A mutex per tracker keeps runs from overlapping.
 */
suspend fun Env.runForever(trackers: List<Tracker>, pollInterval: Duration): Unit = coroutineScope {
    trackers.forEach { tracker ->
        val lock = Mutex()
        suspend fun guarded(trigger: Trigger) = lock.withLock {
            Either.catch { check(tracker, trigger) }.onLeft { log.error("[{}] check failed", tracker.id, it) }
        }
        if (tracker.notify.onChange) launch {
            while (isActive) {
                guarded(Trigger.POLL)
                delay(pollInterval.toMillis())
            }
        }
        tracker.notify.dailyAt?.let { at ->
            launch {
                while (isActive) {
                    val next = nextDailyRun(clock(), at, tracker.notify.zone)
                    log.info("[{}] next daily summary at {}", tracker.id, next.atZone(tracker.notify.zone))
                    delay(delayUntil(clock(), next).toMillis())
                    guarded(Trigger.DAILY)
                }
            }
        }
    }
}
