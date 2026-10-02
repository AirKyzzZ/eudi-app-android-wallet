package eu.europa.ec.networklogic.repository

import com.sun.net.httpserver.HttpServer
import eu.europa.ec.networklogic.model.VERANA_NETWORKS
import eu.europa.ec.networklogic.model.VeranaIndexerAnswer
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import eu.europa.ec.networklogic.util.VeranaHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.net.InetSocketAddress
import java.util.Collections

class VeranaIndexerRepositoryTest {

    private data class Received(
        val method: String,
        val uri: String,
        val contentType: String?,
        val blockHeight: String?,
        val body: String,
    )

    private val received = Collections.synchronizedList(mutableListOf<Received>())
    private val answers = mutableMapOf<String, Pair<Int, String>>()
    private lateinit var server: HttpServer
    private lateinit var repository: VeranaIndexerRepositoryImpl
    private val network get() = VERANA_NETWORKS.first().copy(indexerUrl = "http://127.0.0.1:${server.address.port}")

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                received += Received(
                    method = exchange.requestMethod,
                    uri = exchange.requestURI.toString(),
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
                    blockHeight = exchange.requestHeaders.getFirst("At-Block-Height"),
                    body = exchange.requestBody.readBytes().decodeToString(),
                )
                val (status, body) = answers[exchange.requestURI.path] ?: (500 to "")
                val bytes = body.encodeToByteArray()
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        repository = VeranaIndexerRepositoryImpl(VeranaHttpClient(HttpClient(Android), mock()))
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `resolve posts the v4 query for the DID and reads the trusted answer`() = runBlocking<Unit> {
        answers[RESOLVE] = 200 to fixture("resolve-verifier-accredited.json")

        val answer = repository.resolve(network, VERIFIER_DID)

        val request = received.single()
        assertEquals("POST", request.method)
        assertEquals(RESOLVE, request.uri)
        assertTrue(request.contentType.orEmpty().startsWith("application/json"))
        assertNull(request.blockHeight)
        assertEquals(
            Json.parseToJsonElement(
                """{"did":"$VERIFIER_DID","participations":{"states":["ACTIVE","EXPIRED","REVOKED"]},""" +
                        """"presentations":{"unresolvableCredentialIds":true},"ecsCredentials":true}"""
            ),
            Json.parseToJsonElement(request.body),
        )
        val resolution = (answer as VeranaIndexerAnswer.Resolved).resolution
        assertEquals(VeranaTrustStatus.TRUSTED, resolution.status)
        assertEquals(network, resolution.network)
    }

    @Test
    fun `an unknown DID is an answer, a missing route or a dead indexer is not`() = runBlocking<Unit> {
        answers[RESOLVE] = 404 to """{"error":"DID not found"}"""
        assertEquals(VeranaIndexerAnswer.NotRegistered, repository.resolve(network, VERIFIER_DID))

        answers[RESOLVE] = 404 to """{"name":"NotFoundError","message":"Not Found"}"""
        assertEquals(VeranaIndexerAnswer.Unanswered, repository.resolve(network, VERIFIER_DID))

        val dead = network.copy(indexerUrl = "http://127.0.0.1:1")
        assertEquals(VeranaIndexerAnswer.Unanswered, repository.resolve(dead, VERIFIER_DID))
    }

    @Test
    fun `the schema and ecosystem lookups read the indexer records`() = runBlocking<Unit> {
        answers["/v4/credential-schema/get/8"] = 200 to """{"schema":{"id":8,"ecosystem_id":6}}"""
        answers["/v4/ecosystem/get/6"] = 200 to """{"ecosystem":{"id":6,"did":"$ECOSYSTEM_DID"}}"""

        assertEquals(6L, repository.fetchSchemaEcosystemId(network, "8"))
        assertEquals(ECOSYSTEM_DID, repository.fetchEcosystemDid(network, 6L))
        assertNull(repository.fetchEcosystemDid(network, 7L))
        assertTrue(received.all { it.method == "GET" })
    }

    @Test
    fun `the participant lookup asks for an active participant with that role and schema`() = runBlocking<Unit> {
        answers[PARTICIPANTS] = 200 to """{"participants":[{"did":"$VERIFIER_DID","role":"VERIFIER",""" +
                """"schema_id":8,"participant_state":"ACTIVE"}]}"""

        assertEquals(true, repository.hasActiveParticipant(network, VERIFIER_DID, VeranaTrustRole.VERIFIER, "8"))
        assertEquals(false, repository.hasActiveParticipant(network, VERIFIER_DID, VeranaTrustRole.ISSUER, "8"))

        assertEquals(
            "$PARTICIPANTS?did=did%3Awebvh%3AQmUXVK59dTbKcUiSsbLGABrjaqhBinm2tu9eVYjAX5wbEA%3A" +
                    "demo-verifier-accredited.playground.devnet.verana.network&role=VERIFIER&schema_id=8" +
                    "&participant_state=ACTIVE",
            received.first().uri,
        )
        assertTrue(received.last().uri.contains("role=ISSUER"))

        answers.remove(PARTICIPANTS)
        assertNull(repository.hasActiveParticipant(network, VERIFIER_DID, VeranaTrustRole.VERIFIER, "8"))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/veranaTrust/$name")) { "missing fixture $name" }.readText()

    private companion object {
        const val RESOLVE = "/v4/verifiable-trust/resolve"
        const val PARTICIPANTS = "/v4/participant/list"
        const val VERIFIER_DID =
            "did:webvh:QmUXVK59dTbKcUiSsbLGABrjaqhBinm2tu9eVYjAX5wbEA:demo-verifier-accredited.playground.devnet.verana.network"
        const val ECOSYSTEM_DID =
            "did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network"
    }
}
