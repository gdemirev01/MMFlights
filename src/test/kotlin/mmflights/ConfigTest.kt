package mmflights

import mmflights.app.parseArgs
import mmflights.config.ProviderSettings
import mmflights.config.parseConfig
import mmflights.domain.Trip
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

    @Test fun `cli parsing`() {
        val cli = parseArgs(listOf("-c", "x.yaml", "check", "-t", "a", "--dry-run"), emptyMap()).getOrNull()!!
        assertEquals("check", cli.command); assertEquals(setOf("a"), cli.trackers); assertTrue(cli.dryRun)
        assertTrue(parseArgs(listOf("bogus"), emptyMap()).isLeft())
    }
}
