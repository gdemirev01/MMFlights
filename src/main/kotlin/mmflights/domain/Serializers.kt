package mmflights.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Tiny string-based serializers so the domain can use java.time / BigDecimal directly. */
private fun <T> stringSerializer(name: String, parse: (String) -> T, show: (T) -> String = { it.toString() }) =
    object : KSerializer<T> {
        override val descriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(show(value))
        override fun deserialize(decoder: Decoder): T = parse(decoder.decodeString())
    }

object BigDecimalSerializer : KSerializer<BigDecimal> by stringSerializer("BigDecimal", ::BigDecimal, BigDecimal::toPlainString)
object LocalDateSerializer : KSerializer<LocalDate> by stringSerializer("LocalDate", LocalDate::parse)
object InstantSerializer : KSerializer<Instant> by stringSerializer("Instant", Instant::parse)
