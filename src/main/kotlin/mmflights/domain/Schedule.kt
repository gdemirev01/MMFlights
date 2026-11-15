package mmflights.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Next instant (strictly after [now]) at which the local wall clock in [zone] shows [at]. */
fun nextDailyRun(now: Instant, at: LocalTime, zone: ZoneId): Instant {
    val local = now.atZone(zone)
    val today = local.toLocalDate().atTime(at).atZone(zone)
    return (if (today.toInstant().isAfter(now)) today else today.plusDays(1).toLocalDate().atTime(at).atZone(zone)).toInstant()
}

fun delayUntil(now: Instant, target: Instant): Duration =
    Duration.between(now, target).let { if (it.isNegative) Duration.ZERO else it }
