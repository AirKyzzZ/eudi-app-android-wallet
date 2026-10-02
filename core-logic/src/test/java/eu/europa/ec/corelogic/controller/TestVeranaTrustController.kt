package eu.europa.ec.corelogic.controller

import eu.europa.ec.corelogic.model.VeranaAccreditation
import eu.europa.ec.corelogic.model.VeranaAccreditationReason
import eu.europa.ec.corelogic.model.VeranaAccreditationStatus
import eu.europa.ec.corelogic.model.VeranaTrustEvidence
import eu.europa.ec.corelogic.model.VeranaTrustState
import eu.europa.ec.corelogic.model.blocksAction
import eu.europa.ec.corelogic.verana.FakeDocumentRepository
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.CREDENTIAL_ISSUER
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.ISSUER_DID
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.VCT
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.VERIFIER_DID
import eu.europa.ec.corelogic.verana.VerifyVeranaIssuerMetadataImpl
import eu.europa.ec.networklogic.model.VeranaTrustResolution
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import eu.europa.ec.networklogic.model.VeranaUntrustedReason
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPublicKey
import java.time.Instant

class TestVeranaTrustController {

    private val documents = FakeDocumentRepository(
        signedMetadata = mapOf(CREDENTIAL_ISSUER to VeranaDevnetFixtures.issuerMetadataJwt),
    )
    private val evaluated = mutableListOf<Triple<VeranaTrustRole, String, ECPublicKey?>>()

    private val controller = VeranaTrustControllerImpl(
        documentRepository = documents,
        verifyVeranaIssuerMetadata = VerifyVeranaIssuerMetadataImpl(
            documentRepository = documents,
            clock = { Instant.parse("2026-10-02T00:00:00Z") },
        ),
        evaluateVeranaTrust = { role, did, vct, key ->
            evaluated += Triple(role, did, key)
            VeranaTrustEvidence(
                role = role,
                did = did,
                vct = vct,
                resolution = VeranaTrustResolution(did = did, status = VeranaTrustStatus.TRUSTED),
                accreditation = VeranaAccreditation(
                    status = VeranaAccreditationStatus.GRANTED,
                    reason = VeranaAccreditationReason.ACTIVE_PARTICIPANT,
                ),
            )
        },
    )

    @Test
    fun `an issuer is evaluated under the DID its signed metadata certificate names`() = runTest {
        val states = controller.issuerTrust(CREDENTIAL_ISSUER, VCT).toList()

        assertEquals(
            listOf(
                VeranaTrustState.Resolving(VeranaTrustRole.ISSUER, did = null),
                VeranaTrustState.Resolving(VeranaTrustRole.ISSUER, did = ISSUER_DID),
            ),
            states.take(2),
        )
        val evidence = (states.last() as VeranaTrustState.Evaluated).evidence
        assertEquals(ISSUER_DID, evidence.did)
        assertEquals(VCT, evidence.vct)
        assertEquals(VeranaDevnetFixtures.issuerCertificate.publicKey, evaluated.single().third)
        assertEquals(states.last(), controller.latestIssuerState.value)
        assertFalse(controller.latestIssuerState.value.blocksAction)
    }

    @Test
    fun `an issuer without signed metadata keeps the normal flow`() = runTest {
        documents.signedMetadata = emptyMap()

        val states = controller.issuerTrust(CREDENTIAL_ISSUER, VCT).toList()

        assertEquals(VeranaTrustState.NoDid, states.last())
        assertFalse(controller.latestIssuerState.value.blocksAction)
        assertTrue(evaluated.isEmpty())
    }

    @Test
    fun `signed metadata replayed under another issuer makes it untrusted without asking the registry`() = runTest {
        documents.signedMetadata = mapOf("https://evil.example" to VeranaDevnetFixtures.issuerMetadataJwt)

        val last = controller.issuerTrust("https://evil.example", VCT).toList().last()

        val evidence = (last as VeranaTrustState.Evaluated).evidence
        assertEquals(VeranaTrustStatus.UNTRUSTED, evidence.resolution.status)
        assertEquals(VeranaUntrustedReason.DID_NOT_PROVEN, evidence.resolution.reason)
        assertTrue(controller.latestIssuerState.value.blocksAction)
        assertTrue(evaluated.isEmpty())
    }

    @Test
    fun `a verifier is evaluated under the DID of its x5c leaf with the leaf key`() = runTest {
        val states = controller.verifierTrust(listOf(VeranaDevnetFixtures.verifierCertificate), VCT).toList()

        assertEquals(VeranaTrustState.Resolving(VeranaTrustRole.VERIFIER, VERIFIER_DID), states.first())
        assertEquals(VERIFIER_DID, (states.last() as VeranaTrustState.Evaluated).evidence.did)
        assertEquals(VeranaDevnetFixtures.verifierCertificate.publicKey, evaluated.single().third)
        assertEquals(VeranaTrustRole.VERIFIER, evaluated.single().first)
    }

    @Test
    fun `a verifier without a DID certificate keeps the normal flow`() = runTest {
        val states = controller.verifierTrust(emptyList(), VCT).toList()

        assertEquals(listOf(VeranaTrustState.NoDid), states)
        assertFalse(controller.latestVerifierState.value.blocksAction)

        controller.clearVerifierState()
        assertEquals(null, controller.latestVerifierState.value)
    }

    @Test
    fun `the gate stays closed while the check is resolving`() {
        val resolving: VeranaTrustState = VeranaTrustState.Resolving(VeranaTrustRole.VERIFIER, VERIFIER_DID)

        assertTrue(resolving.blocksAction)
    }
}
