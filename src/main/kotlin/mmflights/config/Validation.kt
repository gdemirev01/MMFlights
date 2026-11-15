package mmflights.config

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.flatMap
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.raise.either
import arrow.core.raise.zipOrAccumulate
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import mmflights.domain.AlertOn
import mmflights.domain.Cabin
import mmflights.domain.DateRange
import mmflights.domain.FlightTimes
import mmflights.domain.NotifyPolicy
import mmflights.domain.Passengers
import mmflights.domain.StayRange
import mmflights.domain.Tracker
import mmflights.domain.Trip
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

typealias Errors = NonEmptyList<String>

/** Validates the whole config, reporting every problem at once instead of stopping at the first. */
fun RawConfig.validate(): Either<Errors, AppConfig> = either {
    zipOrAccumulate(
        { parseDuration("pollInterval", pollInterval).bind() },
        { check(concurrency in 1..32, "concurrency must be between 1 and 32").bind() },
        { currencyCode("currency", currency).bind() },
        { providers.validate().bind() },
        { notifiers.mapNotNull { it.toSettings().bind() } },
        { trackersOf(this@validate).bindNel() },
    ) { poll, _, _, provs, notifs, trackers ->
        AppConfig(poll, concurrency, dataDir, provs, notifs, trackers)
    }
}

private fun trackersOf(cfg: RawConfig): Either<Errors, List<Tracker>> = either {
    val dupes = cfg.trackers.groupBy { it.id }.filterValues { it.size > 1 }.keys
    zipOrAccumulate(
        { check(cfg.trackers.isNotEmpty(), "at least one tracker is required").bind() },
        { check(dupes.isEmpty(), "duplicate tracker ids: ${dupes.joinToString()}").bind() },
        {
            cfg.trackers
                .map { t -> t.validate(cfg.currency).mapLeft { errs -> errs.map { "tracker '${t.id}': $it" } } }
                .let { results ->
                    val errors = results.flatMap { it.leftOrNull().orEmpty() }.toNonEmptyListOrNull()
                    errors?.left() ?: results.mapNotNull { it.getOrNull() }.right()
                }
                .bindNel()
        },
    ) { _, _, trackers -> trackers }
}

fun RawTracker.validate(defaultCurrency: String): Either<Errors, Tracker> = either {
    zipOrAccumulate(
        { check(id.matches(Regex("[A-Za-z0-9_-]+")), "id may only contain letters, digits, '-' and '_'").bind() },
        { airports("origins", origins).bind() },
        { airports("destinations", destinations).bind() },
        { trip().bindNel() },
        { passengers.validate().bindNel() },
        { cabinOf(cabin).bind() },
        { notify.validate().bindNel() },
        { currencyCode("currency", currency ?: defaultCurrency).bind() },
        {
            zipOrAccumulate(
                { check(maxStops == null || maxStops in 0..3, "maxStops must be 0..3").bind() },
                { check(maxQueries in 1..500, "maxQueries must be 1..500").bind() },
                { check(topN in 1..20, "topN must be 1..20").bind() },
                { check(maxPrice == null || maxPrice > 0, "maxPrice must be positive").bind() },
                { times.validate().bindNel() },
            ) { _, _, _, _, ft -> ft }
        },
    ) { _, orig, dest, trip, pax, cab, policy, cur, flightTimes ->
        Tracker(
            id = id,
            name = name ?: "${orig.joinToString("/")} → ${dest.joinToString("/")}",
            origins = orig, destinations = dest, trip = trip, passengers = pax, cabin = cab,
            maxStops = maxStops, times = flightTimes, maxPrice = maxPrice?.toBigDecimal(), currency = cur,
            notify = policy, maxQueries = maxQueries, topN = topN,
        )
    }
}

private fun RawTracker.trip(): Either<Errors, Trip> = either {
    val dep = range("departure", departure).bind()
    if (oneWay) return@either Trip.OneWay(dep)
    zipOrAccumulate(
        { returnWindow?.let { range("return", it).bindNel() } },
        { stay?.let { s -> check(s.min in 0..s.max, "stay.min must be between 0 and stay.max").bind(); StayRange(s.min, s.max) } },
        { check(returnWindow != null || stay != null, "a return trip needs 'return' dates and/or 'stay' (or set oneWay: true)").bind() },
    ) { ret, st, _ -> Trip.Return(dep, ret, st) }
}

private fun range(field: String, r: RawDateRange): Either<Errors, DateRange> = either {
    zipOrAccumulate(
        { parseDate("$field.from", r.from).bind() },
        { parseDate("$field.to", r.to ?: r.from).bind() },
    ) { from, to -> from to to }
        .let { (from, to) ->
            check(!to.isBefore(from), "$field.to is before $field.from").mapLeft { nonEmptyListOf(it) }.bind()
            DateRange(from, to)
        }
}

private fun RawTimes.validate(): Either<Errors, FlightTimes> = either {
    zipOrAccumulate(
        { maxDuration?.let { Either.catch { kotlin.time.Duration.parse(it) }.map { d -> Duration.ofMinutes(d.inWholeMinutes) }
            .mapLeft { "times.maxDuration '$maxDuration' is not a duration (e.g. 4h, 5h 30m)" }.bind() } },
        { departAfter?.let { time("times.departAfter", it).bind() } },
        { arriveBefore?.let { time("times.arriveBefore", it).bind() } },
    ) { d, a, b -> FlightTimes(d, a, b) }
}

