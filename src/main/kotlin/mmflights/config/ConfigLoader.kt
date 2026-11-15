package mmflights.config

import arrow.core.Either
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.raise.either
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path

private val yaml = Yaml(
    configuration = YamlConfiguration(
        polymorphismStyle = PolymorphismStyle.Property,
        polymorphismPropertyName = "type",
        strictMode = true,
    )
)

private val ENV_REF = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}""")

/** Replace `${VAR}` / `${VAR:-default}` with values from [env]; secrets never need to live in the file. */
fun interpolate(text: String, env: Map<String, String>): Either<Errors, String> {
    // Comment lines are left untouched so commented-out examples don't demand variables.
    val active = text.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
    val missing = ENV_REF.findAll(active)
        .filter { env[it.groupValues[1]] == null && it.groups[2] == null }
        .map { "environment variable ${it.groupValues[1]} is not set" }
        .distinct().toList().toNonEmptyListOrNull()
    return missing?.left() ?: text.lines().joinToString("\n") { line ->
        if (line.trimStart().startsWith("#")) line
        else ENV_REF.replace(line) { m -> env[m.groupValues[1]] ?: m.groupValues[2] }
    }.right()
}

fun parseConfig(text: String, env: Map<String, String>): Either<Errors, AppConfig> = either {
    val resolved = interpolate(text, env).bind()
    val raw = Either.catch { yaml.decodeFromString(RawConfig.serializer(), resolved) }
        .mapLeft { nonEmptyListOf("invalid YAML: ${it.message}") }
        .bind()
    raw.validate().bind()
}

fun loadConfig(path: Path, env: Map<String, String> = System.getenv()): Either<Errors, AppConfig> =
    if (!Files.exists(path)) nonEmptyListOf("config file not found: $path").left()
    else parseConfig(Files.readString(path), env)

