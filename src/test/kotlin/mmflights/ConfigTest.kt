package mmflights

import mmflights.app.parseArgs
import mmflights.config.ProviderSettings
import mmflights.config.parseConfig
import mmflights.domain.Trip
import mmflights.domain.expand
import kotlin.test.*

class ConfigTest {
    private val good = """
        currency: EUR
        providers:
          demo: true
          serpapi:
            apiKey: "${'$'}{SERP_KEY}"
        notifiers:
          - type: telegram
            botToken: "${'$'}{TG}"
            chatId: "-100123"
        # - type: slack
        #   webhookUrl: ${'$'}{NOT_SET}
        trackers:
          - id: xmas
            origins: [sof]
            destinations: [LTN, STN]
            departure: { from: 2026-12-18, to: 2026-12-20 }
            stay: { min: 7, max: 10 }
            passengers: { adults: 2, children: [8] }
            notify: { dailyAt: "08:30", timezone: Europe/Sofia, alertOn: drops }
    """.trimIndent()

    @Test fun `parses a valid config with env interpolation`() {
        val cfg = parseConfig(good, mapOf("SERP_KEY" to "k", "TG" to "t")).getOrNull()
        assertNotNull(cfg)
        assertEquals(2, cfg.providers.size)
        assertEquals("k", (cfg.providers.filterIsInstance<ProviderSettings.SerpApi>().single()).apiKey)
        val t = cfg.trackers.single()
        assertEquals(listOf("SOF"), t.origins.toList())
        assertIs<Trip.Return>(t.trip)
    }

    @Test fun `missing env vars are reported`() {
        val errs = parseConfig(good, emptyMap()).leftOrNull()
        assertNotNull(errs)
        assertEquals(2, errs.size)
    }

    @Test fun `accumulates every validation error`() {
        val bad = """
            pollInterval: soon
            trackers:
              - id: "bad id"
                origins: [LONDON]
                destinations: []
                departure: { from: 2026-13-01 }
                passengers: { adults: 0, children: [1] }
                cabin: luxury
                notify: { timezone: Mars/Base }
        """.trimIndent()
        val errs = parseConfig(bad, emptyMap()).leftOrNull()
        assertNotNull(errs)
        val all = errs.joinToString("\n")
        listOf("pollInterval", "provider", "id may only", "origins", "destinations", "departure.from", "adults", "children", "cabin", "timezone")
            .forEach { assertContains(all, it) }
    }

    @Test fun `the shipped config yaml is valid`() {
        val text = java.io.File("config.yaml").readText()
        val env = mapOf("SERPAPI_KEY" to "k", "MESSENGER_PAGE_TOKEN" to "t", "MESSENGER_RECIPIENT_IDS" to "123, 456")
        val cfg = parseConfig(text, env).fold({ fail(it.joinToString()) }, { it })
        val t = cfg.trackers.single()
        assertEquals("sofia-budapest-new-year", t.id)
        assertEquals(4, t.passengers.total)
        assertEquals(java.time.Duration.ofHours(5), t.times.maxDuration)
        val m = cfg.notifiers.filterIsInstance<mmflights.config.NotifierSettings.Messenger>().single()
        assertEquals(listOf("123", "456"), m.recipients)
        // exactly one search combination: SOF→BUD 30 Dec ⇄ 3 Jan
        val q = t.expand(java.time.LocalDate.parse("2026-11-15")).queries.single()
        assertEquals("SOF→BUD 2026-12-30 ⇄ 2027-01-03", q.label)
    }

    @Test fun `messenger is skipped until its secrets are set`() {
        val cfg = parseConfig(java.io.File("config.yaml").readText(), mapOf("SERPAPI_KEY" to "k")).fold({ fail(it.joinToString()) }, { it })
        assertEquals(listOf(mmflights.config.NotifierSettings.Console), cfg.notifiers)
        val half = parseConfig(java.io.File("config.yaml").readText(), mapOf("SERPAPI_KEY" to "k", "MESSENGER_RECIPIENT_IDS" to "1"))
        assertContains(half.leftOrNull()!!.joinToString(), "pageAccessToken")
        // token but no IDs yet: still loads, so `messenger-ids` can run
        val tokenOnly = parseConfig(java.io.File("config.yaml").readText(), mapOf("SERPAPI_KEY" to "k", "MESSENGER_PAGE_TOKEN" to "t"))
        assertEquals(emptyList(), tokenOnly.getOrNull()!!.notifiers.filterIsInstance<mmflights.config.NotifierSettings.Messenger>().single().recipients)
    }

    @Test fun `the example config is valid`() {
        assertTrue(parseConfig(java.io.File("config.example.yaml").readText(), emptyMap()).isRight())
    }

    @Test fun `bad messenger recipients and times are reported`() {
        val text = good.replace("trackers:", """
              - type: messenger
                pageAccessToken: x
                recipients: "maria"
            trackers:""".trimIndent()).replace("notify: {", "times: { maxDuration: forever, departAfter: '25:00' }\n    notify: {")
        val all = parseConfig(text, mapOf("SERP_KEY" to "k", "TG" to "t")).leftOrNull()!!.joinToString("\n")
        assertContains(all, "numeric")
        assertContains(all, "maxDuration")
        assertContains(all, "departAfter")
    }

    @Test fun `cli parsing`() {
        val cli = parseArgs(listOf("-c", "x.yaml", "check", "-t", "a", "--dry-run"), emptyMap()).getOrNull()!!
        assertEquals("check", cli.command); assertEquals(setOf("a"), cli.trackers); assertTrue(cli.dryRun)
        assertTrue(parseArgs(listOf("bogus"), emptyMap()).isLeft())
    }
}
