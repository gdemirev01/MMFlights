package mmflights.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure construction of notifications as [Message]s; each chat app renders them its own way. */
object Messages {

    // --- message layouts ------------------------------------------------------------------------

    fun dailySummary(t: Tracker, s: Snapshot, previous: Snapshot?, state: TrackerState): Message = message {
        line(text("✈️ "), bold(t.name))
        line(text("📅 ${dates(t)}"))
        line(text("👥 ${people(t.passengers)}" + (if (t.cabin != Cabin.ECONOMY) " · ${cabin(t.cabin)}" else "") + bags(t)))
        blank()
        val best = s.best
        if (best == null) {
            line(text(if (s.isBlind) "❌ The flight search failed this time — it will retry tomorrow."
                      else "😕 No flights match your filters right now."))
        } else {
            price(best, t)
            trend(best, previous?.best?.takeIf { previous.comparableTo(s) }, state, t).forEach { line(text(it)) }
            if (!previous.comparableTo(s)) line(italic("🔄 Search settings changed, so price history starts fresh today."))
            blank()
            itinerary(best)
            best.bookingUrl?.let { line(text("🔗 "), link("Open in Google Flights", it)) }
            alternatives(s.cheapest.drop(1), best, t)
        }
        blank()
        footer(t, s)
    }

    fun changeAlert(t: Tracker, change: PriceChange, state: TrackerState): Message = message {
        when (change) {
            is PriceChange.FirstSeen -> {
                line(text("🆕 "), bold(t.name), text(" — tracking started"))
                price(change.offer, t)
            }
            is PriceChange.Dropped -> {
                line(text("📉 "), bold("Price drop: ${t.name}"))
                line(text("Now "), bold(money(change.current.price)), text(" (${money(perPerson(change.current, t))} per person)"))
                line(text("${money(diff(change))} cheaper than before (was ${money(change.previous.price)})"))
            }
            is PriceChange.Rose -> {
                line(text("📈 "), bold("Price went up: ${t.name}"))
                line(text("Now "), bold(money(change.current.price)), text(" (${money(perPerson(change.current, t))} per person)"))
                line(text("${money(diff(change))} more than before (was ${money(change.previous.price)})"))
            }
            is PriceChange.Disappeared -> {
                line(text("⚠️ "), bold(t.name))
                line(text("No flights match your filters any more (last price ${money(change.previous.price)})."))
            }
        }
        val offer = when (change) {
            is PriceChange.FirstSeen -> change.offer
            is PriceChange.Dropped -> change.current
            is PriceChange.Rose -> change.current
            is PriceChange.Disappeared -> null
        }
        offer?.let { o ->
            state.lowestEver?.price?.takeIf { it.amount < o.price.amount }?.let { line(text("Lowest seen so far: ${money(it)}")) }
            blank()
            itinerary(o)
            o.bookingUrl?.let { line(text("🔗 "), link("Open in Google Flights", it)) }
        }
    }

    // --- building blocks ------------------------------------------------------------------------

    private fun MessageBuilder.price(o: Offer, t: Tracker) {
        line(text("💶 "), bold("${money(o.price)} total"))
        line(text("👤 ${money(perPerson(o, t))} per person"))
    }

    private fun trend(best: Offer, prev: Offer?, state: TrackerState, t: Tracker): List<String> =
        buildList {
            if (prev != null && prev.price.currency == best.price.currency) {
                val d = best.price.amount - prev.price.amount
                add(when (d.signum()) {
                    -1 -> "📉 ${money(Money(d.abs(), best.price.currency))} cheaper than last check"
                    1 -> "📈 ${money(Money(d, best.price.currency))} more than last check"
                    else -> "➖ Same price as last check"
                })
            }
            val lowest = state.lowestEver?.price
            when {
                lowest == null || state.history.count { it.price != null } < 2 -> {}
                best.price.amount <= lowest.amount -> add("🎉 lowest price so far")
                else -> add("⬇️ lowest seen ${money(lowest)}")
            }
            t.maxPrice?.let { target ->
                add(if (best.price.amount <= target) "✅ under your ${money(Money(target, best.price.currency))} target"
                    else "🎯 target ${money(Money(target, best.price.currency))}")
            }
        }


