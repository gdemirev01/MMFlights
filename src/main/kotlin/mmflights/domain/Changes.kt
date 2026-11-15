package mmflights.domain

import java.math.BigDecimal
import java.math.MathContext

/**
 * Compares the cheapest offer of two consecutive snapshots and decides whether the difference is
 * worth telling anyone about. Pure: no clock, no IO.
 */
fun detectChange(previous: Snapshot?, current: Snapshot, policy: NotifyPolicy): PriceChange? {
    if (current.isBlind) return null // every provider failed: don't confuse "unknown" with "gone"
    val old = previous?.best?.takeIf { previous.comparableTo(current) }
    val new = current.best
    return when {
        old == null && new == null -> null
        old == null && new != null -> PriceChange.FirstSeen(new)
        old != null && new == null -> PriceChange.Disappeared(old).takeIf { policy.alertOn == AlertOn.ANY_CHANGE }
        else -> priceMove(old!!, new!!, policy)
    }
}

private fun priceMove(old: Offer, new: Offer, policy: NotifyPolicy): PriceChange? {
    if (old.price.currency != new.price.currency) return PriceChange.FirstSeen(new)
    val diff = new.price.amount - old.price.amount
    val abs = diff.abs()
    val pct = if (old.price.amount.signum() == 0) BigDecimal(100)
              else abs.multiply(BigDecimal(100)).divide(old.price.amount, MathContext.DECIMAL64)
    val significant = diff.signum() != 0 && abs >= policy.minChangeAmount && pct >= policy.minChangePercent
    return when {
        !significant -> null
        diff.signum() < 0 -> PriceChange.Dropped(old, new)
        policy.alertOn == AlertOn.ANY_CHANGE -> PriceChange.Rose(old, new)
        else -> null
    }
}

/** Only alert on drops when they land under the user's price ceiling (if any). */
fun PriceChange.respectsCeiling(tracker: Tracker): Boolean {
    val ceiling = tracker.maxPrice ?: return true
    return when (this) {
        is PriceChange.FirstSeen -> offer.price.amount <= ceiling
        is PriceChange.Dropped -> current.price.amount <= ceiling
        is PriceChange.Rose -> previous.price.amount <= ceiling // was a deal, isn't any more
        is PriceChange.Disappeared -> previous.price.amount <= ceiling
    }
}

/** Fold a new snapshot into the stored state, keeping a bounded price history. */
fun TrackerState.record(snapshot: Snapshot, maxHistory: Int = 1000): TrackerState =
    if (!last.comparableTo(snapshot)) TrackerState(last = snapshot, history = listOf(PricePoint(snapshot.takenAt, snapshot.best?.price)))
    else if (snapshot.isBlind) copy(history = (history + PricePoint(snapshot.takenAt, null)).takeLast(maxHistory))
    else TrackerState(
        last = snapshot,
        history = (history + PricePoint(snapshot.takenAt, snapshot.best?.price)).takeLast(maxHistory),
    )

/**
 * False when the search settings that affect price (passengers, cabin, bags, currency) changed
 * between two snapshots – e.g. after turning on bag fees. Such prices must not be compared.
 */
fun Snapshot?.comparableTo(other: Snapshot): Boolean {
    val a = this?.best?.query?.basis ?: return true
    val b = other.best?.query?.basis ?: return true
    return a == b
}
