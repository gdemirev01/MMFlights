package mmflights.domain

import arrow.core.NonEmptyList
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// ---------------------------------------------------------------------------------------------
// Search configuration (what the user asked us to track)
// ---------------------------------------------------------------------------------------------

@Serializable
enum class Cabin { ECONOMY, PREMIUM_ECONOMY, BUSINESS, FIRST }

@Serializable
data class Passengers(
    val adults: Int,
    /** Ages (2..17) of each child passenger. */
    val childAges: List<Int> = emptyList(),
    /** Infants under 2 travelling on an adult's lap. */
    val infantsOnLap: Int = 0,
) {
    val total: Int get() = adults + childAges.size + infantsOnLap
}

data class DateRange(val from: LocalDate, val to: LocalDate) {
    fun days(): List<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
    operator fun contains(d: LocalDate): Boolean = !d.isBefore(from) && !d.isAfter(to)
}

data class StayRange(val minNights: Int, val maxNights: Int)

sealed interface Trip {
    /** The last date on which the trip could still start; after it, there is nothing to track. */
    val lastDeparture: LocalDate get() = when (this) {
        is OneWay -> departure.to
        is Return -> departure.to
    }

    data class OneWay(val departure: DateRange) : Trip

    /** Return trip: return dates come from [returnWindow], [stay], or both (intersection). */
    data class Return(val departure: DateRange, val returnWindow: DateRange?, val stay: StayRange?) : Trip
}

enum class AlertOn { ANY_CHANGE, DROPS_ONLY }

/** "Adequate flight time": limits applied to every leg (outbound and return). Null = no limit. */
data class FlightTimes(
    /** Door-to-door per direction, including layovers. */
    val maxDuration: Duration? = null,
    /** Earliest acceptable local departure time. */
    val departAfter: LocalTime? = null,
    /** Latest acceptable local arrival time, same day as departure. */
    val arriveBefore: LocalTime? = null,
) {
    val isUnrestricted: Boolean get() = maxDuration == null && departAfter == null && arriveBefore == null
}

data class NotifyPolicy(
    val onChange: Boolean,
    val alertOn: AlertOn,
    val minChangePercent: BigDecimal,
    val minChangeAmount: BigDecimal,
    /** Local time of the daily summary, or null to disable it. */
    val dailyAt: LocalTime?,
    val zone: ZoneId,
)

data class Tracker(
    val id: String,
    val name: String,
    val origins: NonEmptyList<String>,
    val destinations: NonEmptyList<String>,
    val trip: Trip,
    val passengers: Passengers,
    val cabin: Cabin,
    val maxStops: Int?,
    val times: FlightTimes,
    val maxPrice: BigDecimal?,
    val currency: String,
    val notify: NotifyPolicy,
    val maxQueries: Int,
    val topN: Int,
    /** Cabin bags (trolleys) whose fees should be included in the price. Google Flights only. */
    val carryOnBags: Int = 0,
)

// ---------------------------------------------------------------------------------------------
// Search inputs / outputs (what providers consume and produce)
// ---------------------------------------------------------------------------------------------

@Serializable
data class SearchQuery(
    val origin: String,
    val destination: String,
    @Serializable(LocalDateSerializer::class) val departDate: LocalDate,
    @Serializable(LocalDateSerializer::class) val returnDate: LocalDate? = null,
    val passengers: Passengers,
    val cabin: Cabin,
    val maxStops: Int? = null,
    val currency: String,
    val carryOnBags: Int = 0,
) {
    /** What the price depends on besides route and dates; prices are only comparable within one basis. */
    val basis: String
        get() = "${passengers.adults}/${passengers.childAges}/${passengers.infantsOnLap}|$cabin|bags=$carryOnBags|$currency"

    val label: String
        get() = "$origin→$destination $departDate" + (returnDate?.let { " ⇄ $it" } ?: "")
}

@Serializable
data class Money(@Serializable(BigDecimalSerializer::class) val amount: BigDecimal, val currency: String) :
    Comparable<Money> {
    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)
    fun show(): String = "${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()} $currency"
}

