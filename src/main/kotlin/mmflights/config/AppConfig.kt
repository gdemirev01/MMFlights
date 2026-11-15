package mmflights.config

import mmflights.domain.Tracker
import java.time.Duration

data class AppConfig(
    val pollInterval: Duration,
    val concurrency: Int,
    val dataDir: String,
    val providers: List<ProviderSettings>,
    val notifiers: List<NotifierSettings>,
    val trackers: List<Tracker>,
)

sealed interface ProviderSettings {
    data class Duffel(val token: String, val baseUrl: String) : ProviderSettings
    data class SerpApi(val apiKey: String, val baseUrl: String, val language: String, val country: String?) : ProviderSettings
    data object Demo : ProviderSettings
}

sealed interface NotifierSettings {
    data class Telegram(val botToken: String, val chatId: String) : NotifierSettings
    data class Slack(val webhookUrl: String) : NotifierSettings
    data class Discord(val webhookUrl: String) : NotifierSettings
    data object Console : NotifierSettings
}