private fun time(field: String, s: String): Either<String, LocalTime> =
    Either.catch { LocalTime.parse(s) }.mapLeft { "$field '$s' must be HH:mm" }

private fun RawPassengers.validate(): Either<Errors, Passengers> = either {
    zipOrAccumulate(
        { check(adults in 1..9, "passengers.adults must be 1..9").bind() },
        { check(children.all { it in 2..17 }, "passengers.children are ages and must be 2..17 (use 'infants' for under 2)").bind() },
        { check(infants in 0..adults, "passengers.infants can't exceed adults").bind() },
        { check(adults + children.size + infants <= 9, "at most 9 passengers per search").bind() },
    ) { _, _, _, _ -> Passengers(adults, children, infants) }
}

private fun RawNotify.validate(): Either<Errors, NotifyPolicy> = either {
    zipOrAccumulate(
        {
            when (alertOn.lowercase()) {
                "any" -> AlertOn.ANY_CHANGE
                "drops" -> AlertOn.DROPS_ONLY
                else -> raise("notify.alertOn must be 'any' or 'drops'")
            }
        },
        { dailyAt?.let { Either.catch { LocalTime.parse(it) }.mapLeft { "notify.dailyAt must be HH:mm" }.bind() } },
        { Either.catch { ZoneId.of(timezone) }.mapLeft { "notify.timezone '$timezone' is not a valid zone id" }.bind() },
        { check(minChangePercent >= 0 && minChangeAmount >= 0, "notify thresholds must be >= 0").bind() },
    ) { alert, at, zone, _ ->
        NotifyPolicy(onChange, alert, BigDecimal.valueOf(minChangePercent), BigDecimal.valueOf(minChangeAmount), at, zone)
    }
}

private fun RawProviders.validate(): Either<String, List<ProviderSettings>> = listOfNotNull(
    duffel?.let { ProviderSettings.Duffel(it.token, it.baseUrl.trimEnd('/')) },
    serpapi?.let { ProviderSettings.SerpApi(it.apiKey, it.baseUrl.trimEnd('/'), it.language, it.country, it.returnDetails) },
    ProviderSettings.Demo.takeIf { demo },
).let { if (it.isEmpty()) "configure at least one provider (duffel, serpapi or demo: true)".left() else it.right() }

/** Right(null) = notifier left unconfigured on purpose (all its values empty) → skipped. */
private fun RawNotifier.toSettings(): Either<String, NotifierSettings?> = when (this) {
    is RawNotifier.Telegram -> NotifierSettings.Telegram(botToken, chatId).right()
    is RawNotifier.Slack -> NotifierSettings.Slack(webhookUrl).right()
    is RawNotifier.Discord -> NotifierSettings.Discord(webhookUrl).right()
    is RawNotifier.Console -> NotifierSettings.Console.right()
    is RawNotifier.Messenger -> if (pageAccessToken.isBlank() && recipients.isBlank()) Either.Right(null)
    else if (pageAccessToken.isBlank()) "messenger.pageAccessToken is empty".left()
    else recipients.split(',', ';', ' ', '\n').map(String::trim).filter(String::isNotEmpty).let { ids ->
        when {
            ids.any { !it.all(Char::isDigit) } -> "messenger.recipients must be numeric page-scoped IDs".left()
            else -> NotifierSettings.Messenger(pageAccessToken, ids, graphVersion).right()
        }
    }
}

// --- small field parsers ------------------------------------------------------------------------

private val IATA = Regex("[A-Z]{3}")

private fun airports(field: String, codes: List<String>): Either<String, NonEmptyList<String>> {
    val upper = codes.map { it.trim().uppercase() }
    val bad = upper.filterNot { it.matches(IATA) }
    return when {
        bad.isNotEmpty() -> "$field: not 3-letter IATA codes: ${bad.joinToString()}".left()
        else -> upper.distinct().toNonEmptyListOrNull()?.right() ?: "$field must not be empty".left()
    }
}

private fun cabinOf(s: String): Either<String, Cabin> =
    Cabin.entries.firstOrNull { it.name.equals(s.replace('-', '_').replace(' ', '_'), ignoreCase = true) }?.right()
        ?: "cabin must be one of ${Cabin.entries.joinToString { it.name.lowercase() }}".left()

private fun currencyCode(field: String, s: String): Either<String, String> =
    s.uppercase().takeIf { it.matches(IATA) }?.right() ?: "$field must be a 3-letter ISO currency code".left()

private fun parseDate(field: String, s: String): Either<String, LocalDate> =
    Either.catch { LocalDate.parse(s) }.mapLeft { "$field '$s' is not a date (YYYY-MM-DD)" }

fun parseDuration(field: String, s: String): Either<String, Duration> =
    Either.catch { kotlin.time.Duration.parse(s).let { Duration.ofMillis(it.inWholeMilliseconds) } }
        .mapLeft { "$field '$s' is not a duration (e.g. 30m, 3h, 1h 30m)" }
        .flatMap { if (it < Duration.ofMinutes(5)) "$field must be at least 5m".left() else it.right() }

private fun check(cond: Boolean, msg: String): Either<String, Unit> = if (cond) Unit.right() else msg.left()
