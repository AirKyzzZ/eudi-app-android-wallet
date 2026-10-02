package eu.europa.ec.networklogic.repository

import eu.europa.ec.networklogic.model.VeranaIndexerAnswer
import eu.europa.ec.networklogic.model.VeranaNetwork
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.util.VeranaHttpClient
import eu.europa.ec.networklogic.util.VeranaTrustResponseParser
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

interface VeranaIndexerRepository {
    suspend fun resolve(network: VeranaNetwork, did: String): VeranaIndexerAnswer

    suspend fun fetchEcosystemDid(network: VeranaNetwork, ecosystemId: Long): String?

    suspend fun fetchSchemaEcosystemId(network: VeranaNetwork, schemaId: String): Long?

    suspend fun hasActiveParticipant(
        network: VeranaNetwork,
        did: String,
        role: VeranaTrustRole,
        schemaId: String,
    ): Boolean?
}

class VeranaIndexerRepositoryImpl(
    private val httpClient: VeranaHttpClient,
) : VeranaIndexerRepository {

    override suspend fun resolve(network: VeranaNetwork, did: String): VeranaIndexerAnswer {
        val response = httpClient.postJson(
            url = "${network.indexerUrl}/v4/verifiable-trust/resolve",
            body = resolveRequest(did),
        ) ?: return VeranaIndexerAnswer.Unanswered
        return VeranaTrustResponseParser.parseResolveAnswer(
            status = response.status,
            body = response.body,
            did = did,
            network = network,
        )
    }

    override suspend fun fetchEcosystemDid(network: VeranaNetwork, ecosystemId: Long): String? =
        getOk("${network.indexerUrl}/v4/ecosystem/get/$ecosystemId")
            ?.let(VeranaTrustResponseParser::parseEcosystemDid)

    override suspend fun fetchSchemaEcosystemId(network: VeranaNetwork, schemaId: String): Long? =
        getOk("${network.indexerUrl}/v4/credential-schema/get/$schemaId")
            ?.let(VeranaTrustResponseParser::parseSchemaEcosystemId)

    override suspend fun hasActiveParticipant(
        network: VeranaNetwork,
        did: String,
        role: VeranaTrustRole,
        schemaId: String,
    ): Boolean? {
        val query = "did=${URLEncoder.encode(did, Charsets.UTF_8.name())}&role=${role.name}" +
                "&schema_id=$schemaId&participant_state=ACTIVE"
        return getOk("${network.indexerUrl}/v4/participant/list?$query")?.let { body ->
            VeranaTrustResponseParser.parseParticipantGranted(
                body = body,
                did = did,
                role = role,
                schemaId = schemaId,
            )
        }
    }

    private suspend fun getOk(url: String): String? =
        httpClient.get(url)?.takeIf { it.status == HTTP_OK }?.body

    private fun resolveRequest(did: String): String = buildJsonObject {
        put("did", did)
        putJsonObject("participations") {
            putJsonArray("states") {
                add("ACTIVE")
                add("EXPIRED")
                add("REVOKED")
            }
        }
        putJsonObject("presentations") {
            put("unresolvableCredentialIds", true)
        }
        put("ecsCredentials", true)
    }.toString()

    private companion object {
        const val HTTP_OK = 200
    }
}