    /**
     * One compact block per direction; every line is short enough not to wrap on a phone:
     *   🛫 Outbound · Wed 30 Dec
     *   18:30 SOF → 18:55 BUD · 1h25m
     *   Ryanair FR 5571 · direct
     */
    private fun MessageBuilder.itinerary(o: Offer) {
        o.legs.forEachIndexed { i, leg ->
            if (leg.segments.isEmpty()) return@forEachIndexed
            val dep = parse(leg.departAt)
            val arr = parse(leg.arriveAt)
            val plusDays = if (dep != null && arr != null) arr.toLocalDate().toEpochDay() - dep.toLocalDate().toEpochDay() else 0
            if (i > 0) blank()
            line(text(if (i == 0) "🛫 " else "🛬 "), bold("${if (i == 0) "Outbound" else "Return"} · ${dep?.let { DAY.format(it) } ?: leg.departAt.take(10)}"))
            line(text(
                "${hm(dep)} ${leg.segments.first().from} → ${hm(arr)}${if (plusDays > 0) "⁺¹" else ""} ${leg.segments.last().to}" +
                    (leg.durationMinutes?.let { " · ${minutesText(it)}" } ?: "")
            ))
            val names = leg.segments.mapNotNull { it.airline }.distinct()
            val flights = leg.segments.joinToString(" + ") { seg ->
                val name = seg.airline?.takeIf { names.size > 1 || seg == leg.segments.first() }
                listOfNotNull(name, "${seg.carrier} ${seg.flightNumber}").joinToString(" ")
            }
            val via = leg.segments.dropLast(1).joinToString(", ") { it.to }
            line(text(flights + " · " + if (leg.stops == 0) "direct" else "${stopsText(leg.stops)} ($via)"))
        }
        if (o.missingReturn) {
            blank()
            line(text("🛬 "), bold("Return · ${DAY.format(o.query.returnDate!!)}"))
            line(italic("Flight details not loaded"))
        }
    }

    /**
     * Two short lines per option:
     *   2. €1,340 · €335 pp
     *   Wizz Air · direct · 14:35 · 1h25m
     */
    private fun MessageBuilder.alternatives(others: List<Offer>, best: Offer, t: Tracker) {
        if (others.isEmpty()) return
        val showDates = others.any { it.query.departDate != best.query.departDate || it.query.returnDate != best.query.returnDate } ||
            t.origins.size + t.destinations.size > 2
        blank()
        line(bold("Other options"))
        others.forEachIndexed { i, o ->
            val out = o.legs.firstOrNull()
            line(text("${i + 2}. "), bold(money(o.price)), text(" · ${money(perPerson(o, t))} pp"))
            line(text(listOfNotNull(
                o.airlines.joinToString(" + "),
                stopsText(o.maxStops),
                out?.let { hm(parse(it.departAt)) },
                out?.durationMinutes?.let(::minutesText),
            ).joinToString(" · ")))
            if (showDates) line(text(routeAndDates(o.query)))
        }
    }

    private fun MessageBuilder.footer(t: Tracker, s: Snapshot) {
        val filters = with(t.times) {
            listOfNotNull(
                t.maxStops?.let { if (it == 0) "direct" else "≤$it stop${if (it > 1) "s" else ""}" },
                maxDuration?.let { "max ${minutesText(it.toMinutes().toInt())}" },
                when {
                    departAfter != null && arriveBefore != null -> "$departAfter–$arriveBefore"
                    departAfter != null -> "after $departAfter"
                    arriveBefore != null -> "land by $arriveBefore"
                    else -> null
                },
            )
        }
        if (filters.isNotEmpty()) line(italic("🔎 ${filters.joinToString(" · ")}"))
        if (t.carryOnBags > 0) line(italic("🧳 Bag fees may not be included"))
        line(italic("Daily update · ${TIMESTAMP.format(s.takenAt.atZone(t.notify.zone))}"))
        s.warnings.take(2).forEach { line(italic("⚠️ $it")) }
    }

