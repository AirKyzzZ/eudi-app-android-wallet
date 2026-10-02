package eu.europa.ec.networklogic.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class VeranaDocumentRepositoryTest {

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
}
