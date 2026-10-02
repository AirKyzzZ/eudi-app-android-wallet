package eu.europa.ec.corelogic.verana

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import kotlin.math.abs

object Jcs {
    fun canonicalize(element: JsonElement): String = StringBuilder().apply { appendCanonical(element) }.toString()

    private fun StringBuilder.appendCanonical(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                append('{')
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    appendString(key)
                    append(':')
                    appendCanonical(element.getValue(key))
                }
                append('}')
            }

            is JsonArray -> {
                append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendCanonical(item)
                }
                append(']')
            }

            JsonNull -> append("null")
            is JsonPrimitive -> when {
                element.isString -> appendString(element.content)
                element.booleanOrNull != null -> append(element.content)
                else -> append(canonicalNumber(element.content))
            }
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u").append(character.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    // RFC 8785 mandates ECMAScript Number::toString for numbers.
    private fun canonicalNumber(literal: String): String {
        val value = literal.toDouble()
        require(value.isFinite()) { "JCS cannot represent $literal" }
        if (value == 0.0) return "0"

        val decimal = BigDecimal(value.toString()).stripTrailingZeros()
        val digits = decimal.unscaledValue().abs().toString()
        val digitCount = digits.length
        val pointPosition = digitCount - decimal.scale()
        val sign = if (value < 0) "-" else ""
        val body = when {
            pointPosition in digitCount..MAX_PLAIN_EXPONENT -> digits + "0".repeat(pointPosition - digitCount)
            pointPosition in 1..MAX_PLAIN_EXPONENT ->
                digits.substring(0, pointPosition) + "." + digits.substring(pointPosition)

            pointPosition in MIN_PLAIN_EXPONENT..0 -> "0." + "0".repeat(-pointPosition) + digits
            else -> {
                val exponent = pointPosition - 1
                val mantissa = if (digitCount == 1) digits else "${digits.first()}.${digits.substring(1)}"
                "${mantissa}e${if (exponent >= 0) "+" else "-"}${abs(exponent)}"
            }
        }
        return sign + body
    }

    private const val HEX_RADIX = 16
    private const val UNICODE_ESCAPE_DIGITS = 4
    private const val MAX_PLAIN_EXPONENT = 21
    private const val MIN_PLAIN_EXPONENT = -5
}
