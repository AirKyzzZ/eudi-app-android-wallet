/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.corelogic.controller

import eu.europa.ec.networklogic.model.VeranaAccreditation
import eu.europa.ec.networklogic.model.VeranaEcsCredential
import eu.europa.ec.networklogic.model.VeranaRole
import eu.europa.ec.networklogic.model.VeranaTrustResolution
import eu.europa.ec.networklogic.repository.VeranaTrustRepository
import eu.europa.ec.networklogic.repository.didDomain
import eu.europa.ec.networklogic.util.VeranaJws
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive

enum class VeranaVerdict { RESOLVING, TRUSTED, UNTRUSTED, UNVERIFIED }

data class VeranaTrustState(
    val verdict: VeranaVerdict,
    val sentence: String,
    val did: String? = null,
    val serviceName: String? = null,
    val operatorName: String? = null,
    val serviceCredentialPresented: Boolean = false,
    val operatorCredentialPresented: Boolean = false,
    val serviceSelfIssued: Boolean = false,
    val operatorSelfIssued: Boolean = false,
    val serviceDescription: String? = null,
    val operatorAddress: String? = null,
    val operatorCountryCode: String? = null,
    val operatorRegistryId: String? = null,
    val minimumAgeRequired: Int? = null,
    val hasTerms: Boolean = false,
    val hasPrivacyPolicy: Boolean = false,
    val credentialName: String? = null,
    val accreditation: VeranaAccreditation? = null,
    val production: Boolean = true,
) {
    val allowAction: Boolean
        get() = verdict == VeranaVerdict.TRUSTED && accreditation?.granted == true

    companion object {
        val resolving = VeranaTrustState(
            verdict = VeranaVerdict.RESOLVING,
            sentence = "Checking the Verana public registry…",
        )

        fun unverified(did: String? = null) = VeranaTrustState(
            verdict = VeranaVerdict.UNVERIFIED,
            sentence = "The Verana resolver could not be reached. This counterparty is neither trusted nor untrusted.",
            did = did,
        )
    }
}

interface VeranaTrustController {
    fun issuerTrust(credentialIssuerUrl: String, vct: String?): Flow<VeranaTrustState>
    fun verifierTrust(certificates: List<X509Certificate>, vct: String?): Flow<VeranaTrustState>
    val latestVerifierState: StateFlow<VeranaTrustState?>
    val latestIssuerState: StateFlow<VeranaTrustState?>
    fun clearVerifierState()
}

