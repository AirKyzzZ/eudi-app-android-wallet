package eu.europa.ec.corelogic.verana

import com.nimbusds.jose.util.X509CertUtils
import com.nimbusds.jwt.SignedJWT
import eu.europa.ec.networklogic.model.VeranaDidDocument
import eu.europa.ec.networklogic.model.VeranaIndexerAnswer
import eu.europa.ec.networklogic.model.VeranaNetwork
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.repository.VeranaDocumentRepository
import eu.europa.ec.networklogic.repository.VeranaIndexerRepository
import eu.europa.ec.networklogic.util.VeranaDids
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.cert.X509Certificate

object VeranaDevnetFixtures {
    const val ISSUER_DID =
        "did:webvh:Qmaup6XHn4UMTEUu9PWMEwcRAYmZ74SpPpUGYpnXj9dTPf:demo-issuer-accredited.playground.devnet.verana.network"
    const val UNACCREDITED_ISSUER_DID =
        "did:webvh:Qmbep95zc1AokqrHBvFVPZWWz13SewXd2dcKS2RjHyKNH7:demo-issuer-unaccredited.playground.devnet.verana.network"
    const val VERIFIER_DID =
        "did:webvh:QmUXVK59dTbKcUiSsbLGABrjaqhBinm2tu9eVYjAX5wbEA:demo-verifier-accredited.playground.devnet.verana.network"
    const val ECOSYSTEM_DID =
        "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network"
    const val CREDENTIAL_ISSUER = "https://demo-issuer-accredited.playground.devnet.verana.network/oid4vci/issuer"
    const val VCT = "https://playground-demo.playground.devnet.verana.network/vt/vct/8"
    const val VTJSC_ID = "https://playground-demo.playground.devnet.verana.network/vt/schemas-8-jsc.json"

    val typeMetadata: JsonObject get() = json("type-metadata.json")
    val vtjsc: JsonObject get() = json("vtjsc.json")
    val issuerMetadataJwt: String get() = text("issuer-metadata.jwt").trim()
    val verifierRequestObjectJwt: String get() = text("verifier-request-object.jwt").trim()

    val issuerDidDocument: VeranaDidDocument get() = didDocument(ISSUER_DID, "issuer-did-document.json")
    val verifierDidDocument: VeranaDidDocument get() = didDocument(VERIFIER_DID, "verifier-did-document.json")
    val ecosystemDidDocument: VeranaDidDocument get() = didDocument(ECOSYSTEM_DID, "ecosystem-did-document.json")

    val issuerCertificate: X509Certificate get() = leafOf(issuerMetadataJwt)
    val verifierCertificate: X509Certificate get() = leafOf(verifierRequestObjectJwt)

    private fun leafOf(jwt: String): X509Certificate =
        requireNotNull(X509CertUtils.parse(SignedJWT.parse(jwt).header.x509CertChain.first().decode()))

    private fun didDocument(did: String, name: String) = requireNotNull(VeranaDids.parseDocument(did, json(name)))

    private fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject

    private fun text(name: String): String =
        requireNotNull(javaClass.getResource("/veranaTrust/$name")) { "missing fixture $name" }.readText()
}

class FakeDocumentRepository(
    var json: Map<String, JsonElement> = emptyMap(),
    var didDocuments: Map<String, VeranaDidDocument> = emptyMap(),
    var signedMetadata: Map<String, String> = emptyMap(),
) : VeranaDocumentRepository {
    override suspend fun fetchJson(url: String): JsonElement? = json[url]

    override suspend fun fetchDidDocument(did: String): VeranaDidDocument? = didDocuments[did]

    override suspend fun fetchSignedIssuerMetadata(credentialIssuer: String): String? = signedMetadata[credentialIssuer]
}

class FakeIndexerRepository(
    var resolveAnswer: suspend (VeranaNetwork, String) -> VeranaIndexerAnswer = { _, _ -> VeranaIndexerAnswer.Unanswered },
    var ecosystemDid: (VeranaNetwork, Long) -> String? = { _, _ -> null },
    var schemaEcosystemId: (VeranaNetwork, String) -> Long? = { _, _ -> null },
    var activeParticipant: (VeranaNetwork, String, VeranaTrustRole, String) -> Boolean? = { _, _, _, _ -> false },
) : VeranaIndexerRepository {
    val resolved = mutableListOf<Pair<VeranaNetwork, String>>()
    val participantQueries = mutableListOf<Triple<String, VeranaTrustRole, String>>()

    override suspend fun resolve(network: VeranaNetwork, did: String): VeranaIndexerAnswer {
        resolved += network to did
        return resolveAnswer(network, did)
    }

    override suspend fun fetchEcosystemDid(network: VeranaNetwork, ecosystemId: Long): String? =
        ecosystemDid(network, ecosystemId)

    override suspend fun fetchSchemaEcosystemId(network: VeranaNetwork, schemaId: String): Long? =
        schemaEcosystemId(network, schemaId)

    override suspend fun hasActiveParticipant(
        network: VeranaNetwork,
        did: String,
        role: VeranaTrustRole,
        schemaId: String,
    ): Boolean? {
        participantQueries += Triple(did, role, schemaId)
        return activeParticipant(network, did, role, schemaId)
    }
}
