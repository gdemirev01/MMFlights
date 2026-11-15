package mmflights.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The YAML file exactly as written by the user. Everything is loosely typed here;
 * [validate] turns it into the strongly typed [AppConfig] (accumulating *all* errors).
 */
@Serializable
data class RawConfig(
    val currency: String = "EUR",
    val pollInterval: String = "3h",
    val concurrency: Int = 4,
    val dataDir: String = "data",
    val providers: RawProviders = RawProviders(),
    val notifiers: List<RawNotifier> = emptyList(),
    val trackers: List<RawTracker> = emptyList(),
)

@Serializable
data class RawProviders(
    val duffel: RawDuffel? = null,
    val serpapi: RawSerpApi? = null,
    /** Deterministic fake prices – lets you try the whole pipeline without API keys. */
    val demo: Boolean = false,
)

@Serializable data class RawDuffel(val token: String, val baseUrl: String = "https://api.duffel.com")
@Serializable data class RawSerpApi(val apiKey: String, val baseUrl: String = "https://serpapi.com", val language: String = "en", val country: String? = null)

@Serializable
sealed interface RawNotifier {
    @Serializable @SerialName("telegram") data class Telegram(val botToken: String, val chatId: String) : RawNotifier
    @Serializable @SerialName("slack") data class Slack(val webhookUrl: String) : RawNotifier
    @Serializable @SerialName("discord") data class Discord(val webhookUrl: String) : RawNotifier
    @Serializable @SerialName("console") data class Console(val enabled: Boolean = true) : RawNotifier
}

@Serializable
data class RawTracker(
    val id: String,
    val name: String? = null,
    val origins: List<String>,
    val destinations: List<String>,
    val departure: RawDateRange,
    @SerialName("return") val returnWindow: RawDateRange? = null,
    val stay: RawStay? = null,
    val oneWay: Boolean = false,
    val passengers: RawPassengers = RawPassengers(),
    val cabin: String = "economy",
    val maxStops: Int? = null,
    val maxPrice: Double? = null,
    val currency: String? = null,
    val maxQueries: Int = 40,
    val topN: Int = 5,
    val notify: RawNotify = RawNotify(),
)

@Serializable data class RawDateRange(val from: String, val to: String? = null)
@Serializable data class RawStay(val min: Int, val max: Int)
@Serializable data class RawPassengers(val adults: Int = 1, val children: List<Int> = emptyList(), val infants: Int = 0)

@Serializable
data class RawNotify(
    val onChange: Boolean = true,
    /** "any" or "drops" */
    val alertOn: String = "any",
    val minChangePercent: Double = 0.0,
    val minChangeAmount: Double = 1.0,
    /** "HH:mm" local time, or null to disable the daily summary. */
    val dailyAt: String? = "09:00",
    val timezone: String = "UTC",
)
