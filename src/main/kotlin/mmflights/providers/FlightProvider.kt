package mmflights.providers

import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import mmflights.config.ProviderSettings
import mmflights.domain.Offer
import mmflights.domain.SearchQuery

/** A source of flight prices. Implementations must never throw – failures are values. */
interface FlightProvider {
    val name: String
    suspend fun search(query: SearchQuery): Either<ProviderError, List<Offer>>
}

data class ProviderError(val provider: String, val message: String)

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

fun httpClient(engine: HttpClientEngine = CIO.create()): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(json) }
    install(HttpTimeout) {
        requestTimeoutMillis = 90_000
        connectTimeoutMillis = 15_000
    }
    install(HttpRequestRetry) {
        maxRetries = 3
        retryOnExceptionOrServerErrors()
        retryIf { _, response -> response.status.value == 429 }
        exponentialDelay(base = 2.0, maxDelayMs = 30_000)
    }
}

/** Build the provider list from settings: a pure mapping, the client is the only shared resource. */
fun providersFrom(settings: List<ProviderSettings>, client: HttpClient): List<FlightProvider> = settings.map {
    when (it) {
        is ProviderSettings.Duffel -> DuffelProvider(client, it)
        is ProviderSettings.SerpApi -> SerpApiGoogleFlightsProvider(client, it)
        ProviderSettings.Demo -> DemoProvider()
    }
}

internal suspend fun HttpResponse.errorText(): String =
    "HTTP ${status.value}: " + runCatching { bodyAsText().take(300) }.getOrDefault("")
