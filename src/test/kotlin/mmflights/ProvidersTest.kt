package mmflights

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mmflights.config.ProviderSettings
import mmflights.domain.Passengers
import mmflights.providers.DuffelProvider
import mmflights.providers.SerpApiGoogleFlightsProvider
import mmflights.providers.httpClient
import kotlin.test.*

class ProvidersTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun resource(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun `duffel request and response mapping`() = runTest {
        var sent: JsonObject? = null
        val engine = MockEngine { req ->
            assertEquals("Bearer tok", req.headers[HttpHeaders.Authorization])
            assertEquals("v2", req.headers["Duffel-Version"])
            sent = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            respond(resource("duffel.json"), HttpStatusCode.Created, jsonHeaders)
        }
        val p = DuffelProvider(httpClient(engine), ProviderSettings.Duffel("tok", "https://api.duffel.com"))
        val offers = p.search(Fixtures.query.copy(passengers = Passengers(2, listOf(8), 1), maxStops = 0)).getOrNull()!!

        val data = sent!!["data"]!!.jsonObject
        assertEquals(2, data["slices"]!!.jsonArray.size)
        assertEquals(4, data["passengers"]!!.jsonArray.size)
        assertEquals(0, data["max_connections"]!!.jsonPrimitive.int)

        assertEquals(1, offers.size)
        val o = offers.single()
        assertEquals("412.30", o.price.amount.toPlainString())
        assertEquals(2, o.legs.size)
        assertEquals("W6", o.legs[0].segments[0].carrier)
    }

    @Test fun `duffel http error becomes a value`() = runTest {
        val engine = MockEngine { respond("""{"errors":[{"message":"bad"}]}""", HttpStatusCode.BadRequest, jsonHeaders) }
        val p = DuffelProvider(httpClient(engine), ProviderSettings.Duffel("tok", "https://api.duffel.com"))
        val err = p.search(Fixtures.query).leftOrNull()
        assertNotNull(err)
        assertContains(err.message, "400")
    }

    @Test fun `serpapi mapping`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("google_flights", req.url.parameters["engine"])
            assertEquals("1", req.url.parameters["type"])
            respond(resource("serpapi.json"), HttpStatusCode.OK, jsonHeaders)
        }
        val p = SerpApiGoogleFlightsProvider(httpClient(engine), ProviderSettings.SerpApi("k", "https://serpapi.com", "en", null))
        val offers = p.search(Fixtures.query).getOrNull()!!
        assertEquals(listOf("189.0", "240.0"), offers.map { it.price.amount.toPlainString() })
        val first = offers.first()
        assertEquals("FR", first.legs[0].segments[0].carrier)
        assertEquals("2026-12-18T06:05", first.legs[0].departAt)
        assertEquals("https://www.google.com/travel/flights?x", first.bookingUrl)
    }
}
