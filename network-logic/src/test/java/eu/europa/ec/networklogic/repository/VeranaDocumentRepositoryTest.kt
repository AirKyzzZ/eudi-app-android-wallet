package eu.europa.ec.networklogic.repository

import eu.europa.ec.networklogic.util.VeranaHttpClient
import eu.europa.ec.networklogic.util.VeranaHttpResponse
import eu.europa.ec.networklogic.util.fixture
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.wheneverBlocking

class VeranaDocumentRepositoryTest {

    private val httpClient = mock<VeranaHttpClient>()
    private val repository = VeranaDocumentRepositoryImpl(httpClient)

    @Test
    fun `signed issuer metadata is looked up at the spec path first, then under the issuer`() {
        assertEquals(
            listOf(
                "https://demo-issuer-accredited.playground.devnet.verana.network/.well-known/openid-credential-issuer/oid4vci/issuer",
                "https://demo-issuer-accredited.playground.devnet.verana.network/oid4vci/issuer/.well-known/openid-credential-issuer",
            ),
            signedIssuerMetadataUrls("https://demo-issuer-accredited.playground.devnet.verana.network/oid4vci/issuer/"),
        )
    }

    @Test
    fun `an issuer at the host root has a single metadata location`() {
        assertEquals(
            listOf("https://issuer.example:8443/.well-known/openid-credential-issuer"),
            signedIssuerMetadataUrls("https://issuer.example:8443"),
        )
    }

    @Test
    fun `only https issuers are looked up`() {
        assertEquals(emptyList<String>(), signedIssuerMetadataUrls("http://issuer.example/oid4vci"))
        assertEquals(emptyList<String>(), signedIssuerMetadataUrls("not a url"))
    }

    @Test
    fun `signed issuer metadata is asked for as a JWT and plain JSON is skipped`() = runTest {
        val (specPath, issuerPath) = signedIssuerMetadataUrls(CREDENTIAL_ISSUER)
        wheneverBlocking { httpClient.get(eq(specPath), any()) }
            .thenReturn(VeranaHttpResponse(200, """{"credential_issuer":"a.b","x":"c.d"}"""))
        wheneverBlocking { httpClient.get(eq(issuerPath), any()) }
            .thenReturn(VeranaHttpResponse(200, " $JWS\n"))

        assertEquals(JWS, repository.fetchSignedIssuerMetadata(CREDENTIAL_ISSUER))
        verify(httpClient).get(specPath, ContentType("application", "jwt"))
        verify(httpClient).get(issuerPath, ContentType("application", "jwt"))
    }

    @Test
    fun `signed issuer metadata that does not load is absent`() = runTest {
        wheneverBlocking { httpClient.get(any(), any()) }.thenReturn(VeranaHttpResponse(404, JWS))

        assertNull(repository.fetchSignedIssuerMetadata(CREDENTIAL_ISSUER))
    }

    @Test
    fun `a did webvh document is the state of the last log entry`() = runTest {
        val document = Json.parseToJsonElement(fixture("verifier-did-document.json"))
        val log = """{"versionId":"1-Qm","state":{"id":"$VERIFIER_DID"}}""" + "\n" +
                """{"versionId":"2-Qm","state":$document}""" + "\n"
        wheneverBlocking { httpClient.get(any(), any()) }.thenReturn(VeranaHttpResponse(200, log))

        val parsed = repository.fetchDidDocument(VERIFIER_DID)

        verify(httpClient).get(
            "https://demo-verifier-accredited.playground.devnet.verana.network/.well-known/did.jsonl",
            ContentType.Application.Json,
        )
        assertEquals(
            "$VERIFIER_DID#openid4vc-development-verifier",
            parsed?.authenticationMethods?.firstOrNull { it.publicKeyJwk != null }?.id,
        )
    }

    @Test
    fun `a DID document that does not load is absent`() = runTest {
        wheneverBlocking { httpClient.get(any(), any()) }.thenReturn(null)

        assertNull(repository.fetchDidDocument(VERIFIER_DID))
        assertNull(repository.fetchDidDocument("did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK"))
    }

    private companion object {
        const val CREDENTIAL_ISSUER = "https://demo-issuer-accredited.playground.devnet.verana.network/oid4vci/issuer"
        const val JWS = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln"
        const val VERIFIER_DID =
            "did:webvh:QmUXVK59dTbKcUiSsbLGABrjaqhBinm2tu9eVYjAX5wbEA:demo-verifier-accredited.playground.devnet.verana.network"
    }
}
