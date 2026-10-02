package eu.europa.ec.corelogic.verana

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import eu.europa.ec.corelogic.model.VeranaIssuerIdentity
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.CREDENTIAL_ISSUER
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.ISSUER_DID
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.VERIFIER_DID
import eu.europa.ec.networklogic.model.VeranaDidDocument
import eu.europa.ec.networklogic.model.VeranaVerificationMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Date

class VerifyVeranaIssuerMetadataImplTest {

    private val documents = FakeDocumentRepository()
    private val verify = VerifyVeranaIssuerMetadataImpl(documents, clock = { NOW })
    private val metadataJwt = VeranaDevnetFixtures.issuerMetadataJwt

    @Test
    fun `the devnet issuer metadata names the issuer DID in its x5c leaf and is signed by that leaf`() = runTest {
        val signed = verify(metadataJwt, CREDENTIAL_ISSUER) as VeranaIssuerIdentity.Signed

        assertEquals(ISSUER_DID, signed.did)
        assertEquals(VeranaDevnetFixtures.issuerCertificate.publicKey, signed.certificateKey)
    }

    @Test
    fun `a trailing slash on the offered issuer still matches`() = runTest {
        assertTrue(verify(metadataJwt, "$CREDENTIAL_ISSUER/") is VeranaIssuerIdentity.Signed)
    }

    @Test
    fun `metadata replayed for another credential issuer is not proven`() = runTest {
        assertEquals(
            VeranaIssuerIdentity.NotProven(ISSUER_DID),
            verify(metadataJwt, "https://evil.example/oid4vci/issuer"),
        )
    }

    @Test
    fun `metadata whose signature does not hold is not proven`() = runTest {
        val (header, payload, signature) = metadataJwt.split('.')
        val flipped = signature.substring(0, 20) + (if (signature[20] == 'A') 'B' else 'A') + signature.substring(21)

        assertEquals(
            VeranaIssuerIdentity.NotProven(ISSUER_DID),
            verify("$header.$payload.$flipped", CREDENTIAL_ISSUER),
        )
    }

    @Test
    fun `metadata without an x5c DID or a DID kid, or not a JWS, names no DID`() = runTest {
        assertEquals(VeranaIssuerIdentity.NoDid, verify("eyJhbGciOiJFUzI1NiJ9.e30.AAAA", CREDENTIAL_ISSUER))
        assertEquals(VeranaIssuerIdentity.NoDid, verify("""{"credential_issuer":"$CREDENTIAL_ISSUER"}""", CREDENTIAL_ISSUER))
    }

    @Test
    fun `the devnet request object is signed by its x5c leaf, which names the verifier DID`() {
        val leaf = VeranaDevnetFixtures.verifierCertificate
        val key = requireNotNull(VeranaX509.p256Key(leaf))

        assertEquals(VERIFIER_DID, VeranaX509.didOf(leaf))
        assertTrue(SignedJWT.parse(VeranaDevnetFixtures.verifierRequestObjectJwt).verify(ECDSAVerifier(key)))
    }

    @Test
    fun `metadata signed under a DID kid is verified with that assertion method`() = runTest {
        val key = ECKeyGenerator(Curve.P_256).generate()
        documents.didDocuments = mapOf(KID_DID to kidDocument(key, assertion = true))

        val signed = verify(kidSigned(key), CREDENTIAL_ISSUER) as VeranaIssuerIdentity.Signed

        assertEquals(KID_DID, signed.did)
        assertEquals(key.toECPublicKey(), signed.certificateKey)
    }

    @Test
    fun `a DID kid outside the assertion methods, or expired metadata, is not proven`() = runTest {
        val key = ECKeyGenerator(Curve.P_256).generate()

        documents.didDocuments = mapOf(KID_DID to kidDocument(key, assertion = false))
        assertEquals(VeranaIssuerIdentity.NotProven(KID_DID), verify(kidSigned(key), CREDENTIAL_ISSUER))

        documents.didDocuments = mapOf(KID_DID to kidDocument(key, assertion = true))
        assertEquals(
            VeranaIssuerIdentity.NotProven(KID_DID),
            verify(kidSigned(key, expiresAt = Instant.parse("2026-10-01T00:00:00Z")), CREDENTIAL_ISSUER),
        )
    }

    @Test
    fun `a DID kid whose document does not load is unavailable`() = runTest {
        val key = ECKeyGenerator(Curve.P_256).generate()

        assertEquals(VeranaIssuerIdentity.Unavailable(KID_DID), verify(kidSigned(key), CREDENTIAL_ISSUER))
    }

    private fun kidSigned(key: ECKey, expiresAt: Instant? = null): String {
        val claims = JWTClaimsSet.Builder()
            .subject(CREDENTIAL_ISSUER)
            .claim("credential_issuer", CREDENTIAL_ISSUER)
            .apply { expiresAt?.let { expirationTime(Date.from(it)) } }
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KID).build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(key)) }.serialize()
    }

    private fun kidDocument(key: ECKey, assertion: Boolean): VeranaDidDocument {
        val method = VeranaVerificationMethod(
            id = KID,
            type = "JsonWebKey2020",
            publicKeyJwk = Json.parseToJsonElement(key.toPublicJWK().toJSONString()).jsonObject,
            publicKeyMultibase = null,
        )
        return VeranaDidDocument(
            id = KID_DID,
            assertionMethods = if (assertion) listOf(method) else emptyList(),
            authenticationMethods = if (assertion) emptyList() else listOf(method),
        )
    }

    private companion object {
        const val KID_DID = "did:web:issuer.example"
        const val KID = "$KID_DID#key-1"
        val NOW: Instant = Instant.parse("2026-10-02T00:00:00Z")
    }
}