class VeranaTrustControllerImpl(
    private val repository: VeranaTrustRepository,
) : VeranaTrustController {

    private val verifierState = MutableStateFlow<VeranaTrustState?>(null)
    override val latestVerifierState: StateFlow<VeranaTrustState?> = verifierState

    private val issuerState = MutableStateFlow<VeranaTrustState?>(null)
    override val latestIssuerState: StateFlow<VeranaTrustState?> = issuerState

    override fun clearVerifierState() {
        verifierState.value = null
    }

    override fun issuerTrust(credentialIssuerUrl: String, vct: String?): Flow<VeranaTrustState> =
        flow {
            emit(VeranaTrustState.resolving.also { issuerState.value = it })

            val metadata = repository.fetchSignedIssuerMetadata(credentialIssuerUrl)
            if (metadata == null) {
                emit(VeranaTrustState.unverified().also { issuerState.value = it })
                return@flow
            }
            if (!metadata.signatureVerified) {
                emit(untrusted(metadata.did, credentials = null).also { issuerState.value = it })
                return@flow
            }

            emit(evaluate(metadata.did, VeranaRole.ISSUER, vct).also { issuerState.value = it })
        }

    override fun verifierTrust(certificates: List<X509Certificate>, vct: String?): Flow<VeranaTrustState> =
        flow {
            emit(VeranaTrustState.resolving.also { verifierState.value = it })

            val leaf = certificates.firstOrNull()
            val did = leaf?.let(::sanDid)
            if (leaf == null || did == null) {
                emit(VeranaTrustState.unverified().also { verifierState.value = it })
                return@flow
            }

            if (!certificateBindsDid(leaf, did)) {
                emit(untrusted(did, credentials = null).also { verifierState.value = it })
                return@flow
            }

            emit(evaluate(did, VeranaRole.VERIFIER, vct).also { verifierState.value = it })
        }

    private suspend fun evaluate(did: String, role: VeranaRole, vct: String?): VeranaTrustState {
        val resolution = repository.resolveTrust(did)
            ?: return VeranaTrustState.unverified(did)

        val accreditation = vct?.let { resolveAccreditation(did, role, it) }
        return stateFrom(did, resolution, accreditation)
    }

    private fun stateFrom(
        did: String,
        resolution: VeranaTrustResolution,
        accreditation: VeranaAccreditation?,
    ): VeranaTrustState {
        val credentials = resolution.ecsCredentials
        val service = credentials.findEcs("ServiceCredential")
        val organization = credentials.findEcs("OrganizationCredential", "PersonaCredential")
        
        val verdict = when (resolution.trustStatus) {
            "TRUSTED" -> VeranaVerdict.TRUSTED
            "UNVERIFIED" -> VeranaVerdict.UNVERIFIED
            else -> VeranaVerdict.UNTRUSTED
        }

        return VeranaTrustState(
            verdict = verdict,
            sentence = sentenceFor(verdict, service != null, service, organization),
            did = did,
            serviceName = service?.claimText("name"),
            operatorName = organization?.claimText("name"),
            serviceCredentialPresented = service != null,
            operatorCredentialPresented = organization != null,
            serviceSelfIssued = false, // removed in v4
            operatorSelfIssued = false, // removed in v4
            serviceDescription = service?.claimText("description"),
            operatorAddress = organization?.claimText("address"),
            operatorCountryCode = organization?.claimText("countryCode")?.uppercase(),
            operatorRegistryId = organization?.claimText("registryId"),
            minimumAgeRequired = service?.claimInt("minimumAgeRequired"),
            hasTerms = service?.hasAsset("termsAndConditions") == true,
            hasPrivacyPolicy = service?.hasAsset("privacyPolicy") == true,
            accreditation = accreditation,
            production = resolution.network?.production ?: true,
        )
    }

    private fun untrusted(did: String, credentials: List<VeranaEcsCredential>?): VeranaTrustState {
        val service = credentials.findEcs("ServiceCredential")
        val organization = credentials.findEcs("OrganizationCredential", "PersonaCredential")
        return VeranaTrustState(
            verdict = VeranaVerdict.UNTRUSTED,
            sentence = "The Verana public registry does not vouch for this service.",
            did = did,
            serviceCredentialPresented = service != null,
            operatorCredentialPresented = organization != null,
            serviceSelfIssued = false,
            operatorSelfIssued = false,
        )
    }

    private fun sentenceFor(
        verdict: VeranaVerdict,
        serviceValid: Boolean,
        service: VeranaEcsCredential?,
        organization: VeranaEcsCredential?,
    ): String = when (verdict) {
        VeranaVerdict.TRUSTED -> "Both identity credentials verified against the Verana public registry"
        VeranaVerdict.UNVERIFIED -> "The Verana resolver could not be reached. This counterparty is neither trusted nor untrusted."
        else ->
            if (service != null || organization != null) {
                "The Verana public registry does not vouch for this service."
            } else {
                "Neither identity credential verified. This counterparty cannot present verifiable trust credentials."
            }
    }

    private suspend fun resolveAccreditation(
        did: String,
        role: VeranaRole,
        vct: String,
    ): VeranaAccreditation? {
        val typeMetadata = repository.fetchDidDocument("dummy") // Not used directly, repo handles it
        val schemaEcoDid = repository.resolveSchemaEcosystemDid(vct)
            ?: return VeranaAccreditation(role = role, granted = false, reason = "This credential type belongs to a network this wallet does not know.")
        
        val ecoName = repository.resolveEcosystemName(schemaEcoDid)
        
        val authorized = repository.holdsActiveParticipant(did, role.name, vct)
        val reason = if (authorized) {
            "An active ${role.name.lowercase()} participant covers this schema."
        } else {
            "No active ${role.name.lowercase()} participant for this schema."
        }
        
        return VeranaAccreditation(role = role, granted = authorized, reason = reason)
    }

    private suspend fun certificateBindsDid(leaf: X509Certificate, did: String): Boolean {
        val document = repository.fetchDidDocument(didDomain(did)) ?: return false
        val certificateKey = leaf.publicKey as? ECPublicKey ?: return false

        sanDns(leaf)?.let { dns -> if (dns != didDomain(did)) return false }

        return VeranaJws.p256Keys(document).any { (_, key) ->
            key.w.affineX == certificateKey.w.affineX && key.w.affineY == certificateKey.w.affineY
        }
    }

    private fun sanDid(certificate: X509Certificate): String? =
        certificate.subjectAlternativeNames
            ?.firstOrNull { it.size >= 2 && it[0] == 6 && (it[1] as? String)?.startsWith("did:") == true }
            ?.get(1) as? String

    private fun sanDns(certificate: X509Certificate): String? =
        certificate.subjectAlternativeNames
            ?.firstOrNull { it.size >= 2 && it[0] == 2 && it[1] is String }
            ?.get(1) as? String

    private fun List<VeranaEcsCredential>?.findEcs(vararg types: String): VeranaEcsCredential? =
        this?.firstOrNull { it.ecsSchema in types }

    private fun VeranaEcsCredential.claimText(key: String): String? =
        (claims?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

    private fun VeranaEcsCredential.claimInt(key: String): Int? =
        (claims?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()

    private fun VeranaEcsCredential.hasAsset(base: String): Boolean =
        claimText("${base}Uri") != null || claimText(base) != null
}
