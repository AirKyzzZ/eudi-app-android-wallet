package eu.europa.ec.networklogic.repository

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class VeranaDataIntegrityTest {

    private val VTJSC_FIXTURE = """
        {
          "@context": [
            "https://www.w3.org/ns/credentials/v2",
            "https://www.w3.org/ns/credentials/undefined-terms/v2"
          ],
          "id": "https://playground-demo.playground.devnet.verana.network/vt/schemas-8-jsc.json",
          "type": ["VerifiableCredential", "JsonSchemaCredential"],
          "issuer": "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network",
          "credentialSubject": {
            "type": "JsonSchema",
            "jsonSchema": {
              "${"$"}ref": "vpr:verana:vna-devnet-1:cs:8"
            },
            "digestSRI": "sha384-5ho+wL3totRSpaN+xya/uevZ3T3cnIlPAMcaFbPmd7I+rqckOtVrbDabpiL4rem3",
            "id": "vpr:verana:vna-devnet-1:cs:8"
          },
          "validFrom": "2026-10-01T14:40:32.399Z",
          "validUntil": "2036-09-28T14:40:32.398Z",
          "credentialSchema": {
            "digestSRI": "sha384-FdPKzKLFNWo+3ZqV9vjuY8aNQk+636lvGRKKNzAfy93Q9jf+lNHD8j91g/KHWCBX",
            "id": "https://www.w3.org/ns/credentials/json-schema/v2.json",
            "type": "JsonSchema"
          },
          "proof": {
            "type": "DataIntegrityProof",
            "cryptosuite": "eddsa-jcs-2022",
            "verificationMethod": "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network#z6MkpgURAWmKM9pZehgnbZyPAyCF59cGdircgTDauNb9dGXF",
            "proofPurpose": "assertionMethod",
            "@context": [
              "https://www.w3.org/ns/credentials/v2",
              "https://www.w3.org/ns/credentials/undefined-terms/v2"
            ],
            "proofValue": "z2Sik7fRZcLoqSmmrdfS2ZBFWqYHY6GAAva9E2DYMriKb6ToY8UB7bomycYJWS2Rd9EDQiDR7zpopZGWN3LmZgrfq"
          }
        }
    """.trimIndent()

    private val ECOSYSTEM_DID_DOCUMENT = """
        {
          "@context": [
            "https://www.w3.org/ns/did/v1",
            "https://w3id.org/security/multikey/v1",
            "https://w3id.org/security/suites/ed25519-2020/v1",
            "https://w3id.org/security/suites/x25519-2019/v1",
            "https://identity.foundation/linked-vp/contexts/v1"
          ],
          "id": "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network",
          "controller": "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network",
          "verificationMethod": [
            {
              "controller": "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network",
              "type": "Multikey",
              "publicKeyMultibase": "z6MkpcfRDx9eag4Gq7cWFiaZBG8Aexg1r3H67y7WFDmWRFqk",
              "id": "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network#FDmWRFqk"
            },
            {
              "controller": "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network",
              "id": "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network#z6MkpgURAWmKM9pZehgnbZyPAyCF59cGdircgTDauNb9dGXF",
              "publicKeyMultibase": "z6MkpgURAWmKM9pZehgnbZyPAyCF59cGdircgTDauNb9dGXF",
              "type": "Ed25519VerificationKey2020"
            },
            {
              "controller": "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network",
              "id": "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network#key-agreement-1",
              "publicKeyMultibase": "z6LSeD9T67uAhG82cAEAbwXBNEbxvHXw9D2pccNuWHFB9zde",
              "type": "Multikey"
            }
          ],
          "authentication": [
            "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network#z6MkpgURAWmKM9pZehgnbZyPAyCF59cGdircgTDauNb9dGXF"
          ],
          "assertionMethod": [
            "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network#z6MkpgURAWmKM9pZehgnbZyPAyCF59cGdircgTDauNb9dGXF"
          ]
        }
    """.trimIndent()

    @Test
    fun `test eddsa-jcs-2022 verification with real devnet fixtures`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val document = json.parseToJsonElement(VTJSC_FIXTURE).jsonObject
        val issuerDocument = json.parseToJsonElement(ECOSYSTEM_DID_DOCUMENT).jsonObject

        val httpClient = org.mockito.Mockito.mock(HttpClient::class.java)
        val repository = VeranaTrustRepositoryImpl(httpClient, "", "")

        val result = repository.verifyEddsaJcs2022(document, issuerDocument)
        assertTrue("Signature verification failed for VTJSC fixture", result)
    }
}
