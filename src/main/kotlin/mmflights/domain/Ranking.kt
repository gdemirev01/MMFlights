package mmflights.domain

/**
 * Pure ranking of raw provider offers:
 *  - drops offers violating the tracker's stop limit,
 *  - compares only like-for-like currency (tracker currency, or the dominant one if no offer uses it),
 *  - de-duplicates the same itinerary found by several providers (keeping the cheapest),
 *  - returns the [Tracker.topN] cheapest.
 */
fun rankOffers(tracker: Tracker, offers: List<Offer>): Pair<List<Offer>, List<String>> {
    val eligible = offers.filter { o -> tracker.maxStops == null || o.maxStops <= tracker.maxStops }
    if (eligible.isEmpty()) return emptyList<Offer>() to emptyList()

    val currency = eligible.map { it.price.currency }.let { cs ->
        if (tracker.currency in cs) tracker.currency
        else cs.groupingBy { it }.eachCount().maxBy { it.value }.key
    }
    val (same, other) = eligible.partition { it.price.currency == currency }
    val warnings = buildList {
        if (currency != tracker.currency) add("No offers priced in ${tracker.currency}; comparing in $currency instead.")
        if (other.isNotEmpty()) add("Ignored ${other.size} offer(s) priced in other currencies (${other.map { it.price.currency }.distinct().joinToString()}).")
    }

    val ranked = same
        .sortedBy { it.price.amount }
        .distinctBy { it.itineraryKey }
        .take(tracker.topN)
    return ranked to warnings
}
