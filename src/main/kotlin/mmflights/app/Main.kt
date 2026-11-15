package mmflights.app

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.runBlocking
import mmflights.config.AppConfig
import mmflights.config.loadConfig
import mmflights.domain.Trigger
import mmflights.notify.ConsoleNotifier
import mmflights.notify.notifiersFrom
import mmflights.providers.httpClient
import mmflights.providers.providersFrom
import mmflights.store.JsonFileStore
import java.nio.file.Path
import kotlin.system.exitProcess

private val USAGE = """
Usage: mmflights [options] <command>

Commands:
  run        Keep running: poll for price changes and send the daily summaries (default)
  check      Run every tracker once and alert on significant changes (cron / CI friendly)
  summary    Run every tracker once and send the full summary now
  validate   Only check the config file

Options:
  -c, --config <file>   Config file (default: config.yaml, or ${'$'}MMFLIGHTS_CONFIG)
  -t, --tracker <id>    Only this tracker (repeatable)
      --dry-run         Print messages to the console instead of sending them
"""

data class Cli(val command: String, val config: Path, val trackers: Set<String>, val dryRun: Boolean)

/** Pure argument parsing via a fold over the argument list. */
fun parseArgs(args: List<String>, env: Map<String, String>): Either<String, Cli> {
    tailrec fun go(rest: List<String>, acc: Cli): Either<String, Cli> = when {
        rest.isEmpty() -> acc.right()
        rest[0] in setOf("-c", "--config") ->
            if (rest.size < 2) "missing value for ${rest[0]}".left() else go(rest.drop(2), acc.copy(config = Path.of(rest[1])))
        rest[0] in setOf("-t", "--tracker") ->
            if (rest.size < 2) "missing value for ${rest[0]}".left() else go(rest.drop(2), acc.copy(trackers = acc.trackers + rest[1]))
        rest[0] == "--dry-run" -> go(rest.drop(1), acc.copy(dryRun = true))
        rest[0] in setOf("-h", "--help") -> "help".left()
        rest[0] in setOf("run", "check", "summary", "validate") -> go(rest.drop(1), acc.copy(command = rest[0]))
        else -> "unknown argument: ${rest[0]}".left()
    }
    return go(args, Cli("run", Path.of(env["MMFLIGHTS_CONFIG"] ?: "config.yaml"), emptySet(), false))
}

fun main(args: Array<String>) {
    val cli = parseArgs(args.toList(), System.getenv()).getOrElse {
        if (it != "help") System.err.println("error: $it")
        println(USAGE.trimIndent())
        exitProcess(if (it == "help") 0 else 2)
    }
    val config = loadConfig(cli.config).getOrElse { errors ->
        System.err.println("Invalid configuration (${cli.config}):")
        errors.forEach { System.err.println("  • $it") }
        exitProcess(2)
    }
    if (cli.command == "validate") {
        println("✓ ${cli.config} is valid: ${config.trackers.size} tracker(s), ${config.providers.size} provider(s), ${config.notifiers.size} notifier(s)")
        return
    }
    exitProcess(runBlocking { execute(cli, config) })
}

private suspend fun execute(cli: Cli, config: AppConfig): Int = httpClient().use { client ->
    val trackers = config.trackers.filter { cli.trackers.isEmpty() || it.id in cli.trackers }
    if (trackers.isEmpty()) {
        System.err.println("No tracker matches ${cli.trackers}"); return 2
    }
    val env = Env(
        providers = providersFrom(config.providers, client),
        notifiers = if (cli.dryRun || config.notifiers.isEmpty()) listOf(ConsoleNotifier) else notifiersFrom(config.notifiers, client),
        store = JsonFileStore(Path.of(config.dataDir)),
        concurrency = config.concurrency,
    )
    when (cli.command) {
        "check", "summary" -> {
            val trigger = if (cli.command == "check") Trigger.POLL else Trigger.MANUAL
            val outcomes = trackers.map { env.check(it, trigger) }
            if (outcomes.any { it.deliveryFailures.isNotEmpty() || it.snapshot.isBlind }) 1 else 0
        }
        else -> { env.runForever(trackers, config.pollInterval); 0 }
    }
}
