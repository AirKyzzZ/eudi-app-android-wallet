package eu.europa.ec.networklogic.util

import eu.europa.ec.networklogic.model.VeranaDidDocument
import eu.europa.ec.networklogic.model.VeranaVerificationMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLDecoder

object VeranaDids {
    private const val DID_WEB = "did:web:"
    private const val DID_WEBVH = "did:webvh:"
    private val DIDS_WITHOUT_DOCUMENT = listOf("did:key:", "did:jwk:")

    fun hasDidDocument(did: String): Boolean = DIDS_WITHOUT_DOCUMENT.none { did.startsWith(it) }

    fun isWebvh(did: String): Boolean = did.startsWith(DID_WEBVH)

    fun documentUrl(did: String): String? {
        val (identifier, fileName) = when {
            did.startsWith(DID_WEBVH) -> did.removePrefix(DID_WEBVH).split(':').drop(1) to "did.jsonl"
            did.startsWith(DID_WEB) -> did.removePrefix(DID_WEB).split(':') to "did.json"
            else -> return null
        }
        return decodeSegments(identifier)
            ?.takeIf { segments -> segments.firstOrNull()?.isNotBlank() == true }
            ?.let { segments ->
                val path = segments.drop(1).joinToString(separator = "") { "$it/" }.ifEmpty { ".well-known/" }
                "https://${segments.first()}/$path$fileName"
            }
    }

    fun lastLogEntry(log: String): String? = log.lineSequence().lastOrNull { it.isNotBlank() }

    fun logEntryState(entry: JsonElement?): JsonElement? = (entry as? JsonObject)?.get("state")

    fun parseDocument(did: String, element: JsonElement?): VeranaDidDocument? {
        val document = element as? JsonObject ?: return null
        if (document.string("id") != did) return null

        val verificationMethods = (document["verificationMethod"] as? JsonArray)
            ?.mapNotNull { it.toVerificationMethod(did) }
            .orEmpty()
        return VeranaDidDocument(
            id = did,
            assertionMethods = document.relationship("assertionMethod", did, verificationMethods),
            authenticationMethods = document.relationship("authentication", did, verificationMethods),
        )
    }

    private fun decodeSegments(segments: List<String>): List<String>? = runCatching {
        segments.map { URLDecoder.decode(it, Charsets.UTF_8.name()) }
    }.getOrNull()

    private fun JsonObject.relationship(
        name: String,
        did: String,
        verificationMethods: List<VeranaVerificationMethod>,
    ): List<VeranaVerificationMethod> = (get(name) as? JsonArray).orEmpty().flatMap { entry ->
        val reference = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (reference != null) {
            val id = absoluteId(did, reference)
            verificationMethods.filter { it.id == id }
        } else {
            listOfNotNull(entry.toVerificationMethod(did))
        }
    }

    private fun JsonElement.toVerificationMethod(did: String): VeranaVerificationMethod? {
        val method = this as? JsonObject ?: return null
        val id = method.string("id") ?: return null
        return VeranaVerificationMethod(
            id = absoluteId(did, id),
            type = method.string("type"),
            publicKeyJwk = method["publicKeyJwk"] as? JsonObject,
            publicKeyMultibase = method.string("publicKeyMultibase"),
        )
    }

    private fun absoluteId(did: String, id: String): String = if (id.startsWith("#")) "$did$id" else id

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
