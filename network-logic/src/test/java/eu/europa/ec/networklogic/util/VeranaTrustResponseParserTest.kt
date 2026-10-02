package eu.europa.ec.networklogic.util

import eu.europa.ec.networklogic.model.VERANA_NETWORKS
import eu.europa.ec.networklogic.model.VeranaIndexerAnswer
import eu.europa.ec.networklogic.model.VeranaTrustClaim
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import eu.europa.ec.networklogic.model.VeranaUntrustedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeranaTrustResponseParserTest {

    private val parser = VeranaTrustResponseParser
    private val devnet = VERANA_NETWORKS.first()

    @Test
    fun `reads the live devnet resolve answer of the accredited verifier`() {
        val answer = parser.parseResolveAnswer(
            200,
            fixture("resolve-verifier-accredited.json"),
            VERIFIER_DID,
            devnet,
        )

        val resolution = (answer as VeranaIndexerAnswer.Resolved).resolution
        assertEquals(VeranaTrustStatus.TRUSTED, resolution.status)
        assertNull(resolution.reason)
        assertEquals(devnet, resolution.network)
        assertEquals("2026-10-01T15:56:54.684Z", resolution.evaluatedAt)
        assertEquals("2027-10-01T00:00:00.000Z", resolution.expiresAt)
        assertEquals(
            listOf("ServiceCredential", "OrganizationCredential"),
            resolution.credentials.map { it.ecsSchema },
        )
        assertEquals(3L, resolution.credentials.first().ecosystemId)
        assertTrue(
            VeranaTrustClaim("name", listOf("Accredited Verifier (demo)")) in resolution.credentials.first().claims
        )
        assertTrue(VeranaTrustClaim("minimumAgeRequired", listOf("0")) in resolution.credentials.first().claims)
        assertTrue(
            "https://demo-verifier-accredited.playground.devnet.verana.network/vt/schemas-8-jsc.json" in
                    resolution.unresolvableCredentialIds
        )
    }

    @Test
    fun `an answer that says trusted false is untrusted`() {
        val answer = parser.parseResolveAnswer(
            200,
            """{"did":"$VERIFIER_DID","trusted":false}""",
            VERIFIER_DID,
            devnet,
        )

        val resolution = (answer as VeranaIndexerAnswer.Resolved).resolution
        assertEquals(VeranaTrustStatus.UNTRUSTED, resolution.status)
        assertEquals(VeranaUntrustedReason.NOT_TRUSTED, resolution.reason)
    }

    @Test
    fun `DID not found is an answer, a missing v4 route is not`() {
        assertEquals(
            VeranaIndexerAnswer.NotRegistered,
            parser.parseResolveAnswer(404, """{"error":"DID not found","code":404}""", VERIFIER_DID, devnet),
        )
        assertEquals(
            VeranaIndexerAnswer.Unanswered,
            parser.parseResolveAnswer(
                404,
                """{"name":"NotFoundError","message":"Not found","code":404,"type":"NOT_FOUND"}""",
                VERIFIER_DID,
                devnet,
            ),
        )
        assertEquals(VeranaIndexerAnswer.Unanswered, parser.parseResolveAnswer(503, "", VERIFIER_DID, devnet))
    }

    @Test
    fun `a malformed answer or one about another DID is unanswered`() {
        listOf(
            """{"did":"did:webvh:QmOther:other.example","trusted":true}""",
            """{"did":"$VERIFIER_DID","trusted":"true"}""",
            """{"did":"$VERIFIER_DID"}""",
            "not json",
        ).forEach { body ->
            assertEquals(
                VeranaIndexerAnswer.Unanswered,
                parser.parseResolveAnswer(200, body, VERIFIER_DID, devnet),
            )
        }
    }

    @Test
    fun `claims outside the allowlist are dropped and links must be http`() {
        val body = """
            {"did":"$VERIFIER_DID","trusted":true,"ecsCredentials":[{"ecsSchema":"ServiceCredential","credentialSubject":{
              "name":"Svc","script":"<x>","logoUri":"javascript:alert(1)","privacyPolicyUri":"https://svc.example/p"
            }}]}
        """.trimIndent()

        val answer = parser.parseResolveAnswer(200, body, VERIFIER_DID, devnet) as VeranaIndexerAnswer.Resolved

        val claims = answer.resolution.credentials.single().claims
        assertEquals(listOf("name", "privacyPolicyUri"), claims.map { it.name })
        assertEquals("https://svc.example/p", claims.last().safeHttpUrl)
    }

    @Test
    fun `reads the ecosystem DID and the schema ecosystem`() {
        assertEquals(
            ECOSYSTEM_DID,
            parser.parseEcosystemDid("""{"ecosystem":{"id":6,"did":"$ECOSYSTEM_DID"}}"""),
        )
        assertEquals(6L, parser.parseSchemaEcosystemId("""{"schema":{"id":8,"ecosystem_id":6}}"""))
        assertNull(parser.parseSchemaEcosystemId("""{"schema":{"id":8,"ecosystem_id":"6"}}"""))
        assertNull(parser.parseEcosystemDid("""{"ecosystem":{"id":6}}"""))
    }

    @Test
    fun `an active participant must match DID, role and schema`() {
        val participant = """{"did":"$VERIFIER_DID","role":"VERIFIER","schema_id":8,"participant_state":"ACTIVE"}"""
        val revoked = participant.replace("ACTIVE", "REVOKED")

        assertEquals(true, granted("""{"participants":[$participant]}""", VeranaTrustRole.VERIFIER))
        assertEquals(false, granted("""{"participants":[$participant]}""", VeranaTrustRole.ISSUER))
        assertEquals(false, granted("""{"participants":[$revoked]}""", VeranaTrustRole.VERIFIER))
        assertEquals(false, granted("""{"participants":[]}""", VeranaTrustRole.VERIFIER))
        assertNull(granted("""{"items":[]}""", VeranaTrustRole.VERIFIER))
    }

    private fun granted(body: String, role: VeranaTrustRole) =
        parser.parseParticipantGranted(body = body, did = VERIFIER_DID, role = role, schemaId = "8")

    private companion object {
        const val VERIFIER_DID =
            "did:webvh:QmUXVK59dTbKcUiSsbLGABrjaqhBinm2tu9eVYjAX5wbEA:demo-verifier-accredited.playground.devnet.verana.network"
        const val ECOSYSTEM_DID =
            "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network"
    }
}
