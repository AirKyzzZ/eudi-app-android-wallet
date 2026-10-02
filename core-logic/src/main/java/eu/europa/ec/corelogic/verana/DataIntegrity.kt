package eu.europa.ec.corelogic.verana

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.Ed25519Verifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.util.Base64URL
import eu.europa.ec.networklogic.model.VeranaDidDocument
import eu.europa.ec.networklogic.model.VeranaVerificationMethod
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime

object DataIntegrity {
    private const val PROOF = "proof"
    private const val PROOF_VALUE = "proofValue"
    private const val CONTEXT = "@context"
    private const val MULTIBASE_BASE58BTC = 'z'
    private const val ED25519_KEY_LENGTH = 32
    private val ED25519_MULTICODEC = byteArrayOf(0xed.toByte(), 0x01)

    fun issuerOf(credential: JsonObject): String? = when (val issuer = credential["issuer"]) {
        is JsonObject -> issuer.string("id")
        else -> credential.string("issuer")
    }

    fun withinValidity(credential: JsonObject, now: Instant): Boolean =
        credential.boundHolds("validFrom") { from -> !from.isAfter(now) } &&
                credential.boundHolds("validUntil") { until -> until.isAfter(now) }

    fun verifyEddsaJcs2022(document: JsonObject, issuerDocument: VeranaDidDocument): Boolean {
        val proof = (document[PROOF] as? JsonObject)?.takeIf { it.isEddsaJcs2022AssertionProof() } ?: return false
        val unsecured = JsonObject(document - PROOF)
        val proofValue = proof.string(PROOF_VALUE)?.takeIf { it.firstOrNull() == MULTIBASE_BASE58BTC } ?: return false
        if (issuerOf(unsecured) != issuerDocument.id) return false

        val verificationMethod = proof.string("verificationMethod")
        val publicKey = issuerDocument.assertionMethods
            .firstOrNull { it.id == verificationMethod }
            ?.ed25519PublicKey()
            ?: return false

        val proofConfig = (proof - PROOF_VALUE).toMutableMap<String, JsonElement>()
        unsecured[CONTEXT]?.let { proofConfig[CONTEXT] = it }

        return runCatching {
            val hashData = sha256(Jcs.canonicalize(JsonObject(proofConfig))) + sha256(Jcs.canonicalize(unsecured))
            verifyEd25519(
                publicKey = publicKey,
                data = hashData,
                signature = Base58Btc.decode(proofValue.substring(1)),
            )
        }.getOrDefault(false)
    }

    private fun JsonObject.isEddsaJcs2022AssertionProof(): Boolean =
        string("type") == "DataIntegrityProof" &&
                string("cryptosuite") == "eddsa-jcs-2022" &&
                string("proofPurpose") == "assertionMethod"

    private fun VeranaVerificationMethod.ed25519PublicKey(): ByteArray? =
        publicKeyMultibase?.let(::ed25519FromMultibase) ?: publicKeyJwk?.let(::ed25519FromJwk)

    private fun ed25519FromMultibase(multibase: String): ByteArray? = multibase
        .takeIf { it.firstOrNull() == MULTIBASE_BASE58BTC }
        ?.let { runCatching { Base58Btc.decode(it.substring(1)) }.getOrNull() }
        ?.takeIf { key ->
            key.size == ED25519_MULTICODEC.size + ED25519_KEY_LENGTH &&
                    key.copyOfRange(0, ED25519_MULTICODEC.size).contentEquals(ED25519_MULTICODEC)
        }
        ?.let { key -> key.copyOfRange(ED25519_MULTICODEC.size, key.size) }

    private fun ed25519FromJwk(jwk: JsonObject): ByteArray? = jwk.string("x")
        ?.takeIf { jwk.string("kty") == "OKP" && jwk.string("crv") == "Ed25519" }
        ?.let { runCatching { Base64URL(it).decode() }.getOrNull() }
        ?.takeIf { it.size == ED25519_KEY_LENGTH }

    // Through nimbus, whose Ed25519 runs on Tink: java.security has no Ed25519 below API 33.
    private fun verifyEd25519(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        val key = OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(publicKey)).build()
        return Ed25519Verifier(key).verify(JWSHeader(JWSAlgorithm.EdDSA), data, Base64URL.encode(signature))
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun JsonObject.boundHolds(name: String, holds: (Instant) -> Boolean): Boolean {
        val bound = get(name) ?: return true
        val time = (bound as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
        return time != null && holds(time)
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
