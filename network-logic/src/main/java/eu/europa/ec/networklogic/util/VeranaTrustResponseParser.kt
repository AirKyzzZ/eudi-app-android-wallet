package eu.europa.ec.networklogic.util

import eu.europa.ec.networklogic.model.VeranaIndexerAnswer
import eu.europa.ec.networklogic.model.VeranaNetwork
import eu.europa.ec.networklogic.model.VeranaTrustClaim
import eu.europa.ec.networklogic.model.VeranaTrustCredential
import eu.europa.ec.networklogic.model.VeranaTrustResolution
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import eu.europa.ec.networklogic.model.VeranaUntrustedReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI

object VeranaTrustResponseParser {

    fun parseJson(body: String): JsonElement? = runCatching { Json.parseToJsonElement(body) }.getOrNull()

    fun parseResolveAnswer(
        status: Int,
        body: String,
        did: String,
        network: VeranaNetwork,
    ): VeranaIndexerAnswer {
        val json = parseJson(body) as? JsonObject
        return when {
            status == HTTP_NOT_FOUND && json?.strictString("error") == DID_NOT_FOUND -> VeranaIndexerAnswer.NotRegistered
            status != HTTP_OK -> VeranaIndexerAnswer.Unanswered
            else -> json?.toResolution(did, network)
                ?.let(VeranaIndexerAnswer::Resolved)
                ?: VeranaIndexerAnswer.Unanswered
        }
    }

    fun parseEcosystemDid(body: String): String? =
        ((parseJson(body) as? JsonObject)?.get("ecosystem") as? JsonObject)?.strictString("did")

    fun parseSchemaEcosystemId(body: String): Long? =
        ((parseJson(body) as? JsonObject)?.get("schema") as? JsonObject)?.strictLong("ecosystem_id")

    fun parseParticipantGranted(
        body: String,
        did: String,
        role: VeranaTrustRole,
        schemaId: String,
    ): Boolean? {
        val participants = (parseJson(body) as? JsonObject)?.get("participants") as? JsonArray ?: return null
        return participants.any { element ->
            val participant = element as? JsonObject ?: return@any false
            participant.strictString("did") == did &&
                    participant.strictString("role") == role.name &&
                    (participant["schema_id"] as? JsonPrimitive)?.contentOrNull == schemaId &&
                    participant.strictString("participant_state") == ACTIVE
        }
    }

    private fun JsonObject.toResolution(did: String, network: VeranaNetwork): VeranaTrustResolution? {
        if (strictString("did") != did) return null
        val trusted = strictBoolean("trusted") ?: return null

        return VeranaTrustResolution(
            did = did,
            status = if (trusted) VeranaTrustStatus.TRUSTED else VeranaTrustStatus.UNTRUSTED,
            reason = if (trusted) null else VeranaUntrustedReason.NOT_TRUSTED,
            network = network,
            evaluatedAt = strictString("evaluatedAtTime"),
            expiresAt = strictString("expiresAtTime"),
            credentials = (get("ecsCredentials") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.toEcsCredential() }
                .orEmpty(),
            unresolvableCredentialIds = (get("presentations") as? JsonArray).orEmpty().flatMap { presentation ->
                ((presentation as? JsonObject)?.get("unresolvableCredentialIds") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { id -> id.isString }?.content }
            },
        )
    }

    private fun JsonObject.toEcsCredential(): VeranaTrustCredential? {
        val ecsSchema = strictString("ecsSchema") ?: return null
        val subject = get("credentialSubject") as? JsonObject ?: return null
        return VeranaTrustCredential(
            ecsSchema = ecsSchema,
            ecosystemId = strictLong("ecosystemId"),
            id = strictString("id"),
            claims = subject.toAllowlistedClaims(),
        )
    }

    private fun JsonObject.toAllowlistedClaims(): List<VeranaTrustClaim> = entries.mapNotNull { (name, element) ->
        if (name !in ALLOWED_CLAIMS) return@mapNotNull null

        val values = if (name in INTEGER_CLAIMS) {
            (element as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.intOrNull
                ?.takeIf { it >= 0 }
                ?.let { listOf(it.toString()) }
        } else {
            when (element) {
                is JsonPrimitive -> element.takeIf { it.isString }?.contentOrNull?.let(::listOf)
                is JsonArray -> element.map { item ->
                    (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
                }

                else -> null
            }
        }?.filter { it.isNotBlank() }.orEmpty()
        if (values.isEmpty()) return@mapNotNull null

        val safeHttpUrl = if (name in HTTP_URL_CLAIMS) {
            values.singleOrNull()?.toSafeHttpUrl() ?: return@mapNotNull null
        } else {
            null
        }
        VeranaTrustClaim(
            name = name,
            values = values,
            safeHttpUrl = safeHttpUrl,
        )
    }.sortedBy { it.name }

    private fun JsonObject.strictString(name: String): String? =
        (get(name) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.strictBoolean(name: String): Boolean? =
        (get(name) as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.booleanOrNull

    private fun JsonObject.strictLong(name: String): Long? =
        (get(name) as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.longOrNull

    private fun String.toSafeHttpUrl(): String? = runCatching {
        val uri = URI(this)
        takeIf { uri.scheme?.lowercase() in HTTP_SCHEMES && !uri.host.isNullOrBlank() }
    }.getOrNull()

    private const val HTTP_OK = 200
    private const val HTTP_NOT_FOUND = 404
    private const val DID_NOT_FOUND = "DID not found"
    private const val ACTIVE = "ACTIVE"
    private val ALLOWED_CLAIMS = setOf(
        "name",
        "type",
        "description",
        "descriptionFormat",
        "logoUri",
        "logoDigestSri",
        "minimumAgeRequired",
        "termsAndConditionsUri",
        "termsAndConditionsDigestSri",
        "privacyPolicyUri",
        "privacyPolicyDigestSri",
        "address",
        "registryId",
        "registryUri",
        "countryCode",
        "legalJurisdiction",
        "organizationKind",
        "lei",
        "id",
    )
    private val INTEGER_CLAIMS = setOf("minimumAgeRequired")
    private val HTTP_URL_CLAIMS = setOf(
        "logoUri",
        "privacyPolicyUri",
        "termsAndConditionsUri",
        "registryUri",
    )
    private val HTTP_SCHEMES = setOf("http", "https")
}