@Serializable
data class Segment(
    val carrier: String,
    val flightNumber: String,
    val from: String,
    val to: String,
    /** Local date-time as returned by the provider (ISO-8601, no zone). */
    val departAt: String,
    val arriveAt: String,
    /** Operating/marketing airline name, e.g. "Wizz Air", when the provider gives it. */
    val airline: String? = null,
)

@Serializable
data class Leg(
    val segments: List<Segment>,
    /** Total minutes including layovers, when the provider reports it. */
    val durationMinutes: Int? = null,
) {
    val stops: Int get() = (segments.size - 1).coerceAtLeast(0)
    val departAt: String get() = segments.firstOrNull()?.departAt.orEmpty()
    val arriveAt: String get() = segments.lastOrNull()?.arriveAt.orEmpty()
}

@Serializable
data class Offer(
    val provider: String,
    val query: SearchQuery,
    val price: Money,
    val airlines: List<String>,
    /** Outbound first, then return (when the provider gives it). */
    val legs: List<Leg>,
    val bookingUrl: String? = null,
    /** Provider-specific token to fetch more details later (e.g. Google's return flights). */
    val continuation: String? = null,
) {
    val maxStops: Int get() = legs.maxOfOrNull { it.stops } ?: 0
    val totalMinutes: Int get() = legs.sumOf { it.durationMinutes ?: 0 }

    /** True when the provider hasn't told us the return flight yet. */
    val missingReturn: Boolean get() = query.returnDate != null && legs.size < 2

    /** Identity of the itinerary regardless of which provider found it. */
    val itineraryKey: String
        get() = query.label + "|" + legs.joinToString("/") { leg ->
            leg.segments.joinToString(",") { "${it.carrier}${it.flightNumber}@${it.departAt}" }
        }
}

// ---------------------------------------------------------------------------------------------
// Results over time
// ---------------------------------------------------------------------------------------------

@Serializable
data class Snapshot(
    val trackerId: String,
    @Serializable(InstantSerializer::class) val takenAt: Instant,
    /** Cheapest offers, ascending by price. */
    val cheapest: List<Offer>,
    val queriesRun: Int,
    val queriesFailed: Int,
    val warnings: List<String> = emptyList(),
) {
    val best: Offer? get() = cheapest.firstOrNull()

    /** True when every single provider call failed – we learned nothing about prices. */
    val isBlind: Boolean get() = queriesRun > 0 && queriesFailed >= queriesRun
}

@Serializable
data class PricePoint(@Serializable(InstantSerializer::class) val at: Instant, val price: Money?)

@Serializable
data class TrackerState(
    val last: Snapshot? = null,
    val history: List<PricePoint> = emptyList(),
    /** Local date (tracker time zone) of the last daily update that was delivered. */
    val lastDailyDate: String? = null,
) {
    val lowestEver: PricePoint?
        get() = history.filter { it.price != null }.minByOrNull { it.price!!.amount }
}

sealed interface PriceChange {
    data class FirstSeen(val offer: Offer) : PriceChange
    data class Dropped(val previous: Offer, val current: Offer) : PriceChange
    data class Rose(val previous: Offer, val current: Offer) : PriceChange
    data class Disappeared(val previous: Offer) : PriceChange
}

/** Why a check was run – decides what gets sent. */
enum class Trigger { POLL, DAILY, MANUAL }

/** Pure check of a leg against the tracker's time limits. Unknown data never disqualifies. */
fun Leg.fits(t: FlightTimes): Boolean {
    fun parse(s: String) = runCatching { LocalDateTime.parse(s.take(16)) }.getOrNull()
    val dep = parse(departAt)
    val arr = parse(arriveAt)
    val durationOk = t.maxDuration == null || durationMinutes == null || durationMinutes <= t.maxDuration.toMinutes()
    val departOk = t.departAfter == null || dep == null || !dep.toLocalTime().isBefore(t.departAfter)
    val arriveOk = t.arriveBefore == null || dep == null || arr == null ||
        (arr.toLocalDate() == dep.toLocalDate() && !arr.toLocalTime().isAfter(t.arriveBefore))
    return durationOk && departOk && arriveOk
}

fun Offer.fits(t: FlightTimes): Boolean = legs.all { it.fits(t) }

fun minutesText(m: Int): String = "${m / 60}h" + (if (m % 60 != 0) "%02dm".format(m % 60) else "")
