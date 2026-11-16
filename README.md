# MMFlights ✈️

A flight price tracker. You describe a trip (airports, flexible dates, stay length, passengers,
cabin, stops, acceptable flight times, budget) and it searches the airlines for the cheapest fare.
It sends a **daily summary** and an **alert whenever the price changes** to Facebook Messenger,
Telegram, Slack or Discord.

The included [`config.yaml`](config.yaml) tracks **4 people, Sofia → Budapest on 30 Dec 2026,
back on 3 Jan 2027**. It looks for the lowest total price among flights of at most 5 hours per
direction that leave after 07:00 and land by 23:30.

Written in **Kotlin**, in a functional style, with [Arrow](https://arrow-kt.io) (typed errors and
parallel calls), [Ktor client](https://ktor.io), kotlinx.serialization and coroutines.

## How it finds the cheapest price

No single API lets you query every airline one by one. Many airlines, especially low-cost ones,
have no public booking API at all. MMFlights asks **aggregators** instead: each one already
searches hundreds of airlines in one call. You can turn on any combination of these providers:

| Provider | Coverage | Notes |
|---|---|---|
| `serpapi` (Google Flights) | Practically every airline, including low-cost carriers (Ryanair, Wizz…) | Paid per search (there is a small free plan). With `returnDetails: true`, one extra search per check loads the return flight of the cheapest round trip, so time limits apply to both directions. |
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
| `daily` | Like `summary`, but at most once per day per tracker, and not after the trip dates. Safe to schedule several times a day. |
| `validate` | Checks the config and reports **all** problems at once. |
| `messenger-ids` | Lists people who messaged your Facebook Page, with the IDs to use as Messenger recipients. |

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
- `carryOnBags`: how many cabin bags (trolleys) to include in the price. Google Flights adds each
  airline's bag fees, so low-cost fares become comparable with fares that include a bag. Only
  cabin bags are supported; the search API can't price checked bags.
- `times: {maxDuration, departAfter, arriveBefore}`: what counts as an *adequate flight time*.
  It applies to both directions. Among acceptable flights, the cheapest wins and the shorter one
  breaks a tie.
- `maxPrice`: alerts only fire for deals at or under this total.
- `maxQueries`: caps how many date × route combinations are searched per check, which keeps API
  costs under control.
- `notify`: `onChange`, `alertOn: any|drops`, `minChangePercent`, `minChangeAmount`,
  `dailyAt`, `timezone`.

Secrets come from environment variables through `${VAR}` (or `${VAR:-default}`).

### Setting up Google Flights (SerpApi)

1. Create an account at [serpapi.com](https://serpapi.com) and copy your API key.
2. Set it as `SERPAPI_KEY`: a repository secret for GitHub Actions, or an environment variable
   locally.
3. Budget: the Budapest tracker uses about 2 searches per run. Once a day, that is about
   60 searches a month.

### Facebook Messenger setup

Meta's rules limit what a bot can do on Messenger:

- **One-to-one only.** Bots can't post in Messenger group chats, so each person gets their own
  copy.
- **Each person must message your Page first.** That is how the bot gets their ID.
- **24-hour window.** Meta only delivers bot messages within 24 hours of the person's last
  message to the Page. If someone goes quiet, their updates stop, and the job log says
  *"outside Messenger's 24-hour window"*. Replying anything to the Page reopens the window. If
  this is a problem, add Telegram as a second channel; it has no such limit and supports groups.

Steps:

1. Create a Facebook **Page**, for example "Our Flight Tracker".
2. At [developers.facebook.com](https://developers.facebook.com), create an app of type
   *Business* and add the **Messenger** product. Connect your Page and generate a
   **Page access token**. Save it as `MESSENGER_PAGE_TOKEN`.
   - While the app is in development mode, only people with a role on the app (admins,
     developers, testers) can receive messages. Add your group members as testers under
     *App roles*. Alternatively, submit the app for review of `pages_messaging` to reach anyone.
3. Ask everyone to send the Page a message.
4. Run `mmflights messenger-ids`, or trigger the workflow with command `messenger-ids`. It lists
   the names and IDs of the people who wrote to the Page. Save the IDs, comma-separated, as
   `MESSENGER_RECIPIENT_IDS`.

### Group chat setup (Telegram / Slack / Discord)

- **Telegram**: create a bot with [@BotFather](https://t.me/BotFather) and add it to your group.
  Send a message in the group, then open `https://api.telegram.org/bot<TOKEN>/getUpdates` to find
  the group's `chat.id` (a negative number). Set `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`.
- **Slack / Discord**: create an incoming webhook for the channel and set its URL.

## Running it on a schedule

- **GitHub Actions (no server):** `.github/workflows/flight-tracker.yml` posts one update a day
  at about 09:03 Sofia time, including the change since the previous day. GitHub sometimes delays
  or skips scheduled runs, so the workflow also starts at 09:33, 10:03 and 11:03. The `daily`
  command sends at most one update per day; later runs stop without searching. If a search fails
  or no chat receives the message, the day isn't marked as done, so the next backup run retries.
  Once the trip's departure dates have passed, it stops searching and messaging. You can also
  start it manually from the Actions tab (`summary` sends now; `daily`, `check` and
  `messenger-ids` are also available). Scheduled workflows only run on the
  default branch, so merge this branch first. Price history is carried between runs in the
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
notify/      Messenger / Telegram / webhook / console notifiers
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
