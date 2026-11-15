package mmflights.notify

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mmflights.config.NotifierSettings
import mmflights.domain.Messages

/** Somewhere to send a text message (a group chat, a channel, the terminal…). */
interface Notifier {
    val name: String
    suspend fun send(text: String): Either<String, Unit>
}

fun notifiersFrom(settings: List<NotifierSettings>, client: HttpClient): List<Notifier> = settings.map {
    when (it) {
        is NotifierSettings.Telegram -> TelegramNotifier(client, it)
        is NotifierSettings.Slack -> WebhookNotifier("slack", client, it.webhookUrl, "text", 39_000)
        is NotifierSettings.Discord -> WebhookNotifier("discord", client, it.webhookUrl, "content", 2_000)
        is NotifierSettings.Messenger -> MessengerNotifier(client, it)
        NotifierSettings.Console -> ConsoleNotifier
    }
}

/** Sends to every notifier; one failing channel doesn't stop the others. Returns failures. */
suspend fun List<Notifier>.broadcast(text: String): List<String> =
    mapNotNull { n -> n.send(text).leftOrNull()?.let { "${n.name}: $it" } }

object ConsoleNotifier : Notifier {
    override val name = "console"
    override suspend fun send(text: String): Either<String, Unit> = Either.Right(println("\n$text\n"))
}

/**
 * Telegram bot → group chat. Add the bot to the group, then use the group's chat id
 * (negative number, e.g. -1001234567890) as `chatId`.
 */
class TelegramNotifier(private val client: HttpClient, private val s: NotifierSettings.Telegram) : Notifier {
    override val name = "telegram"

    override suspend fun send(text: String): Either<String, Unit> = either {
        Messages.chunk(text, 4_000).forEach { part ->
            val r = Either.catch {
                client.post("https://api.telegram.org/bot${s.botToken}/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(TelegramMessage(s.chatId, part))
                }
            }.mapLeft { "request failed: ${it.message}" }.bind()
            ensure(r.status.isSuccess()) { "HTTP ${r.status.value}: ${r.bodyAsText().take(200)}" }
        }
    }
}

/** Incoming-webhook style chats: Slack (`text`), Discord (`content`), Mattermost, Google Chat (`text`)… */
class WebhookNotifier(
    override val name: String,
    private val client: HttpClient,
    private val url: String,
    private val field: String,
    private val maxLength: Int,
) : Notifier {
    override suspend fun send(text: String): Either<String, Unit> = either {
        Messages.chunk(text, maxLength).forEach { part ->
            val r = Either.catch {
                client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put(field, part) })
                }
            }.mapLeft { "request failed: ${it.message}" }.bind()
            ensure(r.status.isSuccess()) { "HTTP ${r.status.value}: ${r.bodyAsText().take(200)}" }
        }
    }
}

@Serializable
data class TelegramMessage(
    @SerialName("chat_id") val chatId: String,
    val text: String,
    @SerialName("disable_web_page_preview") val disablePreview: Boolean = true,
)

