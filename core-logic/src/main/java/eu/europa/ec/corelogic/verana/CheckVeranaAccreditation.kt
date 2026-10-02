package eu.europa.ec.corelogic.verana

import eu.europa.ec.corelogic.model.VeranaAccreditation
import eu.europa.ec.corelogic.model.VeranaAccreditationReason
import eu.europa.ec.corelogic.model.VeranaAccreditationStatus
import eu.europa.ec.networklogic.model.VERANA_NETWORKS
import eu.europa.ec.networklogic.model.VeranaNetwork
import eu.europa.ec.networklogic.model.VeranaSchemaRef
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import eu.europa.ec.networklogic.repository.VeranaDocumentRepository
import eu.europa.ec.networklogic.repository.VeranaIndexerRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.time.Instant

fun interface CheckVeranaAccreditation {
    suspend operator fun invoke(
        did: String,
        role: VeranaTrustRole,
        vct: String?,
    ): VeranaAccreditation
}

class CheckVeranaAccreditationImpl(
    private val documentRepository: VeranaDocumentRepository,
    private val indexerRepository: VeranaIndexerRepository,
    private val resolveVeranaTrust: ResolveVeranaTrust,
    private val networks: List<VeranaNetwork> = VERANA_NETWORKS,
    private val clock: () -> Instant = Instant::now,
) : CheckVeranaAccreditation {

    override suspend fun invoke(
        did: String,
        role: VeranaTrustRole,
        vct: String?,
    ): VeranaAccreditation = check(did, role, vct) ?: VeranaAccreditation(
        status = VeranaAccreditationStatus.UNDETERMINED,
        reason = VeranaAccreditationReason.REGISTRY_UNREACHABLE,
    )

    private suspend fun check(did: String, role: VeranaTrustRole, vct: String?): VeranaAccreditation? {
        if (vct == null || !vct.isHttpsUrl()) return refused(VeranaAccreditationReason.NO_VERANA_SCHEMA)

        val typeMetadata = documentRepository.fetchJson(vct) ?: return null
        val credentialName = (typeMetadata as? JsonObject)?.string("name")
        val vtjscId = (typeMetadata as? JsonObject)?.string("relatedJsonSchemaCredentialId")
            ?.takeIf { it.isHttpsUrl() }
            ?: return refused(VeranaAccreditationReason.NO_VERANA_SCHEMA, credentialName)

        val vtjsc = documentRepository.fetchJson(vtjscId) ?: return null
        return checkSchemaCredential(
            did = did,
            role = role,
            vtjsc = vtjsc as? JsonObject,
            credentialName = credentialName,
        )
    }

    private suspend fun checkSchemaCredential(
        did: String,
        role: VeranaTrustRole,
        vtjsc: JsonObject?,
        credentialName: String?,
    ): VeranaAccreditation? {
        val vtjscIssuer = vtjsc?.let(DataIntegrity::issuerOf)
        if (vtjsc == null || vtjscIssuer == null) {
            return refused(VeranaAccreditationReason.SCHEMA_CREDENTIAL_MALFORMED, credentialName)
        }
        val issuerDocument = documentRepository.fetchDidDocument(vtjscIssuer) ?: return null
        if (!DataIntegrity.verifyEddsaJcs2022(vtjsc, issuerDocument) || !DataIntegrity.withinValidity(vtjsc, clock())) {
            return refused(VeranaAccreditationReason.SCHEMA_CREDENTIAL_INVALID, credentialName)
        }

        val schemaRef = VeranaSchemaRef.parse(vtjsc.jsonSchemaRef(), networks)
            ?: return refused(VeranaAccreditationReason.UNKNOWN_NETWORK, credentialName)
        return checkParticipant(
            did = did,
            role = role,
            schemaRef = schemaRef,
            vtjscIssuer = vtjscIssuer,
            credentialName = credentialName,
        )
    }

    private suspend fun checkParticipant(
        did: String,
        role: VeranaTrustRole,
        schemaRef: VeranaSchemaRef,
        vtjscIssuer: String,
        credentialName: String?,
    ): VeranaAccreditation? {
        val (ecosystemDid, authorized) = coroutineScope {
            val ecosystemDid = async { schemaEcosystemDid(schemaRef) }
            val authorized = async {
                indexerRepository.hasActiveParticipant(schemaRef.network, did, role, schemaRef.schemaId)
            }
            ecosystemDid.await() to authorized.await()
        }
        if (ecosystemDid == null || authorized == null) return null
        if (ecosystemDid != vtjscIssuer) {
            return refused(VeranaAccreditationReason.ECOSYSTEM_MISMATCH, credentialName)
        }

        return VeranaAccreditation(
            status = if (authorized) VeranaAccreditationStatus.GRANTED else VeranaAccreditationStatus.REFUSED,
            reason = if (authorized) {
                VeranaAccreditationReason.ACTIVE_PARTICIPANT
            } else {
                VeranaAccreditationReason.NO_ACTIVE_PARTICIPANT
            },
            credentialName = credentialName,
            ecosystemName = ecosystemName(ecosystemDid),
            schemaId = schemaRef.schemaId,
            networkId = schemaRef.network.id,
        )
    }

    private suspend fun schemaEcosystemDid(schemaRef: VeranaSchemaRef): String? {
        val ecosystemId = indexerRepository.fetchSchemaEcosystemId(schemaRef.network, schemaRef.schemaId)
            ?: return null
        return indexerRepository.fetchEcosystemDid(schemaRef.network, ecosystemId)
    }

    private suspend fun ecosystemName(ecosystemDid: String): String? {
        val resolution = resolveVeranaTrust(ecosystemDid)
        if (resolution.status != VeranaTrustStatus.TRUSTED) return null
        return resolution.credentials
            .firstOrNull { it.ecsSchema == SERVICE_CREDENTIAL }
            ?.claims
            ?.firstOrNull { it.name == "name" }
            ?.values
            ?.singleOrNull()
    }

    private fun refused(reason: VeranaAccreditationReason, credentialName: String? = null) = VeranaAccreditation(
        status = VeranaAccreditationStatus.REFUSED,
        reason = reason,
        credentialName = credentialName,
    )

    private fun JsonObject.jsonSchemaRef(): String? =
        ((get("credentialSubject") as? JsonObject)?.get("jsonSchema") as? JsonObject)?.string("\$ref")

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun String.isHttpsUrl(): Boolean = runCatching {
        val uri = URI(this)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private companion object {
        const val SERVICE_CREDENTIAL = "ServiceCredential"
    }
}
