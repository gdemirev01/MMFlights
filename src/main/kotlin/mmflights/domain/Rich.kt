package mmflights.domain

/**
 * A tiny, chat-agnostic rich-text model. Messages are built once as data and each chat app
 * renders them in its own markup (Telegram HTML, Slack mrkdwn, Discord markdown, plain text).
 */
sealed interface Span {
    data class Text(val value: String) : Span
    data class Bold(val value: String) : Span
    data class Italic(val value: String) : Span
    data class Link(val text: String, val url: String) : Span
}

data class Message(val lines: List<List<Span>>) {
    /** Plain-text rendering, used for logs, the console, Messenger and tests. */
    val plain: String get() = PlainText.render(this)
}

class MessageBuilder {
    private val lines = mutableListOf<List<Span>>()
    fun line(vararg spans: Span) { lines += spans.toList() }
    fun line(spans: List<Span>) { lines += spans }
    fun blank() { if (lines.isNotEmpty() && lines.last().isNotEmpty()) lines += emptyList<Span>() }
    fun build() = Message(lines.dropLastWhile { it.isEmpty() })
}

fun message(block: MessageBuilder.() -> Unit): Message = MessageBuilder().apply(block).build()

fun text(s: String): Span = Span.Text(s)
fun bold(s: String): Span = Span.Bold(s)
fun italic(s: String): Span = Span.Italic(s)
fun link(text: String, url: String): Span = Span.Link(text, url)

/** Renders a [Message] for one chat app. Pure. */
fun interface Renderer {
    fun render(m: Message): String
}

private fun Message.renderWith(span: (Span) -> String) =
    lines.joinToString("\n") { line -> line.joinToString("") { span(it) } }

object PlainText : Renderer {
    override fun render(m: Message) = m.renderWith {
        when (it) {
            is Span.Text -> it.value
            is Span.Bold -> it.value
            is Span.Italic -> it.value
            is Span.Link -> "${it.text}: ${it.url}"
        }
    }
}

/** Telegram `parse_mode=HTML`: only &, < and > (and " in attributes) need escaping. */
object TelegramHtml : Renderer {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    override fun render(m: Message) = m.renderWith {
        when (it) {
            is Span.Text -> esc(it.value)
            is Span.Bold -> "<b>${esc(it.value)}</b>"
            is Span.Italic -> "<i>${esc(it.value)}</i>"
            is Span.Link -> "<a href=\"${esc(it.url).replace("\"", "&quot;")}\">${esc(it.text)}</a>"
        }
    }
}

/** Slack mrkdwn. */
object SlackMrkdwn : Renderer {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    override fun render(m: Message) = m.renderWith {
        when (it) {
            is Span.Text -> esc(it.value)
            is Span.Bold -> "*${esc(it.value)}*"
            is Span.Italic -> "_${esc(it.value)}_"
            is Span.Link -> "<${it.url}|${esc(it.text)}>"
        }
    }
}

/** Discord markdown; `<url>` inside the link suppresses the big preview card. */
object DiscordMarkdown : Renderer {
    private fun esc(s: String) = s.replace(Regex("""([*_~`|\\])"""), """\\$1""")
    override fun render(m: Message) = m.renderWith {
        when (it) {
            is Span.Text -> esc(it.value)
            is Span.Bold -> "**${esc(it.value)}**"
            is Span.Italic -> "*${esc(it.value)}*"
            is Span.Link -> "[${esc(it.text)}](<${it.url}>)"
        }
    }
}
