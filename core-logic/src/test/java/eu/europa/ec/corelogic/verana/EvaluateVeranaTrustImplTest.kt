package eu.europa.ec.corelogic.verana

import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.corelogic.model.VeranaAccreditation
import eu.europa.ec.corelogic.model.VeranaAccreditationReason
import eu.europa.ec.corelogic.model.VeranaAccreditationStatus
import eu.europa.ec.corelogic.model.VeranaDidKeyBinding
import eu.europa.ec.corelogic.model.blocksAccept
import eu.europa.ec.corelogic.model.canRetry
import eu.europa.ec.networklogic.model.VeranaTrustResolution
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import eu.europa.ec.networklogic.model.VeranaUntrustedReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class EvaluateVeranaTrustImplTest {

    private var binding = VeranaDidKeyBinding.PROVEN
    private var status = VeranaTrustStatus.TRUSTED
    private var accreditationStatus = VeranaAccreditationStatus.GRANTED
    private var resolveFailure: Throwable? = null
    private var resolveCalls = 0
    private var accreditationCalls = 0
    private var bindingCalls = 0

    private val key = VeranaX509.p256Key(VeranaDevnetFixtures.verifierCertificate)!!

    private val useCase = EvaluateVeranaTrustImpl(
        verifyVeranaDidKeyBinding = { _, _, _ -> bindingCalls++; binding },
        resolveVeranaTrust = { did ->
            resolveCalls++
            resolveFailure?.let { throw it }
            VeranaTrustResolution(did = did, status = status)
        },
        checkVeranaAccreditation = { _, _, _ ->
            accreditationCalls++
            VeranaAccreditation(status = accreditationStatus, reason = VeranaAccreditationReason.ACTIVE_PARTICIPANT)
        },
        logController = mock<LogController>(),
    )

    @Test
    fun `trusted and granted opens the gate`() = runTest {
        val evidence = useCase(VeranaTrustRole.ISSUER, DID, VCT, key)

        assertEquals(VeranaTrustStatus.TRUSTED, evidence.resolution.status)
        assertEquals(VeranaAccreditationStatus.GRANTED, evidence.accreditation?.status)
        assertEquals(VCT, evidence.vct)
        assertFalse(evidence.blocksAccept)
        assertFalse(evidence.canRetry)
    }

    @Test
    fun `trusted but refused keeps the gate closed without a retry`() = runTest {
        accreditationStatus = VeranaAccreditationStatus.REFUSED

        val evidence = useCase(VeranaTrustRole.VERIFIER, DID, VCT, key)

        assertTrue(evidence.blocksAccept)
        assertFalse(evidence.canRetry)
    }

    @Test
    fun `undetermined accreditation keeps the gate closed with a retry`() = runTest {
        accreditationStatus = VeranaAccreditationStatus.UNDETERMINED

        val evidence = useCase(VeranaTrustRole.ISSUER, DID, VCT, key)

        assertTrue(evidence.blocksAccept)
        assertTrue(evidence.canRetry)
    }

    @Test
    fun `unverified and untrusted counterparties keep the gate closed even when granted`() = runTest {
        status = VeranaTrustStatus.UNVERIFIED
        val unverified = useCase(VeranaTrustRole.ISSUER, DID, VCT, key)

        status = VeranaTrustStatus.UNTRUSTED
        val untrusted = useCase(VeranaTrustRole.ISSUER, DID, VCT, key)

        assertTrue(unverified.blocksAccept)
        assertTrue(unverified.canRetry)
        assertTrue(untrusted.blocksAccept)
        assertFalse(untrusted.canRetry)
    }

    @Test
    fun `a certificate key the DID does not list makes the counterparty untrusted`() = runTest {
        binding = VeranaDidKeyBinding.NOT_PROVEN

        val evidence = useCase(VeranaTrustRole.VERIFIER, DID, VCT, key)

        assertEquals(VeranaTrustStatus.UNTRUSTED, evidence.resolution.status)
        assertEquals(VeranaUntrustedReason.DID_NOT_PROVEN, evidence.resolution.reason)
        assertNull(evidence.accreditation)
        assertTrue(evidence.blocksAccept)
        assertEquals(0, resolveCalls)
        assertEquals(0, accreditationCalls)
    }

    @Test
    fun `an unreachable DID document leaves the counterparty unverified`() = runTest {
        binding = VeranaDidKeyBinding.UNAVAILABLE

        val evidence = useCase(VeranaTrustRole.ISSUER, DID, VCT, key)

        assertEquals(VeranaTrustStatus.UNVERIFIED, evidence.resolution.status)
        assertTrue(evidence.blocksAccept)
        assertTrue(evidence.canRetry)
    }

    @Test
    fun `a DID authenticated without a certificate skips the key binding`() = runTest {
        useCase(VeranaTrustRole.VERIFIER, DID, VCT, certificateKey = null)

        assertEquals(0, bindingCalls)
        assertEquals(1, resolveCalls)
    }

    @Test
    fun `an unexpected failure is unverified, never trusted`() = runTest {
        resolveFailure = IllegalStateException("boom")

        val evidence = useCase(VeranaTrustRole.ISSUER, DID, VCT, key)

        assertEquals(VeranaTrustStatus.UNVERIFIED, evidence.resolution.status)
        assertTrue(evidence.blocksAccept)
    }

    private companion object {
        const val DID = "did:webvh:QmService:service.example"
        const val VCT = "https://ecosystem.example/vt/vct/8"
    }
}
