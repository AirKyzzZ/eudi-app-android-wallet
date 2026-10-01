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
import eu.europa.ec.networklogic.model.VeranaNetwork
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface VeranaTrustRepository {
    suspend fun resolveTrust(did: String): VeranaTrustResolution?
    suspend fun resolveSchemaEcosystemDid(vct: String): String?
    suspend fun holdsActiveParticipant(did: String, role: String, vct: String): Boolean
    suspend fun resolveEcosystemName(ecosystemDid: String): String?
    suspend fun fetchSignedIssuerMetadata(credentialIssuer: String): SignedIssuerMetadata?
    suspend fun fetchDidDocument(domain: String): JsonObject?
    suspend fun verifyEddsaJcs2022(document: JsonObject, issuerDocument: JsonObject): Boolean
}

class VeranaTrustRepositoryImpl(
    private val httpClient: HttpClient,
    private val resolverUrl: String, // Kept for DI signature compatibility, unused
    private val apiUrl: String,
) : VeranaTrustRepository {

    private companion object {
        val VERANA_NETWORKS = listOf(
            VeranaNetwork("vna-devnet-1", "Devnet", "https://idx.devnet.verana.network", "https://explorer.devnet.verana.network", false, listOf("did:webvh:QmdLKxG6ZdBRJbUevm1mQdPr3arKSE3bg5RfA8cg8uUszF:playground-demo.playground.devnet.verana.network")),
            VeranaNetwork("vna-testnet-1", "Testnet", "https://idx.testnet.verana.network", "https://explorer.testnet.verana.network", false)
        )
        val VPR_SCHEMA_ID = Regex("""/cs/v\d+/js/(\d+)\b""")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun resolveTrust(did: String): VeranaTrustResolution? {
        if (!did.startsWith("did:web:") && !did.startsWith("did:webvh:")) {
            return VeranaTrustResolution(did, "UNTRUSTED", reason = "this service cannot present verifiable trust credentials")
        }

        return coroutineScope {
            val jobs = VERANA_NETWORKS.map { network ->
                async { queryNetwork(network, did) }
            }
            val results = mutableListOf<VeranaTrustResolution?>()
            for (job in jobs) {
                val res = job.await()
                if (res != null) {
                    if (res.trustStatus == "TRUSTED") {
                        jobs.forEach { it.cancel() }
                        return@coroutineScope res
                    }
                    results.add(res)
                }
            }
            if (results.isEmpty()) {
                return@coroutineScope VeranaTrustResolution(did, "UNVERIFIED")
            }
            results.firstOrNull { it?.reason != "not registered" } ?: results.firstOrNull()
        }
    }

    private suspend fun queryNetwork(network: VeranaNetwork, did: String): VeranaTrustResolution? = runCatching {
        val body = """{"did":"$did","participations":{"states":["ACTIVE","EXPIRED","REVOKED"]},"presentations":{"unresolvableCredentialIds":true},"ecsCredentials":true}"""
        val response = httpClient.post("${network.indexerUrl.trimEnd('/')}/v4/verifiable-trust/resolve") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status == HttpStatusCode.NotFound) {
            val errBody = response.bodyAsText()
            if (errBody.contains("DID not found")) {
                return@runCatching VeranaTrustResolution(did, "UNTRUSTED", network = network, reason = "not registered")
            }
            return@runCatching null
        }
        if (!response.status.isSuccessValue()) return@runCatching null

        val resJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (resJson.stringValue("did") != did) return@runCatching null

        val isTrusted = (resJson["trusted"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        if (!isTrusted) return@runCatching VeranaTrustResolution(did, "UNTRUSTED", network = network, reason = "not trusted")

        val ecs = resJson["ecsCredentials"]?.jsonArray?.mapNotNull {
            val o = it.jsonObject
            val schema = o.stringValue("ecsSchema") ?: return@mapNotNull null
            val ecosystemId = (o["ecosystemId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            eu.europa.ec.networklogic.model.VeranaEcsCredential(schema, ecosystemId, o["claims"]?.jsonObject)
        } ?: emptyList()

        if (network.trustedEcsEcosystemDids != null) {
            for (cred in ecs) {
                val ecoId = cred.ecosystemId ?: return@runCatching null
                val ecoRes = httpClient.get("${network.indexerUrl.trimEnd('/')}/v4/ecosystem/get/$ecoId")
                if (!ecoRes.status.isSuccessValue()) return@runCatching null
                val ecoDid = json.parseToJsonElement(ecoRes.bodyAsText()).jsonObject["ecosystem"]?.jsonObject?.stringValue("did")
                if (ecoDid !in network.trustedEcsEcosystemDids) {
                    return@runCatching VeranaTrustResolution(did, "UNTRUSTED", network = network, reason = "ecosystem not trusted")
                }
            }
        }

        VeranaTrustResolution(did, "TRUSTED", network = network, ecsCredentials = ecs, evaluatedAt = resJson.stringValue("evaluatedAtTime"), expiresAt = resJson.stringValue("expiresAtTime"))
    }.getOrNull()

    override suspend fun resolveSchemaEcosystemDid(vct: String): String? = runCatching {
        val typeMetadata = json.parseToJsonElement(httpClient.get(vct).bodyAsText()).jsonObject
        val vtjscId = typeMetadata.stringValue("relatedJsonSchemaCredentialId") ?: return@runCatching null
        val vtjsc = json.parseToJsonElement(httpClient.get(vtjscId).bodyAsText()).jsonObject
        val issuer = vtjsc.stringValue("issuer") ?: return@runCatching null
        
        val validFrom = vtjsc.stringValue("validFrom")
        val validUntil = vtjsc.stringValue("validUntil")
        val now = java.time.Instant.now()
        if (validFrom != null && java.time.Instant.parse(validFrom).isAfter(now)) return@runCatching null
        if (validUntil == null || java.time.Instant.parse(validUntil).isBefore(now)) return@runCatching null

        val subject = vtjsc["credentialSubject"]?.jsonObject ?: return@runCatching null
        val ref = subject["jsonSchema"]?.jsonObject?.stringValue("\$ref") ?: return@runCatching null
        
        val parts = ref.split(":")
        if (parts.size != 5 || parts[0] != "vpr" || parts[1] != "verana" || parts[3] != "cs") return@runCatching null
        val networkId = parts[2]
        val schemaId = parts[4]

        val network = VERANA_NETWORKS.find { it.id == networkId } ?: return@runCatching null
        val schemaRes = httpClient.get("${network.indexerUrl.trimEnd('/')}/v4/credential-schema/get/$schemaId")
        if (!schemaRes.status.isSuccessValue()) return@runCatching null
        val ecoId = json.parseToJsonElement(schemaRes.bodyAsText()).jsonObject["schema"]?.jsonObject?.stringValue("ecosystem_id") ?: return@runCatching null

        val ecoRes = httpClient.get("${network.indexerUrl.trimEnd('/')}/v4/ecosystem/get/$ecoId")
        if (!ecoRes.status.isSuccessValue()) return@runCatching null
        val ecoDid = json.parseToJsonElement(ecoRes.bodyAsText()).jsonObject["ecosystem"]?.jsonObject?.stringValue("did")

        if (ecoDid == issuer) ecoDid else null
    }.getOrNull()

    override suspend fun holdsActiveParticipant(did: String, role: String, vct: String): Boolean = runCatching {
        val typeMetadata = json.parseToJsonElement(httpClient.get(vct).bodyAsText()).jsonObject
        val vtjscId = typeMetadata.stringValue("relatedJsonSchemaCredentialId") ?: return@runCatching false
        val vtjsc = json.parseToJsonElement(httpClient.get(vtjscId).bodyAsText()).jsonObject
        val subject = vtjsc["credentialSubject"]?.jsonObject ?: return@runCatching false
        val ref = subject["jsonSchema"]?.jsonObject?.stringValue("\$ref") ?: return@runCatching false
        
        val parts = ref.split(":")
        val networkId = parts[2]
        val schemaId = parts[4]
        val network = VERANA_NETWORKS.find { it.id == networkId } ?: return@runCatching false

        val res = httpClient.get("${network.indexerUrl.trimEnd('/')}/v4/participant/list?did=${did.encodeURLParameter()}&role=$role&schema_id=$schemaId&participant_state=ACTIVE")
        if (!res.status.isSuccessValue()) return@runCatching false
        val participants = json.parseToJsonElement(res.bodyAsText()).jsonObject["participants"]?.jsonArray ?: return@runCatching false
        participants.isNotEmpty()
    }.getOrDefault(false)

    override suspend fun resolveEcosystemName(ecosystemDid: String): String? = runCatching {
        val res = resolveTrust(ecosystemDid) ?: return@runCatching null
        if (res.trustStatus != "TRUSTED") return@runCatching null
        val s = res.ecsCredentials.find { it.ecsSchema == "ServiceCredential" } ?: return@runCatching null
        (s.claims?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
    }.getOrNull()

    override suspend fun verifyEddsaJcs2022(document: JsonObject, issuerDocument: JsonObject): Boolean = runCatching {
        val proof = document["proof"]?.jsonObject ?: return@runCatching false
        if (proof.stringValue("type") != "DataIntegrityProof" ||
            proof.stringValue("cryptosuite") != "eddsa-jcs-2022" ||
            proof.stringValue("proofPurpose") != "assertionMethod") return@runCatching false

        val proofValue = proof.stringValue("proofValue") ?: return@runCatching false
        if (!proofValue.startsWith("z")) return@runCatching false

        val issuer = document.stringValue("issuer") ?: document["issuer"]?.jsonObject?.stringValue("id")
        if (issuer != issuerDocument.stringValue("id")) return@runCatching false

        val assertionMethods = issuerDocument["assertionMethod"]?.jsonArray?.mapNotNull {
            if (it is kotlinx.serialization.json.JsonPrimitive) {
                issuerDocument["verificationMethod"]?.jsonArray?.find { vm ->
                    vm.jsonObject.stringValue("id") == it.content || vm.jsonObject.stringValue("id") == "${issuerDocument.stringValue("id")}${it.content}"
                }?.jsonObject
            } else it.jsonObject
        } ?: emptyList()

        val method = assertionMethods.find {
            it.stringValue("id") == proof.stringValue("verificationMethod") ||
            it.stringValue("id") == "${issuerDocument.stringValue("id")}${proof.stringValue("verificationMethod")}"
        } ?: return@runCatching false

        val publicKey = getEd25519PublicKey(method) ?: return@runCatching false

        val unsecuredMutable = document.toMutableMap()
        unsecuredMutable.remove("proof")
        val unsecured = JsonObject(unsecuredMutable)

        val proofConfigMutable = proof.toMutableMap()
        proofConfigMutable.remove("proofValue")
        if (unsecured.containsKey("@context")) {
            proofConfigMutable["@context"] = unsecured["@context"]!!
        }
        val proofConfig = JsonObject(proofConfigMutable)

        val hashData = java.security.MessageDigest.getInstance("SHA-256").digest(canonicalize(proofConfig).toByteArray(Charsets.UTF_8)) +
            java.security.MessageDigest.getInstance("SHA-256").digest(canonicalize(unsecured).toByteArray(Charsets.UTF_8))

        val sigBytes = base58btcDecode(proofValue.substring(1))

        val keySpec = java.security.spec.X509EncodedKeySpec(
            byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + publicKey
        )
        val key = java.security.KeyFactory.getInstance("Ed25519").generatePublic(keySpec)
        val verifier = java.security.Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(hashData)
        verifier.verify(sigBytes)
    }.getOrDefault(false)

    private fun canonicalize(element: kotlinx.serialization.json.JsonElement): String {
        if (element is kotlinx.serialization.json.JsonArray) {
            return "[" + element.joinToString(",") { canonicalize(it) } + "]"
        }
        if (element is JsonObject) {
            val members = element.entries.filter { it.value !is kotlinx.serialization.json.JsonNull }
                .sortedBy { it.key }
                .joinToString(",") { "\"${it.key}\":${canonicalize(it.value)}" }
            return "{$members}"
        }
        return element.toString()
    }

    private fun getEd25519PublicKey(method: JsonObject): ByteArray? {
        val mb = method.stringValue("publicKeyMultibase")
        if (mb?.startsWith("z") == true) {
            val key = base58btcDecode(mb.substring(1))
            if (key.size == 34 && key[0] == 0xed.toByte() && key[1] == 0x01.toByte()) {
                return key.copyOfRange(2, 34)
            }
        }
        val jwk = method["publicKeyJwk"]?.jsonObject
        if (jwk?.stringValue("kty") == "OKP" && jwk.stringValue("crv") == "Ed25519") {
            val x = jwk.stringValue("x") ?: return null
            return java.util.Base64.getUrlDecoder().decode(x)
        }
        return null
    }

    private val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private fun base58btcDecode(value: String): ByteArray {
        var number = java.math.BigInteger.ZERO
        for (character in value) {
            val digit = BASE58_ALPHABET.indexOf(character)
            require(digit >= 0)
            number = number.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(digit.toLong()))
        }
        var bytes = number.toByteArray()
        if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        var leadingZeros = 0
        for (character in value) {
            if (character == '1') leadingZeros++ else break
        }
        return ByteArray(leadingZeros) + bytes
    }

    override suspend fun fetchSignedIssuerMetadata(credentialIssuer: String): SignedIssuerMetadata? =
        runCatching {
            val response = httpClient.get("${credentialIssuer.trimEnd('/')}/.well-known/openid-credential-issuer") { accept(ContentType("application", "jwt")) }
            if (!response.status.isSuccessValue()) return@runCatching null
            val token = response.bodyAsText().trim()
            if (!token.startsWith("ey")) return@runCatching null

            val decoded = VeranaJws.decode(token) ?: return@runCatching null
            val kid = decoded.kid ?: return@runCatching null
            val did = kid.substringBefore('#').takeIf { it.startsWith("did:") } ?: return@runCatching null

            val verified = fetchDidDocument(didDomain(did))?.let { VeranaJws.verifyEs256(decoded, it, kid) } == true
            
            // Replay check
            val payload = decoded.payload
            val sub = payload.stringValue("sub")
            val iss = payload.stringValue("credential_issuer")
            if (sub != credentialIssuer && iss != credentialIssuer) return@runCatching null

            SignedIssuerMetadata(kid = kid, did = did, payload = payload, signatureVerified = verified)
        }.getOrNull()

    override suspend fun fetchDidDocument(domain: String): JsonObject? = runCatching {
        val paths = listOf("/.well-known/did.jsonl", "/did.jsonl", "/.well-known/did.json", "/did.json")
        for (path in paths) {
            val response = httpClient.get("https://$domain$path")
            if (response.status.isSuccessValue()) {
                val body = response.bodyAsText()
                if (path.endsWith(".jsonl")) {
                    val lastLine = body.lines().lastOrNull { it.isNotBlank() } ?: continue
                    val doc = json.parseToJsonElement(lastLine).jsonObject["state"]?.jsonObject
                    if (doc != null) return doc
                } else {
                    return json.parseToJsonElement(body).jsonObject
                }
            }
        }
        null
    }.getOrNull()

    private fun HttpStatusCode.isSuccessValue(): Boolean = value in 200..299

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
}

fun didDomain(did: String): String {
    val parts = did.split(':')
    return when {
        parts.size >= 3 && parts[1] == "web" -> parts[2]
        parts.size >= 4 && parts[1] == "webvh" -> parts[3]
        else -> parts.last()
    }
}
