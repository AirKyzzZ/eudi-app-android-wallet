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

import eu.europa.ec.corelogic.model.VeranaIssuerIdentity
import eu.europa.ec.corelogic.model.VeranaTrustEvidence
import eu.europa.ec.corelogic.model.VeranaTrustState
import eu.europa.ec.corelogic.verana.EvaluateVeranaTrust
import eu.europa.ec.corelogic.verana.VeranaX509
import eu.europa.ec.corelogic.verana.VerifyVeranaIssuerMetadata
import eu.europa.ec.networklogic.model.VeranaTrustResolution.Companion.unresolved
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import eu.europa.ec.networklogic.model.VeranaUntrustedReason
import eu.europa.ec.networklogic.repository.VeranaDocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import java.security.cert.X509Certificate

interface VeranaTrustController {
    fun issuerTrust(credentialIssuer: String, vct: String?): Flow<VeranaTrustState>
    fun verifierTrust(certificates: List<X509Certificate>, vct: String?): Flow<VeranaTrustState>
    val latestIssuerState: StateFlow<VeranaTrustState?>
    val latestVerifierState: StateFlow<VeranaTrustState?>
    fun clearVerifierState()
}

class VeranaTrustControllerImpl(
    private val documentRepository: VeranaDocumentRepository,
    private val verifyVeranaIssuerMetadata: VerifyVeranaIssuerMetadata,
    private val evaluateVeranaTrust: EvaluateVeranaTrust,
) : VeranaTrustController {

    private val issuerState = MutableStateFlow<VeranaTrustState?>(null)
    override val latestIssuerState: StateFlow<VeranaTrustState?> = issuerState.asStateFlow()

    private val verifierState = MutableStateFlow<VeranaTrustState?>(null)
    override val latestVerifierState: StateFlow<VeranaTrustState?> = verifierState.asStateFlow()

    override fun clearVerifierState() {
        verifierState.value = null
    }

    override fun issuerTrust(credentialIssuer: String, vct: String?): Flow<VeranaTrustState> = flow {
        emit(VeranaTrustState.Resolving(role = VeranaTrustRole.ISSUER, did = null))

        val identity = documentRepository.fetchSignedIssuerMetadata(credentialIssuer)
            ?.let { jwt -> verifyVeranaIssuerMetadata(jwt, credentialIssuer) }
            ?: VeranaIssuerIdentity.NoDid

        when (identity) {
            VeranaIssuerIdentity.NoDid -> emit(VeranaTrustState.NoDid)
            is VeranaIssuerIdentity.NotProven -> emit(notProven(VeranaTrustRole.ISSUER, identity.did, vct))
            is VeranaIssuerIdentity.Unavailable -> emit(
                evaluated(VeranaTrustRole.ISSUER, identity.did, vct, VeranaTrustStatus.UNVERIFIED, reason = null)
            )
            is VeranaIssuerIdentity.Signed -> {
                emit(VeranaTrustState.Resolving(role = VeranaTrustRole.ISSUER, did = identity.did))
                val evidence = evaluateVeranaTrust(
                    role = VeranaTrustRole.ISSUER,
                    did = identity.did,
                    vct = vct,
                    certificateKey = identity.certificateKey,
                )
                emit(VeranaTrustState.Evaluated(evidence))
            }
        }
    }.onEach { issuerState.value = it }

    override fun verifierTrust(certificates: List<X509Certificate>, vct: String?): Flow<VeranaTrustState> = flow {
        val leaf = certificates.firstOrNull()
        val did = leaf?.let(VeranaX509::didOf)
        if (leaf == null || did == null) {
            emit(VeranaTrustState.NoDid)
            return@flow
        }

        emit(VeranaTrustState.Resolving(role = VeranaTrustRole.VERIFIER, did = did))
        val key = VeranaX509.p256Key(leaf)
        if (key == null) {
            emit(notProven(VeranaTrustRole.VERIFIER, did, vct))
            return@flow
        }
        val evidence = evaluateVeranaTrust(
            role = VeranaTrustRole.VERIFIER,
            did = did,
            vct = vct,
            certificateKey = key,
        )
        emit(VeranaTrustState.Evaluated(evidence))
    }.onEach { verifierState.value = it }

    private fun notProven(role: VeranaTrustRole, did: String, vct: String?) =
        evaluated(role, did, vct, VeranaTrustStatus.UNTRUSTED, VeranaUntrustedReason.DID_NOT_PROVEN)

    private fun evaluated(
        role: VeranaTrustRole,
        did: String,
        vct: String?,
        status: VeranaTrustStatus,
        reason: VeranaUntrustedReason?,
    ) = VeranaTrustState.Evaluated(
        VeranaTrustEvidence(
            role = role,
            did = did,
            vct = vct,
            resolution = unresolved(did, status, reason),
            accreditation = null,
        )
    )
}