    // --- formatting helpers ---------------------------------------------------------------------

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val TIMESTAMP = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)
    private val SYMBOLS = mapOf("EUR" to "€", "USD" to "$", "GBP" to "£")

    /** "€1,108", "€459.25", "1,200 BGN". */
    fun money(m: Money): String {
        val scaled = m.amount.setScale(2, RoundingMode.HALF_UP)
        val pattern = if (scaled.stripTrailingZeros().scale() <= 0) "#,##0" else "#,##0.00"
        val number = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(scaled)
        return SYMBOLS[m.currency]?.let { "$it$number" } ?: "$number ${m.currency}"
    }

    fun perPerson(o: Offer, t: Tracker): Money =
        Money(o.price.amount.divide(BigDecimal(t.passengers.total.coerceAtLeast(1)), 2, RoundingMode.HALF_UP), o.price.currency)

    private fun diff(c: PriceChange): Money = when (c) {
        is PriceChange.Dropped -> Money((c.previous.price.amount - c.current.price.amount).abs(), c.current.price.currency)
        is PriceChange.Rose -> Money((c.current.price.amount - c.previous.price.amount).abs(), c.current.price.currency)
        else -> error("no diff")
    }

    private fun dates(t: Tracker): String {
        fun span(r: DateRange) = if (r.from == r.to) SHORT.format(r.from) else "${SHORT.format(r.from)}–${SHORT.format(r.to)}"
        return when (val trip = t.trip) {
            is Trip.OneWay -> "${span(trip.departure)} (one-way)"
            is Trip.Return -> listOfNotNull(
                span(trip.departure) + (trip.returnWindow?.let { " → ${span(it)}" } ?: ""),
                trip.stay?.let { "${it.minNights}–${it.maxNights} nights" },
            ).joinToString(" · ")
        }
    }

    private fun people(p: Passengers): String = buildList {
        add("${p.adults} adult${if (p.adults != 1) "s" else ""}")
        if (p.childAges.isNotEmpty()) add("${p.childAges.size} child${if (p.childAges.size != 1) "ren" else ""}")
        if (p.infantsOnLap > 0) add("${p.infantsOnLap} infant${if (p.infantsOnLap != 1) "s" else ""}")
    }.joinToString(", ")

    private fun cabin(c: Cabin) = c.name.lowercase().replace('_', ' ')

    private fun bags(t: Tracker) = when (t.carryOnBags) {
        0 -> ""
        1 -> " · 🧳 1 cabin bag"
        else -> " · 🧳 ${t.carryOnBags} cabin bags"
    }

    private fun routeAndDates(q: SearchQuery) =
        "${q.origin}→${q.destination} ${SHORT.format(q.departDate)}" + (q.returnDate?.let { " ⇄ ${SHORT.format(it)}" } ?: "")

    private fun stopsText(n: Int) = when (n) { 0 -> "direct"; 1 -> "1 stop"; else -> "$n stops" }
    private fun parse(iso: String): LocalDateTime? = runCatching { LocalDateTime.parse(iso.take(16)) }.getOrNull()
    private fun hm(t: LocalDateTime?) = t?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "?"

    /** Split a message on line boundaries so each part fits a chat platform's size limit. */
    fun chunk(text: String, max: Int): List<String> =
        text.lines().fold(listOf("")) { acc, line ->
            val current = acc.last()
            val candidate = if (current.isEmpty()) line else "$current\n$line"
            when {
                candidate.length <= max -> acc.dropLast(1) + candidate
                line.length > max -> acc + line.chunked(max)
                else -> acc + line
            }
        }.filter { it.isNotBlank() }
}
