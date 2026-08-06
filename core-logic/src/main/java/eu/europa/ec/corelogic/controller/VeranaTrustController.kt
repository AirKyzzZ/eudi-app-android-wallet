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
import eu.europa.ec.networklogic.model.VeranaPermission
import eu.europa.ec.networklogic.model.VeranaRole
import eu.europa.ec.networklogic.model.VeranaTrustCredential
import eu.europa.ec.networklogic.model.VeranaTrustResolution
import eu.europa.ec.networklogic.repository.VeranaTrustRepository
import eu.europa.ec.networklogic.repository.didDomain
import eu.europa.ec.networklogic.util.VeranaJws
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

enum class VeranaVerdict { RESOLVING, TRUSTED, PARTIAL, UNTRUSTED, UNVERIFIED }

data class VeranaTrustState(
    val verdict: VeranaVerdict,
    val sentence: String,
    val did: String? = null,
    val serviceName: String? = null,
    val operatorName: String? = null,
    val serviceCredentialPresented: Boolean = false,
    val operatorCredentialPresented: Boolean = false,
    val accreditation: VeranaAccreditation? = null,
    val production: Boolean = true,
) {
    /**
     * The action is blocked on UNTRUSTED, on a failed accreditation, and while any check is in
     * flight. It is never blocked on could-not-determine alone: an unreachable resolver renders
     * grey, not as a refusal.
     */
    val allowAction: Boolean
        get() = when (verdict) {
            VeranaVerdict.RESOLVING, VeranaVerdict.UNTRUSTED -> false
            VeranaVerdict.TRUSTED, VeranaVerdict.PARTIAL -> accreditation?.granted != false
            VeranaVerdict.UNVERIFIED -> true
        }

    companion object {
        val resolving = VeranaTrustState(
            verdict = VeranaVerdict.RESOLVING,
            sentence = "Checking the Verana public registry…",
        )

        fun unverified(did: String? = null) = VeranaTrustState(
            verdict = VeranaVerdict.UNVERIFIED,
            sentence = "The Verana resolver could not be reached. " +
                "This counterparty is neither trusted nor untrusted.",
            did = did,
        )
    }
}

interface VeranaTrustController {

    fun issuerTrust(credentialIssuerUrl: String, vct: String?): Flow<VeranaTrustState>

    fun verifierTrust(certificates: List<X509Certificate>, vct: String?): Flow<VeranaTrustState>

    /** Latest verdicts, read again at act time so the gate guards the action, not the dialog. */
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
            // An unverifiable signature means the DID claim is hearsay; the registry must not be
            // quoted as vouching for a peer that has not proven control of that DID.
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
        val resolution = repository.resolveTrust(did, fullDetail = true)
            ?: return VeranaTrustState.unverified(did)

