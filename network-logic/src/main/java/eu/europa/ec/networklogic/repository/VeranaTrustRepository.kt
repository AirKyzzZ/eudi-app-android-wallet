/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.networklogic.repository

import eu.europa.ec.networklogic.model.SignedIssuerMetadata
import eu.europa.ec.networklogic.model.VeranaPermission
import eu.europa.ec.networklogic.model.VeranaRole
import eu.europa.ec.networklogic.model.VeranaSchema
import eu.europa.ec.networklogic.model.VeranaTrustResolution
import eu.europa.ec.networklogic.util.VeranaJws
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface VeranaTrustRepository {

    /**
     * Q1. Null means the resolver could not be reached or answered malformed, which the caller
     * must render as UNVERIFIED, never as a refusal. A 404 is retried once behind a refresh:
     * evaluations expire an hour after they are made and a stale DID answers 404 in a way that
     * is otherwise indistinguishable from an unknown one.
     */
    suspend fun resolveTrust(did: String, fullDetail: Boolean): VeranaTrustResolution?

    /** Q2/Q3 raw material, from the VPR itself; the resolver answers Q1 only. */
    suspend fun fetchSchemas(): List<VeranaSchema>?

    suspend fun fetchPermissions(): List<VeranaPermission>?

    suspend fun fetchPermissionsForDid(
        did: String,
        role: VeranaRole,
        schemaId: String,
    ): List<VeranaPermission>?

    suspend fun resolveSchemaIdFromVct(vct: String): String?

    suspend fun fetchSignedIssuerMetadata(credentialIssuer: String): SignedIssuerMetadata?

    /** Parsed `https://<domain>/.well-known/did.json`, the parallel did:web document. */
    suspend fun fetchDidDocument(domain: String): JsonObject?
}

