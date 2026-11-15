package mmflights.providers

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mmflights.config.ProviderSettings
import mmflights.domain.Cabin
import mmflights.domain.Leg
import mmflights.domain.Money
import mmflights.domain.Offer
import mmflights.domain.SearchQuery
import mmflights.domain.Segment
import java.math.BigDecimal

/**
 * Google Flights results via SerpApi (https://serpapi.com/google-flights-api). Google Flights
 * aggregates practically every airline, including most low-cost carriers.
 *
 * For return trips the price is the round-trip total; Google only lists the outbound flights in
 * the first response, so offers carry the outbound leg only.
 */
class SerpApiGoogleFlightsProvider(
    private val client: HttpClient,
    private val settings: ProviderSettings.SerpApi,
) : FlightProvider {
    override val name = "google-flights"

    override suspend fun search(query: SearchQuery): Either<ProviderError, List<Offer>> = either {
        val response = Either.catch {
            client.get("${settings.baseUrl}/search.json") {
                params(query).forEach { (k, v) -> parameter(k, v) }
                parameter("api_key", settings.apiKey)
            }
        }.mapLeft { ProviderError(name, "request failed: ${it.message}") }.bind()

        ensure(response.status.isSuccess()) { ProviderError(name, response.errorText()) }
        val body = Either.catch { response.body<SerpResponse>() }
            .mapLeft { ProviderError(name, "unexpected response: ${it.message}") }.bind()
        // "no results" is reported as an error string by SerpApi, but it's a valid empty answer.
        ensure(body.error == null || body.error.contains("hasn't returned any results", ignoreCase = true)) {
            ProviderError(name, body.error)
        }
        (body.bestFlights + body.otherFlights)
            .filter { it.price != null && it.flights.isNotEmpty() }
            .map { it.toOffer(query, body.searchMetadata?.googleFlightsUrl) }
    }

    /** Pure mapping of a query to SerpApi parameters. */
    fun params(q: SearchQuery): List<Pair<String, String>> = buildList {
        add("engine" to "google_flights")
        add("departure_id" to q.origin)
        add("arrival_id" to q.destination)
        add("outbound_date" to q.departDate.toString())
        q.returnDate?.let { add("return_date" to it.toString()) }
        add("type" to if (q.returnDate != null) "1" else "2")
        add("adults" to q.passengers.adults.toString())
        if (q.passengers.childAges.isNotEmpty()) add("children" to q.passengers.childAges.size.toString())
        if (q.passengers.infantsOnLap > 0) add("infants_on_lap" to q.passengers.infantsOnLap.toString())
        add("travel_class" to when (q.cabin) {
            Cabin.ECONOMY -> "1"; Cabin.PREMIUM_ECONOMY -> "2"; Cabin.BUSINESS -> "3"; Cabin.FIRST -> "4"
        })
        add("stops" to when (q.maxStops) { null -> "0"; 0 -> "1"; 1 -> "2"; else -> "3" })
        add("currency" to q.currency)
        add("hl" to settings.language)
        settings.country?.let { add("gl" to it) }
    }

    private fun SerpItinerary.toOffer(q: SearchQuery, url: String?) = Offer(
        provider = name,
        query = q,
        price = Money(BigDecimal.valueOf(price!!), q.currency),
        airlines = flights.map { it.airline }.distinct(),
        legs = listOf(Leg(flights.map { f ->
            val (carrier, number) = f.flightNumber.trim().split(" ", limit = 2).let {
                if (it.size == 2) it[0] to it[1] else f.airline to f.flightNumber
            }
            Segment(
                carrier = carrier,
                flightNumber = number,
                from = f.departureAirport.id,
                to = f.arrivalAirport.id,
                departAt = f.departureAirport.time.replace(' ', 'T'),
                arriveAt = f.arrivalAirport.time.replace(' ', 'T'),
            )
        })),
        bookingUrl = url,
    )
}

// --- wire format ---------------------------------------------------------------------------------

@Serializable
data class SerpResponse(
    @SerialName("best_flights") val bestFlights: List<SerpItinerary> = emptyList(),
    @SerialName("other_flights") val otherFlights: List<SerpItinerary> = emptyList(),
    @SerialName("search_metadata") val searchMetadata: SerpMetadata? = null,
    val error: String? = null,
)

@Serializable data class SerpMetadata(@SerialName("google_flights_url") val googleFlightsUrl: String? = null)
@Serializable data class SerpItinerary(val flights: List<SerpFlight> = emptyList(), val price: Double? = null)

@Serializable
data class SerpFlight(
    @SerialName("departure_airport") val departureAirport: SerpAirport,
    @SerialName("arrival_airport") val arrivalAirport: SerpAirport,
    val airline: String = "",
    @SerialName("flight_number") val flightNumber: String = "",
)

@Serializable data class SerpAirport(val id: String, val time: String)
