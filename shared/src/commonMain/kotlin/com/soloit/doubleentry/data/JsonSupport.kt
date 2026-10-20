package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Money
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal

/**
 * Serializes cents as an exact JSON decimal number (`123.45`), matching Postgres `numeric(18,2)`
 * and .NET `decimal`. Reading goes through [BigDecimal] so no precision is lost to doubles.
 */
object DecimalCentsSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DecimalCents", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) {
        val text = Money.toDecimal(value).toPlainString()
        if (encoder is JsonEncoder) encoder.encodeJsonElement(JsonUnquotedLiteral(text)) else encoder.encodeString(text)
    }

    override fun deserialize(decoder: Decoder): Long {
        val text = if (decoder is JsonDecoder) (decoder.decodeJsonElement() as JsonPrimitive).content else decoder.decodeString()
        return Money.fromDecimal(BigDecimal(text.trim()))
    }
}

/** Accepts enum names (`"Asset"`) as well as legacy ordinals (`0`); writes names. */
abstract class LenientEnumSerializer<E : Enum<E>>(
    name: String,
    private val values: List<E>,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): E {
        val primitive = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
        val raw = primitive?.content ?: decoder.decodeString()
        primitive?.intOrNull?.let { ordinal -> values.getOrNull(ordinal)?.let { return it } }
        return values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw SerializationException("Unknown value '$raw'.")
    }
}

object AccountTypeSerializer : LenientEnumSerializer<AccountType>("AccountType", AccountType.entries)

object EntryTypeSerializer : LenientEnumSerializer<EntryType>("EntryType", EntryType.entries)
