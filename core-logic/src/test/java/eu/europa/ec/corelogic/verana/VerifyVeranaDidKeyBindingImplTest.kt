package eu.europa.ec.corelogic.verana

import eu.europa.ec.corelogic.model.VeranaDidKeyBinding
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.ISSUER_DID
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.VERIFIER_DID
import eu.europa.ec.networklogic.model.VeranaTrustRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VerifyVeranaDidKeyBindingImplTest {

    private val documents = FakeDocumentRepository(
        didDocuments = mapOf(
            ISSUER_DID to VeranaDevnetFixtures.issuerDidDocument,
            VERIFIER_DID to VeranaDevnetFixtures.verifierDidDocument,
        ),
    )
    private val useCase = VerifyVeranaDidKeyBindingImpl(documents)

    private val issuerKey = requireNotNull(VeranaX509.p256Key(VeranaDevnetFixtures.issuerCertificate))
    private val verifierKey = requireNotNull(VeranaX509.p256Key(VeranaDevnetFixtures.verifierCertificate))

    @Test
    fun `the devnet issuer DID document lists the metadata signing key as an assertion method`() = runTest {
        assertEquals(VeranaDidKeyBinding.PROVEN, useCase(ISSUER_DID, VeranaTrustRole.ISSUER, issuerKey))
    }

    @Test
    fun `the devnet verifier DID document lists the request signing key for authentication`() = runTest {
        assertEquals(VeranaDidKeyBinding.PROVEN, useCase(VERIFIER_DID, VeranaTrustRole.VERIFIER, verifierKey))
    }

    @Test
    fun `an authentication key does not prove an issuer`() = runTest {
        assertEquals(VeranaDidKeyBinding.NOT_PROVEN, useCase(VERIFIER_DID, VeranaTrustRole.ISSUER, verifierKey))
    }

    @Test
    fun `a certificate key the DID document does not list is not proven`() = runTest {
        assertEquals(VeranaDidKeyBinding.NOT_PROVEN, useCase(VERIFIER_DID, VeranaTrustRole.VERIFIER, issuerKey))

        val issuerDocument = VeranaDevnetFixtures.issuerDidDocument
        documents.didDocuments = mapOf(
            ISSUER_DID to issuerDocument.copy(
                assertionMethods = issuerDocument.assertionMethods.filterNot {
                    it.id.endsWith("#openid4vc-development-issuer")
                },
            ),
        )
        assertEquals(VeranaDidKeyBinding.NOT_PROVEN, useCase(ISSUER_DID, VeranaTrustRole.ISSUER, issuerKey))
    }

    @Test
    fun `an unreachable DID document leaves the binding unavailable`() = runTest {
        documents.didDocuments = emptyMap()

        assertEquals(VeranaDidKeyBinding.UNAVAILABLE, useCase(ISSUER_DID, VeranaTrustRole.ISSUER, issuerKey))
    }
}