class VeranaTrustRepositoryImpl(
    private val httpClient: HttpClient,
    private val resolverUrl: String,
    private val apiUrl: String,
) : VeranaTrustRepository {

    private companion object {
        // The VPR list endpoints ignore pagination and default to 64 records, which would make
        // an accredited issuer beyond record 64 look unaccredited. A full page is treated as
        // truncated rather than complete.
        const val RESPONSE_MAX_SIZE = 1000
        val VPR_SCHEMA_ID = Regex("""/cs/v\d+/js/(\d+)\b""")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun resolveTrust(did: String, fullDetail: Boolean): VeranaTrustResolution? {
        val first = resolveOnce(did, fullDetail) ?: return null
        if (first.status != HttpStatusCode.NotFound) return first.resolution?.takeIf { it.did == did }

        runCatching {
            httpClient.post("${resolverUrl.trimEnd('/')}/v1/trust/refresh") {
                contentType(ContentType.Application.Json)
                setBody("""{"did":"${did}"}""")
            }
        }
        val second = resolveOnce(did, fullDetail) ?: return null
        return second.resolution?.takeIf { it.did == did }
    }

    private data class ResolveAttempt(val status: HttpStatusCode, val resolution: VeranaTrustResolution?)

    private suspend fun resolveOnce(did: String, fullDetail: Boolean): ResolveAttempt? = runCatching {
        val detail = if (fullDetail) "full" else "summary"
        val response = httpClient.get(
            "${resolverUrl.trimEnd('/')}/v1/trust/resolve?did=${did.encodeURLParameter()}&detail=$detail"
        )
        if (!response.status.isSuccessValue()) return@runCatching ResolveAttempt(response.status, null)
        ResolveAttempt(response.status, json.decodeFromString<VeranaTrustResolution>(response.bodyAsText()))
    }.getOrNull()

    override suspend fun fetchSchemas(): List<VeranaSchema>? = runCatching {
        val response = httpClient.get(
            "${apiUrl.trimEnd('/')}/verana/cs/v1/list?response_max_size=$RESPONSE_MAX_SIZE"
        )
        if (!response.status.isSuccessValue()) return@runCatching null
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val schemas = body["schemas"]?.jsonArray ?: return@runCatching null
        if (schemas.size >= RESPONSE_MAX_SIZE) return@runCatching null

        schemas.mapNotNull { entry ->
            val record = entry as? JsonObject ?: return@mapNotNull null
            val id = record.stringValue("id") ?: return@mapNotNull null
            var title: String? = null
            var vprId: String? = null
            record.stringValue("json_schema")?.let { raw ->
                runCatching {
                    val parsed = json.parseToJsonElement(raw).jsonObject
                    title = parsed.stringValue("title")
                    vprId = parsed.stringValue("\$id")
                }
            }
            VeranaSchema(id = id, trustRegistryId = record.stringValue("tr_id"), title = title, vprId = vprId)
        }
    }.getOrNull()

    override suspend fun fetchPermissions(): List<VeranaPermission>? = runCatching {
        val response = httpClient.get(
            "${apiUrl.trimEnd('/')}/verana/perm/v1/list?response_max_size=$RESPONSE_MAX_SIZE"
        )
        if (!response.status.isSuccessValue()) return@runCatching null
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val permissions = body["permissions"]?.jsonArray ?: return@runCatching null
        if (permissions.size >= RESPONSE_MAX_SIZE) return@runCatching null

        permissions.mapNotNull { entry ->
            runCatching { json.decodeFromJsonElement(VeranaPermission.serializer(), entry) }.getOrNull()
        }.filter { it.id != null && it.schemaId != null && it.type != null }
    }.getOrNull()

    override suspend fun fetchPermissionsForDid(
        did: String,
        role: VeranaRole,
        schemaId: String,
    ): List<VeranaPermission>? = runCatching {
        val response = httpClient.get(
            "${apiUrl.trimEnd('/')}/verana/perm/v1/find_with_did" +
                "?did=${did.encodeURLParameter()}" +
                "&type=${role.vprTypeCode}" +
                "&schema_id=${schemaId.encodeURLParameter()}"
        )
        // 404 means the schema is not on chain, a determinate zero rather than a failure, so it denies.
        if (response.status == HttpStatusCode.NotFound) return@runCatching emptyList<VeranaPermission>()
        if (!response.status.isSuccessValue()) return@runCatching null
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val permissions = body["permissions"]?.jsonArray ?: return@runCatching null

        permissions.mapNotNull { entry ->
            runCatching { json.decodeFromJsonElement(VeranaPermission.serializer(), entry) }.getOrNull()
        }.filter { it.id != null && it.schemaId != null && it.type != null }
    }.getOrNull()

    /**
     * The credential's own link to its schema: the SD-JWT type metadata names the VTJSC, and the
     * VTJSC names the VPR schema id. Following it checks accreditation against the schema the
     * issuer actually committed to, not a credential title anyone can reuse.
     */
    override suspend fun resolveSchemaIdFromVct(vct: String): String? {
        VPR_SCHEMA_ID.find(vct)?.let { return it.groupValues[1] }
        if (!vct.startsWith("https://")) return null

        return runCatching {
            val typeMetadata = json.parseToJsonElement(httpClient.get(vct).bodyAsText()).jsonObject
            val vtjscId = typeMetadata.stringValue("relatedJsonSchemaCredentialId")
                ?.takeIf { it.startsWith("https://") } ?: return@runCatching null

            val vtjsc = json.parseToJsonElement(httpClient.get(vtjscId).bodyAsText()).jsonObject
            val subject = vtjsc["credentialSubject"] as? JsonObject ?: return@runCatching null
            val jsonSchema = subject["jsonSchema"] as? JsonObject
            val pointer = jsonSchema?.stringValue("\$id")
                ?: jsonSchema?.stringValue("\$ref")
                ?: subject.stringValue("id")
            pointer?.let { VPR_SCHEMA_ID.find(it)?.groupValues?.get(1) }
        }.getOrNull()
    }

    override suspend fun fetchSignedIssuerMetadata(credentialIssuer: String): SignedIssuerMetadata? =
        runCatching {
            val response = httpClient.get(
                "${credentialIssuer.trimEnd('/')}/.well-known/openid-credential-issuer"
            ) { accept(ContentType("application", "jwt")) }
            if (!response.status.isSuccessValue()) return@runCatching null
            val token = response.bodyAsText().trim()
            if (!token.startsWith("ey")) return@runCatching null

            val decoded = VeranaJws.decode(token) ?: return@runCatching null
            val kid = decoded.kid ?: return@runCatching null
            val did = kid.substringBefore('#').takeIf { it.startsWith("did:") } ?: return@runCatching null

            val verified = fetchDidDocument(didDomain(did))
                ?.let { VeranaJws.verifyEs256(decoded, it, kid) } == true
            SignedIssuerMetadata(kid = kid, did = did, payload = decoded.payload, signatureVerified = verified)
        }.getOrNull()

    override suspend fun fetchDidDocument(domain: String): JsonObject? = runCatching {
        val response = httpClient.get("https://$domain/.well-known/did.json")
        if (!response.status.isSuccessValue()) return@runCatching null
        json.parseToJsonElement(response.bodyAsText()).jsonObject
    }.getOrNull()

    private fun HttpStatusCode.isSuccessValue(): Boolean = value in 200..299

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
}

/** `did:web:<domain>` and `did:webvh:<scid>:<domain>` both name the host their document lives on. */
fun didDomain(did: String): String {
    val parts = did.split(':')
    return when {
        parts.size >= 3 && parts[1] == "web" -> parts[2]
        parts.size >= 4 && parts[1] == "webvh" -> parts[3]
        else -> parts.last()
    }
}
