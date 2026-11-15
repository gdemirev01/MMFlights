package mmflights.domain

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure rendering of notifications as plain text (works in Telegram, Slack, Discord, terminal). */
object Messages {

    fun changeAlert(tracker: Tracker, change: PriceChange, state: TrackerState): String = buildString {
        val (icon, headline) = when (change) {
            is PriceChange.FirstSeen -> "🆕" to "First price found: ${change.offer.price.show()}"
            is PriceChange.Dropped -> "📉" to "Price DROPPED ${delta(change.previous, change.current)}: " +
                "${change.previous.price.show()} → ${change.current.price.show()}"
            is PriceChange.Rose -> "📈" to "Price rose ${delta(change.previous, change.current)}: " +
                "${change.previous.price.show()} → ${change.current.price.show()}"
            is PriceChange.Disappeared -> "⚠️" to "No offers found any more (was ${change.previous.price.show()})"
        }
        appendLine("$icon ${tracker.name}")
        appendLine(headline)
        val offer = when (change) {
            is PriceChange.FirstSeen -> change.offer
            is PriceChange.Dropped -> change.current
            is PriceChange.Rose -> change.current
            is PriceChange.Disappeared -> null
        }
        offer?.let { appendLine(); append(describe(it)) }
        state.lowestEver?.price?.let { appendLine(); append("Lowest seen so far: ${it.show()}") }
    }.trimEnd()

    fun dailySummary(tracker: Tracker, snapshot: Snapshot, previous: Snapshot?, state: TrackerState): String = buildString {
        appendLine("✈️ ${tracker.name} — daily summary")
        appendLine(criteria(tracker))
        appendLine()
        val best = snapshot.best
        if (best == null) {
            appendLine(if (snapshot.isBlind) "❌ All searches failed this time." else "No offers match your criteria right now.")
        } else {
            appendLine("🏆 Cheapest: ${best.price.show()}")
            appendLine(describe(best))
            val prevBest = previous?.best
            if (prevBest != null && prevBest.price.currency == best.price.currency) {
                val d = best.price.amount - prevBest.price.amount
                appendLine(
                    when (d.signum()) {
                        -1 -> "▼ ${Money(d.abs(), best.price.currency).show()} vs previous check"
                        1 -> "▲ ${Money(d, best.price.currency).show()} vs previous check"
                        else -> "＝ unchanged vs previous check"
                    }
                )
            }
            state.lowestEver?.let { appendLine("Lowest seen: ${it.price!!.show()} (${date(it.at, tracker.notify.zone)})") }
            tracker.maxPrice?.let {
                val target = Money(it, best.price.currency).show()
                appendLine(if (best.price.amount <= it) "✅ Under your target of $target" else "🎯 Target: $target")
            }
            if (snapshot.cheapest.size > 1) {
                appendLine()
                appendLine("Alternatives:")
                snapshot.cheapest.drop(1).forEachIndexed { i, o ->
                    val time = o.legs.firstOrNull()?.let { l -> " · out ${hm(l.departAt).substringAfter(' ').substringAfter(' ')}" + (l.durationMinutes?.let { " (${minutesText(it)})" } ?: "") }.orEmpty()
                    appendLine("${i + 2}. ${o.price.show()} · ${o.query.label} · ${o.airlines.joinToString()} · ${stopsText(o.maxStops)}$time")
                }
            }
        }
        appendLine()
        append("Checked ${snapshot.queriesRun} search${if (snapshot.queriesRun != 1) "es" else ""}")
        if (snapshot.queriesFailed > 0) append(" (${snapshot.queriesFailed} failed)")
        append(" at ${timestamp(snapshot, tracker.notify.zone)}")
        snapshot.warnings.take(3).forEach { append("\n⚠️ $it") }
    }.trimEnd()

    fun describe(o: Offer): String = buildString {
        appendLine("${o.query.label} · ${o.airlines.joinToString()}")
        o.legs.forEachIndexed { i, leg ->
            if (leg.segments.isEmpty()) return@forEachIndexed
            val title = if (i == 0) "Out" else "Back"
            val flights = leg.segments.joinToString(" + ") { "${it.carrier}${it.flightNumber} ${it.from}-${it.to}" }
            val duration = leg.durationMinutes?.let { " · ${minutesText(it)}" }.orEmpty()
            appendLine("  $title: ${hm(leg.departAt)} → ${hm(leg.arriveAt)}$duration · ${stopsText(leg.stops)} · $flights")
        }
        if (o.missingReturn) appendLine("  Back: on ${o.query.returnDate} (details not loaded; price is the round-trip total)")
        append("  via ${o.provider}")
        o.bookingUrl?.let { append(" · $it") }
    }

    private fun criteria(t: Tracker): String {
        val p = t.passengers
        val pax = buildList {
            add("${p.adults} adult${if (p.adults != 1) "s" else ""}")
            if (p.childAges.isNotEmpty()) add("${p.childAges.size} child${if (p.childAges.size != 1) "ren" else ""}")
            if (p.infantsOnLap > 0) add("${p.infantsOnLap} infant${if (p.infantsOnLap != 1) "s" else ""}")
        }.joinToString(", ")
        val dates = when (val trip = t.trip) {
            is Trip.OneWay -> "one-way ${span(trip.departure)}"
            is Trip.Return -> "out ${span(trip.departure)}" +
                (trip.returnWindow?.let { ", back ${span(it)}" } ?: "") +
                (trip.stay?.let { ", ${it.minNights}-${it.maxNights} nights" } ?: "")
        }
        val times = with(t.times) {
            listOfNotNull(
                maxDuration?.let { "≤ ${minutesText(it.toMinutes().toInt())} per way" },
                departAfter?.let { "depart after $it" },
                arriveBefore?.let { "arrive by $it" },
            )
        }
        return (listOf(
            "${t.origins.joinToString("/")} → ${t.destinations.joinToString("/")}", dates, pax,
            t.cabin.name.lowercase().replace('_', ' '),
        ) + times).joinToString(" · ")
    }

    private fun span(r: DateRange) = if (r.from == r.to) "${r.from}" else "${r.from}..${r.to}"
    private fun delta(a: Offer, b: Offer) = Money((b.price.amount - a.price.amount).abs(), b.price.currency).show()
    private fun stopsText(n: Int) = when (n) { 0 -> "direct"; 1 -> "1 stop"; else -> "$n stops" }
    private fun hm(iso: String) = runCatching { LocalDateTime.parse(iso.take(19)).format(DateTimeFormatter.ofPattern("MMM d HH:mm")) }.getOrDefault(iso)
    private fun timestamp(s: Snapshot, zone: ZoneId) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").format(s.takenAt.atZone(zone))
    private fun date(at: java.time.Instant, zone: ZoneId) = DateTimeFormatter.ISO_LOCAL_DATE.format(at.atZone(zone))

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
