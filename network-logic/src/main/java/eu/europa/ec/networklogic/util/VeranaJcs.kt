package eu.europa.ec.networklogic.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object VeranaJcs {
    fun canonicalize(element: JsonElement): String {
        return when (element) {
            is JsonNull -> "null"
            is JsonPrimitive -> {
                if (element.isString) {
                    "\"" + element.content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
                } else {
                    element.content
                }
            }
            is JsonArray -> "[" + element.joinToString(",") { canonicalize(it) } + "]"
            is JsonObject -> "{" + element.entries.sortedBy { it.key }.joinToString(",") { 
                "\"" + it.key.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\":" + canonicalize(it.value)
            } + "}"
        }
    }
}
