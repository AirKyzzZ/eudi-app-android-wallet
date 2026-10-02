package eu.europa.ec.networklogic.util

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object VeranaJws {

    data class DecodedJws(
        val kid: String?,
        val alg: String?,
        val header: JsonObject,
        val payload: JsonObject,
        val signingInput: ByteArray,
        val signature: ByteArray,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun decode(token: String): DecodedJws? = runCatching {
        val parts = token.split('.')
        if (parts.size != 3) return@runCatching null
        val header = json.parseToJsonElement(parts[0].b64UrlDecode().decodeToString()).jsonObject
        DecodedJws(
            kid = header.string("kid"),
            alg = header.string("alg"),
            header = header,
            payload = json.parseToJsonElement(parts[1].b64UrlDecode().decodeToString()).jsonObject,
            signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII),
            signature = parts[2].b64UrlDecode(),
        )
    }.getOrNull()

    fun verifyEs256(jws: DecodedJws, didDocument: JsonObject, kid: String): Boolean {
        if (jws.alg != "ES256") return false
        val fragment = kid.substringAfter('#', missingDelimiterValue = "")
        val keys = p256Keys(didDocument)
        val preferred = keys.filter { fragment.isNotEmpty() && it.first.endsWith("#$fragment") }
        val candidates = preferred.ifEmpty { keys }
        return candidates.any { (_, key) -> verify(jws, key) }
    }
    
    fun verifyX5c(jws: DecodedJws, cert: X509Certificate): Boolean {
        if (jws.alg != "ES256") return false
        val key = cert.publicKey as? ECPublicKey ?: return false
        return verify(jws, key)
    }

    fun parseCertificate(base64: String): X509Certificate? = runCatching {
        val bytes = Base64.getDecoder().decode(base64)
        val cf = CertificateFactory.getInstance("X.509")
        cf.generateCertificate(bytes.inputStream()) as X509Certificate
    }.getOrNull()

    fun p256Keys(didDocument: JsonObject): List<Pair<String, ECPublicKey>> =
        didDocument["assertionMethod"]
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { method ->
                runCatching {
                    val record = method.jsonObject
                    val id = record.string("id") ?: return@runCatching null
                    val jwk = record["publicKeyJwk"]?.jsonObject ?: return@runCatching null
                    if (jwk.string("kty") != "EC" || jwk.string("crv") != "P-256") return@runCatching null
                    val x = jwk.string("x") ?: return@runCatching null
                    val y = jwk.string("y") ?: return@runCatching null
                    id to ecPublicKey(x, y)
                }.getOrNull()
            } ?: emptyList()

    fun ecPublicKey(xB64Url: String, yB64Url: String): ECPublicKey {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, xB64Url.b64UrlDecode()), BigInteger(1, yB64Url.b64UrlDecode()))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, spec)) as ECPublicKey
    }

    private fun verify(jws: DecodedJws, key: ECPublicKey): Boolean = runCatching {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(jws.signingInput)
        verifier.verify(rawSignatureToDer(jws.signature))
    }.getOrDefault(false)

    private fun rawSignatureToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "ES256 signature must be 64 bytes" }
        val r = BigInteger(1, raw.copyOfRange(0, 32)).toByteArray()
        val s = BigInteger(1, raw.copyOfRange(32, 64)).toByteArray()
        val length = 2 + r.size + 2 + s.size
        val header = if (length < 128) byteArrayOf(0x30, length.toByte()) else byteArrayOf(0x30, 0x81.toByte(), length.toByte())
        return header + byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
    }

    private fun String.b64UrlDecode(): ByteArray = Base64.getUrlDecoder().decode(this)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
}
