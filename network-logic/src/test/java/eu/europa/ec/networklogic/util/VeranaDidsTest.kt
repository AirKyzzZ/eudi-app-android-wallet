package eu.europa.ec.networklogic.util

import eu.europa.ec.networklogic.model.VERANA_NETWORKS
import eu.europa.ec.networklogic.model.VeranaSchemaRef
import eu.europa.ec.networklogic.model.networkLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeranaDidsTest {

    private val verifierDidDocument: JsonObject =
        Json.parseToJsonElement(fixture("verifier-did-document.json")).jsonObject

    @Test
    fun `did webvh resolves to the did jsonl under the host or its path`() {
        assertEquals(
            "https://playground-demo.playground.devnet.verana.network/.well-known/did.jsonl",
            VeranaDids.documentUrl(ECOSYSTEM_DID),
        )
        assertEquals(
            "https://example.com:8443/dids/issuer/did.jsonl",
            VeranaDids.documentUrl("did:webvh:QmScid:example.com%3A8443:dids:issuer"),
        )
    }

    @Test
    fun `did web resolves to the did json under the host or its path`() {
        assertEquals("https://example.com/.well-known/did.json", VeranaDids.documentUrl("did:web:example.com"))
        assertEquals(
            "https://example.com/user/alice/did.json",
            VeranaDids.documentUrl("did:web:example.com:user:alice"),
        )
        assertNull(VeranaDids.documentUrl("did:key:z6MkExample"))
    }

    @Test
    fun `did key and did jwk have no DID document`() {
        assertFalse(VeranaDids.hasDidDocument("did:key:z6MkExample"))
        assertFalse(VeranaDids.hasDidDocument("did:jwk:eyJrdHkiOiJFQyJ9"))
        assertTrue(VeranaDids.hasDidDocument(VERIFIER_DID))
    }

    @Test
    fun `the last log entry carries the current DID document`() {
        val first = JsonObject(mapOf("id" to JsonPrimitive("old")))
        val log = didLog(first) + didLog(verifierDidDocument)

        val lastEntry = Json.parseToJsonElement(requireNotNull(VeranaDids.lastLogEntry(log)))

        assertEquals(verifierDidDocument, VeranaDids.logEntryState(lastEntry))
    }

    @Test
    fun `schema references map onto a configured network only`() {
        val schemaRef = requireNotNull(VeranaSchemaRef.parse("vpr:verana:vna-devnet-1:cs:8", VERANA_NETWORKS))

        assertEquals(VERANA_NETWORKS.first(), schemaRef.network)
        assertEquals("8", schemaRef.schemaId)
        assertNull(VeranaSchemaRef.parse("vpr:verana:vna-mainnet-9:cs:8", VERANA_NETWORKS))
        assertNull(VeranaSchemaRef.parse("vpr:verana:vna-testnet-1/cs/v1/js/253", VERANA_NETWORKS))
    }

    @Test
    fun `devnet and testnet carry no ecosystem allowlist and label themselves as non-production`() {
        assertTrue(VERANA_NETWORKS.all { it.trustedEcsEcosystemDids == null })
        assertEquals("DEVNET · TESTNET", VERANA_NETWORKS.networkLabel())
        assertEquals("DEVNET", VERANA_NETWORKS.take(1).networkLabel())
        assertNull(VERANA_NETWORKS.map { it.copy(production = true) }.networkLabel())
    }

    @Test
    fun `a document for another DID is refused`() {
        assertNull(VeranaDids.parseDocument("did:webvh:QmOther:other.example", verifierDidDocument))
    }

    @Test
    fun `relationships resolve references, including relative ones`() {
        val document = Json.parseToJsonElement(
            """
            {
              "id": "did:web:example.com",
              "verificationMethod": [{"id": "#key-1", "type": "Multikey", "publicKeyMultibase": "z6Mk"}],
              "assertionMethod": ["#key-1", {"id": "#embedded", "type": "JsonWebKey2020"}],
              "authentication": ["did:web:example.com#key-1", "#missing"]
            }
            """.trimIndent()
        )

        val parsed = requireNotNull(VeranaDids.parseDocument("did:web:example.com", document))

        assertEquals(
            listOf("did:web:example.com#key-1", "did:web:example.com#embedded"),
            parsed.assertionMethods.map { it.id },
        )
        assertEquals(listOf("did:web:example.com#key-1"), parsed.authenticationMethods.map { it.id })
    }

    @Test
    fun `the devnet verifier lists its request signing key under authentication`() {
        val document = requireNotNull(VeranaDids.parseDocument(VERIFIER_DID, verifierDidDocument))

        assertTrue(document.authenticationMethods.any { it.id == "$VERIFIER_DID#openid4vc-development-verifier" })
        assertFalse(document.assertionMethods.any { it.id == "$VERIFIER_DID#openid4vc-development-verifier" })
    }

    private fun didLog(document: JsonObject): String = """{"versionId":"1-Qm","state":$document}""" + "\n"

    private companion object {
        const val VERIFIER_DID =
            "did:webvh:QmUXVK59dTbKcUiSsbLGABrjaqhBinm2tu9eVYjAX5wbEA:demo-verifier-accredited.playground.devnet.verana.network"
        const val ECOSYSTEM_DID =
            "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network"
    }
}

internal fun Any.fixture(name: String): String =
    requireNotNull(javaClass.getResource("/veranaTrust/$name")) { "missing fixture $name" }.readText()
