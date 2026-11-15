package mmflights

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import mmflights.config.NotifierSettings
import mmflights.notify.MessengerNotifier
import mmflights.providers.httpClient
import kotlin.test.*

class MessengerTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val settings = NotifierSettings.Messenger("PAGE_TOKEN", listOf("111", "222"), "v24.0")

    @Test fun `sends one message per recipient and explains the 24h window error`() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            assertEquals("/v24.0/me/messages", req.url.encodedPath)
            assertEquals("PAGE_TOKEN", req.url.parameters["access_token"])
            val body = (req.body as TextContent).text.also { bodies += it }
            if ("\"222\"" in body) respond(
                """{"error":{"message":"(#10) This message is sent outside of allowed window.","code":10,"error_subcode":2018278}}""",
                HttpStatusCode.BadRequest, jsonHeaders,
            ) else respond("""{"recipient_id":"111","message_id":"m"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val err = MessengerNotifier(httpClient(engine), settings).send(mmflights.domain.message { line(mmflights.domain.text("Hello ✈️")) }).leftOrNull()

        assertEquals(2, bodies.size)
        assertContains(bodies[0], "\"messaging_type\":\"UPDATE\"")
        assertContains(bodies[0], "\"text\":\"Hello ✈️\"")
        assertNotNull(err)
        assertContains(err, "recipient 222")
        assertContains(err, "24-hour window")
        assertFalse("111" in err)
    }

    @Test fun `lists conversation participants except the page`() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/v24.0/me" -> respond("""{"id":"999","name":"Trip Bot"}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond(
                    """{"data":[{"participants":{"data":[{"name":"Maria","id":"111"},{"name":"Trip Bot","id":"999"}]}},
                               {"participants":{"data":[{"name":"Ivan","id":"222"},{"name":"Trip Bot","id":"999"}]}}]}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
            }
        }
        val people = MessengerNotifier(httpClient(engine), settings).listRecipients().getOrNull()
        assertEquals(listOf("Maria" to "111", "Ivan" to "222"), people)
    }
}