        val accreditation = vct?.let { resolveAccreditation(did, role, it) }
        return stateFrom(did, resolution, accreditation)
    }

    // Both mandatory identity credentials must verify inside Q1; exactly one is PARTIAL. The
    // resolver's own trustStatus agrees on the live cast, but the credential pair is the rule
    // the versioned card is written against.
    private fun stateFrom(
        did: String,
        resolution: VeranaTrustResolution,
        accreditation: VeranaAccreditation?,
    ): VeranaTrustState {
        val credentials = resolution.credentials
        val service = credentials.findEcs("ECS-SERVICE")
        val organization = credentials.findEcs("ECS-ORG", "ECS-ORGANIZATION", "ECS-PERSONA")
        val serviceValid = service?.isValid == true
        val organizationValid = organization?.isValid == true

        val verdict = when {
            serviceValid && organizationValid -> VeranaVerdict.TRUSTED
            serviceValid || organizationValid -> VeranaVerdict.PARTIAL
            else -> VeranaVerdict.UNTRUSTED
        }

        return VeranaTrustState(
            verdict = verdict,
            sentence = sentenceFor(verdict, serviceValid, service, organization),
            did = did,
            serviceName = service?.takeIf { serviceValid }?.claimName(),
            operatorName = organization?.takeIf { organizationValid }?.claimName(),
            serviceCredentialPresented = service != null,
            operatorCredentialPresented = organization != null,
            accreditation = accreditation,
            production = resolution.production,
        )
    }

    private fun untrusted(did: String, credentials: List<VeranaTrustCredential>?): VeranaTrustState =
        VeranaTrustState(
            verdict = VeranaVerdict.UNTRUSTED,
            sentence = "The Verana public registry does not vouch for this service.",
            did = did,
            serviceCredentialPresented = credentials?.findEcs("ECS-SERVICE") != null,
            operatorCredentialPresented = credentials
                ?.findEcs("ECS-ORG", "ECS-ORGANIZATION", "ECS-PERSONA") != null,
        )

    // Wording fixed by the versioned card at playground/public/trust-card/index.html: the same
    // evaluation must not read differently depending on which wallet rendered it.
    private fun sentenceFor(
        verdict: VeranaVerdict,
        serviceValid: Boolean,
        service: VeranaTrustCredential?,
        organization: VeranaTrustCredential?,
    ): String = when (verdict) {
        VeranaVerdict.TRUSTED -> "Both identity credentials verified against the Verana public registry"
        VeranaVerdict.PARTIAL ->
            if (serviceValid) "The service credential verified. Nothing verifies who operates it."
            else "The operator credential verified. Nothing verifies the service itself."
        else ->
            if (service != null || organization != null) {
                "The Verana public registry does not vouch for this service."
            } else {
                "Neither identity credential verified. " +
                    "This counterparty cannot present verifiable trust credentials."
            }
    }

    private suspend fun resolveAccreditation(
        did: String,
        role: VeranaRole,
        vct: String,
    ): VeranaAccreditation? {
        val schemaId = repository.resolveSchemaIdFromVct(vct)
            ?: repository.fetchSchemas()?.firstOrNull { it.vprId == vct }?.id
            ?: return null

        val permissions = repository.fetchPermissions() ?: return null
        return findAccreditation(permissions, did, schemaId, role)
    }

    private fun findAccreditation(
        permissions: List<VeranaPermission>,
        did: String,
        schemaId: String,
        role: VeranaRole,
    ): VeranaAccreditation {
        val forRole = permissions.filter {
            it.did == did && it.schemaId == schemaId && it.type == role.name
        }
        val live = forRole.firstOrNull(::isPermissionActive)
        if (live != null) {
            return VeranaAccreditation(role = role, granted = true, schemaId = schemaId, permissionId = live.id)
        }

        val reason = when {
            forRole.any { it.validationState == "PENDING" } ->
                "A ${role.name.lowercase()} permission for this schema is still pending validation"
            forRole.isNotEmpty() ->
                "A ${role.name.lowercase()} permission exists for this schema but is no longer in force"
            else -> "No ${role.name.lowercase()} permission for this schema"
        }
        return VeranaAccreditation(role = role, granted = false, schemaId = schemaId, reason = reason)
    }

    // A permission only counts while it is live: not revoked, not slashed, not terminated, and
    // inside its effective window. PENDING is an application under review, not a grant.
    private fun isPermissionActive(permission: VeranaPermission, at: Instant = Instant.now()): Boolean {
        if (permission.revoked != null || permission.slashed != null) return false
        if (permission.validationState == "TERMINATED" || permission.validationState == "PENDING") return false

        permission.effectiveFrom?.toInstantOrNull()?.let { if (it.isAfter(at)) return false }
        permission.effectiveUntil?.toInstantOrNull()?.let { if (!it.isAfter(at)) return false }
        return true
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

    private fun List<VeranaTrustCredential>?.findEcs(vararg types: String): VeranaTrustCredential? =
        this?.firstOrNull { it.ecsType in types }

    private fun VeranaTrustCredential.claimName(): String? =
        (claims?.get("name") as? JsonPrimitive)?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }

    private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
}
