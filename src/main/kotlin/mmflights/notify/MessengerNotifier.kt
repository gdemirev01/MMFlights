package mmflights.notify

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mmflights.config.NotifierSettings
import mmflights.domain.Message
import mmflights.domain.Messages
import mmflights.providers.json

/**
 * Facebook Messenger through a Facebook Page's Send API.
 *
 * Meta's rules shape how this works:
 *  - Bots can't post in group chats, so every recipient gets the message one-to-one.
 *  - A recipient must have messaged the Page first; their page-scoped ID (PSID) is the address.
 *  - Messages can only be sent within 24 hours of the recipient's last message to the Page.
 *    Outside that window Meta rejects the send; the error below says so.
 */
private val log = org.slf4j.LoggerFactory.getLogger("mmflights")

class MessengerNotifier(private val client: HttpClient, private val s: NotifierSettings.Messenger) : Notifier {
    override val name = "messenger"
    override val reachesPeople: Boolean get() = s.recipients.isNotEmpty()
    private val graph = "https://graph.facebook.com/${s.graphVersion}"

    override suspend fun send(message: Message): Either<String, Unit> {
        if (s.recipients.isEmpty()) {
            // Not an error: the Page may still be in setup while other channels deliver.
            log.warn("messenger: no recipients yet – save their IDs as MESSENGER_RECIPIENT_IDS to enable it")
            return Unit.right()
        }
        val parts = Messages.chunk(message.plain, 2_000)
        val failures = s.recipients.mapNotNull { id ->
            parts.firstNotNullOfOrNull { part -> sendOne(id, part).leftOrNull() }?.let { "recipient $id: $it" }
        }
        return if (failures.isEmpty()) Unit.right() else failures.joinToString("; ").left()
    }

    private suspend fun sendOne(psid: String, text: String): Either<String, Unit> = either {
        val r = Either.catch {
            client.post("$graph/me/messages") {
                parameter("access_token", s.pageAccessToken)
                contentType(ContentType.Application.Json)
                setBody(MessengerSend(MessengerRecipient(psid), "UPDATE", MessengerText(text)))
            }
        }.mapLeft { "request failed: ${it.message}" }.bind()
        ensure(r.status.isSuccess()) { explain(r) }
    }

    /** Page conversations → (name, PSID) pairs, to fill in `recipients`. */
    suspend fun listRecipients(): Either<String, List<Pair<String, String>>> = either {
        val me = Either.catch { client.get("$graph/me") { parameter("fields", "id,name"); parameter("access_token", s.pageAccessToken) } }
            .mapLeft { "request failed: ${it.message}" }.bind()
        ensure(me.status.isSuccess()) { explain(me) }
        val pageId = Either.catch { me.body<GraphNode>().id }.mapLeft { "unexpected response: ${it.message}" }.bind()

        val r = Either.catch {
            client.get("$graph/me/conversations") {
                parameter("platform", "messenger")
                parameter("fields", "participants")
                parameter("limit", 100)
                parameter("access_token", s.pageAccessToken)
            }
        }.mapLeft { "request failed: ${it.message}" }.bind()
        ensure(r.status.isSuccess()) { explain(r) }
        Either.catch { r.body<GraphConversations>() }.mapLeft { "unexpected response: ${it.message}" }.bind()
            .data.flatMap { it.participants.data }
            .filter { it.id != pageId }
            .distinctBy { it.id }
            .map { (it.name ?: "?") to it.id }
    }

    companion object {
        /** Turn Graph API errors into advice a person can act on. */
        suspend fun explain(r: HttpResponse): String {
            val raw = runCatching { r.bodyAsText() }.getOrDefault("")
            val err = runCatching { json.decodeFromString(GraphErrorBody.serializer(), raw).error }.getOrNull()
                ?: return "HTTP ${r.status.value}: ${raw.take(200)}"
            return when {
                err.code == 10 && err.subcode == 2018278 ->
                    "outside Messenger's 24-hour window – this person must message the Page again to keep receiving updates"
                err.code == 551 || err.subcode == 1545041 -> "this person isn't available (blocked the Page or deleted the conversation)"
                err.code == 190 -> "page access token is invalid or expired"
                err.code == 100 && err.subcode == 2018001 -> "no such recipient – PSIDs only work with the Page that issued them"
                else -> "Graph API error ${err.code}${err.subcode?.let { "/$it" } ?: ""}: ${err.message}"
            }
        }
    }
}

@Serializable data class MessengerSend(val recipient: MessengerRecipient, @SerialName("messaging_type") val messagingType: String, val message: MessengerText)
@Serializable data class MessengerRecipient(val id: String)
@Serializable data class MessengerText(val text: String)

@Serializable data class GraphErrorBody(val error: GraphError)
@Serializable data class GraphError(val message: String = "", val code: Int? = null, @SerialName("error_subcode") val subcode: Int? = null)
@Serializable data class GraphNode(val id: String, val name: String? = null)
@Serializable data class GraphConversations(val data: List<GraphConversation> = emptyList())
@Serializable data class GraphConversation(val participants: GraphParticipants = GraphParticipants())
@Serializable data class GraphParticipants(val data: List<GraphNode> = emptyList())
