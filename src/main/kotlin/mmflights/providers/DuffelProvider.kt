package mmflights.providers

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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

/**
 * Duffel (https://duffel.com) – a single API in front of 300+ airlines (NDC, GDS and low-cost
 * carriers). One offer request searches every airline Duffel is connected to.
 */
class DuffelProvider(private val client: HttpClient, private val settings: ProviderSettings.Duffel) : FlightProvider {
    override val name = "duffel"

    override suspend fun search(query: SearchQuery): Either<ProviderError, List<Offer>> = either {
        val response = Either.catch {
            client.post("${settings.baseUrl}/air/offer_requests") {
                parameter("return_offers", true)
                parameter("supplier_timeout", 20_000)
                bearerAuth(settings.token)
                header("Duffel-Version", "v2")
                header("Accept-Encoding", "gzip")
                contentType(ContentType.Application.Json)
                setBody(DuffelRequest(toRequest(query)))
            }
        }.mapLeft { ProviderError(name, "request failed: ${it.message}") }.bind()

        ensure(response.status.isSuccess()) { ProviderError(name, response.errorText()) }
        val body = Either.catch { response.body<DuffelResponse>() }
            .mapLeft { ProviderError(name, "unexpected response: ${it.message}") }.bind()
        body.data.offers.map { it.toOffer(query) }
    }

    private fun DuffelOffer.toOffer(q: SearchQuery) = Offer(
        provider = name,
        query = q,
        price = Money(totalAmount.toBigDecimal(), totalCurrency),
        airlines = (listOf(owner.name) + slices.flatMap { s -> s.segments.map { it.marketingCarrier.name } }).distinct(),
        legs = slices.map { s ->
            Leg(durationMinutes = s.duration?.let { d -> runCatching { java.time.Duration.parse(d).toMinutes().toInt() }.getOrNull() }, segments = s.segments.map {
                Segment(
                    carrier = it.marketingCarrier.iataCode ?: it.marketingCarrier.name,
                    flightNumber = it.flightNumber,
                    from = it.origin.iataCode,
                    to = it.destination.iataCode,
                    departAt = it.departingAt,
                    arriveAt = it.arrivingAt,
                )
            })
        },
        bookingUrl = null,
    )

    companion object {
        fun toRequest(q: SearchQuery) = DuffelRequestData(
            slices = listOfNotNull(
                DuffelSliceRequest(q.origin, q.destination, q.departDate.toString()),
                q.returnDate?.let { DuffelSliceRequest(q.destination, q.origin, it.toString()) },
            ),
            passengers = List(q.passengers.adults) { DuffelPassenger(type = "adult") } +
                q.passengers.childAges.map { DuffelPassenger(age = it) } +
                List(q.passengers.infantsOnLap) { DuffelPassenger(type = "infant_without_seat") },
            cabinClass = when (q.cabin) {
                Cabin.ECONOMY -> "economy"; Cabin.PREMIUM_ECONOMY -> "premium_economy"
                Cabin.BUSINESS -> "business"; Cabin.FIRST -> "first"
            },
            maxConnections = q.maxStops?.coerceAtMost(2),
        )
    }
}

// --- wire format ---------------------------------------------------------------------------------

@Serializable data class DuffelRequest(val data: DuffelRequestData)

@Serializable
data class DuffelRequestData(
    val slices: List<DuffelSliceRequest>,
    val passengers: List<DuffelPassenger>,
    @SerialName("cabin_class") val cabinClass: String,
    @SerialName("max_connections") val maxConnections: Int? = null,
)

@Serializable
data class DuffelSliceRequest(
    val origin: String,
    val destination: String,
    @SerialName("departure_date") val departureDate: String,
)

@Serializable data class DuffelPassenger(val type: String? = null, val age: Int? = null)

@Serializable data class DuffelResponse(val data: DuffelResponseData)
@Serializable data class DuffelResponseData(val offers: List<DuffelOffer> = emptyList())

@Serializable
data class DuffelOffer(
    @SerialName("total_amount") val totalAmount: String,
    @SerialName("total_currency") val totalCurrency: String,
    val owner: DuffelAirline,
    val slices: List<DuffelSlice> = emptyList(),
)

@Serializable data class DuffelAirline(val name: String, @SerialName("iata_code") val iataCode: String? = null)
@Serializable data class DuffelSlice(val segments: List<DuffelSegment> = emptyList(), val duration: String? = null)
@Serializable data class DuffelPlace(@SerialName("iata_code") val iataCode: String)

@Serializable
data class DuffelSegment(
    val origin: DuffelPlace,
    val destination: DuffelPlace,
    @SerialName("departing_at") val departingAt: String,
    @SerialName("arriving_at") val arrivingAt: String,
    @SerialName("marketing_carrier") val marketingCarrier: DuffelAirline,
    @SerialName("marketing_carrier_flight_number") val flightNumber: String = "",
)
