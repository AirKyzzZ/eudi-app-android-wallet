package eu.europa.ec.networklogic.repository

import eu.europa.ec.networklogic.model.VeranaDidDocument
import eu.europa.ec.networklogic.util.VeranaDids
import eu.europa.ec.networklogic.util.VeranaHttpClient
import eu.europa.ec.networklogic.util.VeranaTrustResponseParser
import io.ktor.http.ContentType
import kotlinx.serialization.json.JsonElement
import java.net.URI

interface VeranaDocumentRepository {
    suspend fun fetchJson(url: String): JsonElement?

    suspend fun fetchDidDocument(did: String): VeranaDidDocument?

    suspend fun fetchSignedIssuerMetadata(credentialIssuer: String): String?
}

class VeranaDocumentRepositoryImpl(
    private val httpClient: VeranaHttpClient,
) : VeranaDocumentRepository {

    override suspend fun fetchJson(url: String): JsonElement? =
        fetchOk(url)?.let(VeranaTrustResponseParser::parseJson)

    override suspend fun fetchDidDocument(did: String): VeranaDidDocument? {
        val url = VeranaDids.documentUrl(did) ?: return null
        val body = fetchOk(url) ?: return null
        val document = if (VeranaDids.isWebvh(did)) {
            VeranaDids.lastLogEntry(body)
                ?.let(VeranaTrustResponseParser::parseJson)
                .let(VeranaDids::logEntryState)
        } else {
            VeranaTrustResponseParser.parseJson(body)
        }
        return VeranaDids.parseDocument(did, document)
    }

    override suspend fun fetchSignedIssuerMetadata(credentialIssuer: String): String? =
        signedIssuerMetadataUrls(credentialIssuer).firstNotNullOfOrNull { url ->
            fetchOk(url, accept = APPLICATION_JWT)?.trim()?.takeIf(COMPACT_JWS::matches)
        }

    private suspend fun fetchOk(url: String, accept: ContentType = ContentType.Application.Json): String? =
        httpClient.get(url, accept)?.takeIf { it.status == HTTP_OK }?.body

    private companion object {
        const val HTTP_OK = 200
        val COMPACT_JWS = Regex("""^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$""")
        val APPLICATION_JWT = ContentType("application", "jwt")
    }
}

internal fun signedIssuerMetadataUrls(credentialIssuer: String): List<String> {
    val issuer = credentialIssuer.trimEnd('/')
    val uri = runCatching { URI(issuer) }.getOrNull() ?: return emptyList()
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return emptyList()

    val wellKnown = "https://${uri.rawAuthority}/.well-known/openid-credential-issuer"
    val path = uri.rawPath.orEmpty()
    return if (path.isEmpty()) {
        listOf(wellKnown)
    } else {
        listOf("$wellKnown$path", "$issuer/.well-known/openid-credential-issuer")
    }
}
