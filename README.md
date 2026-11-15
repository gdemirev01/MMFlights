# MMFlights ✈️

A flight price tracker. You describe a trip (airports, flexible dates, stay length, passengers,
cabin, stops, budget) and it searches the airlines for the cheapest fare. It sends a **daily
summary** and an **alert whenever the price changes** to a group chat (Telegram, Slack or
Discord).

Written in **Kotlin**, in a functional style, with [Arrow](https://arrow-kt.io) (typed errors and
parallel calls), [Ktor client](https://ktor.io), kotlinx.serialization and coroutines.

## How it finds the cheapest price

No single API lets you query every airline one by one. Many airlines, especially low-cost ones,
have no public booking API at all. MMFlights asks **aggregators** instead: each one already
searches hundreds of airlines in one call. You can turn on any combination of these providers:

| Provider | Coverage | Notes |
|---|---|---|
| `serpapi` (Google Flights) | Practically every airline, including low-cost carriers (Ryanair, Wizz…) | Paid per search (there is a free tier). Round trips show the outbound leg; the price is the round-trip total. |
| `duffel` | 300+ airlines through direct NDC/GDS connections | A live token returns real, bookable prices. Test tokens return fake "Duffel Airways" data. |
| `demo` | Fake, deterministic prices | Lets you try the whole pipeline without any keys. |

For each tracker, every date × route combination goes to every enabled provider in parallel. The
results are merged, de-duplicated and ranked, and the cheapest `topN` are kept.

Adding another source (Amadeus, Travelpayouts, a scraper…) means writing one class that implements
`FlightProvider`.

## Quick start

```bash
cp config.example.yaml config.yaml      # demo provider + console output out of the box
./gradlew installDist
build/install/mmflights/bin/mmflights validate
build/install/mmflights/bin/mmflights summary          # search now, print the summary
build/install/mmflights/bin/mmflights run              # keep running: polls + daily summaries
```

| Command | What it does |
|---|---|
| `run` (default) | Long-running daemon. Polls every `pollInterval` and alerts on changes. Sends each tracker's daily summary at its `dailyAt` in its `timezone`. |
| `check` | Searches once and alerts only if the price changed. Good for cron, CI or Actions. |
| `summary` | Searches once and sends the full summary. |
| `validate` | Checks the config and reports **all** problems at once. |

Options: `-c/--config <file>`, `-t/--tracker <id>` (repeatable), and `--dry-run` (print to the
console instead of sending).

## Configuration

See [`config.example.yaml`](config.example.yaml). Every option is commented there. The main ones
for each tracker:

- `origins` / `destinations`: lists of IATA codes. Every pair is searched.
- `departure: {from, to}`: the window of possible departure dates.
- Return dates: set `stay: {min, max}` (nights), `return: {from, to}`, or both (their
  intersection is used). Set `oneWay: true` for one-way trips.
- `passengers: {adults, children: [ages], infants}`, `cabin`, `maxStops`
- `maxPrice`: alerts only fire for deals at or under this total.
- `maxQueries`: caps how many date × route combinations are searched per check, which keeps API
  costs under control.
- `notify`: `onChange`, `alertOn: any|drops`, `minChangePercent`, `minChangeAmount`,
  `dailyAt`, `timezone`.

Secrets come from environment variables through `${VAR}` (or `${VAR:-default}`).

### Group chat setup

- **Telegram**: create a bot with [@BotFather](https://t.me/BotFather) and add it to your group.
  Send a message in the group, then open `https://api.telegram.org/bot<TOKEN>/getUpdates` to find
  the group's `chat.id` (a negative number). Set `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`.
- **Slack / Discord**: create an incoming webhook for the channel and set its URL.

## Running it on a schedule

- **GitHub Actions (no server):** `.github/workflows/flight-tracker.yml` runs `check` every
  3 hours and `summary` daily at 07:03 UTC. Price history is carried between runs in the
  Actions cache. Commit a `config.yaml` and add your keys as repository secrets.
- **Docker / any server:**
  `docker build -t mmflights . && docker run -d -v $PWD/config.yaml:/app/config.yaml -v $PWD/data:/app/data --env-file .env mmflights`
- **cron:** call `mmflights check` and `mmflights summary` at the times you want.

## Design

The project follows a *functional core, imperative shell* layout:

```
domain/      pure: model, query expansion, ranking, change detection, message rendering, schedule math
config/      YAML → RawConfig → validated AppConfig (Arrow error accumulation, env interpolation)
providers/   FlightProvider implementations; every failure is an Either value, never a thrown exception
notify/      Telegram / webhook / console notifiers
store/       per-tracker JSON state (last snapshot + price history), atomic writes
app/         Engine (search → record → decide → notify) and the CLI
```

- Everything that decides *what* happens is a pure function with unit tests: which queries to
  run, which offer wins, whether a change matters, and what the message says.
- Side effects (HTTP, files, clock) arrive through an explicit `Env` record. Tests swap them for
  fakes.
- If every provider fails, the run is recorded as "blind". It is never reported as "the flights
  disappeared".

```bash
./gradlew test
```
