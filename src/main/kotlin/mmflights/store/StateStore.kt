package mmflights.store

import arrow.core.Either
import kotlinx.serialization.json.Json
import mmflights.domain.TrackerState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Persistence port: last snapshot + price history per tracker. */
interface StateStore {
    suspend fun load(trackerId: String): TrackerState
    suspend fun save(trackerId: String, state: TrackerState): Either<Throwable, Unit>
}

/** One pretty-printed JSON file per tracker; writes are atomic (temp file + move). */
class JsonFileStore(private val dir: Path) : StateStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun file(id: String) = dir.resolve("$id.json")

    override suspend fun load(trackerId: String): TrackerState =
        Either.catch { json.decodeFromString(TrackerState.serializer(), Files.readString(file(trackerId))) }
            .getOrNull() ?: TrackerState()

    override suspend fun save(trackerId: String, state: TrackerState): Either<Throwable, Unit> = Either.catch {
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, trackerId, ".tmp")
        Files.writeString(tmp, json.encodeToString(TrackerState.serializer(), state))
        Files.move(tmp, file(trackerId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

class InMemoryStore : StateStore {
    private val states = java.util.concurrent.ConcurrentHashMap<String, TrackerState>()
    override suspend fun load(trackerId: String) = states[trackerId] ?: TrackerState()
    override suspend fun save(trackerId: String, state: TrackerState): Either<Throwable, Unit> =
        Either.Right(states.put(trackerId, state)).map { }
}
