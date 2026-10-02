package eu.europa.ec.corelogic.verana

import com.nimbusds.jose.util.Base64URL
import eu.europa.ec.corelogic.model.VeranaDidKeyBinding
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaVerificationMethod
import eu.europa.ec.networklogic.repository.VeranaDocumentRepository
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigInteger
import java.security.interfaces.ECPublicKey

fun interface VerifyVeranaDidKeyBinding {
    suspend operator fun invoke(
        did: String,
        role: VeranaTrustRole,
        certificateKey: ECPublicKey,
    ): VeranaDidKeyBinding
}

class VerifyVeranaDidKeyBindingImpl(
    private val documentRepository: VeranaDocumentRepository,
) : VerifyVeranaDidKeyBinding {

    override suspend fun invoke(
        did: String,
        role: VeranaTrustRole,
        certificateKey: ECPublicKey,
    ): VeranaDidKeyBinding {
        val document = documentRepository.fetchDidDocument(did) ?: return VeranaDidKeyBinding.UNAVAILABLE
        // The devnet verifiers list their request-signing key under authentication only, not assertionMethod.
        val methods = when (role) {
            VeranaTrustRole.ISSUER -> document.assertionMethods
            VeranaTrustRole.VERIFIER -> document.assertionMethods + document.authenticationMethods
        }
        return if (VeranaX509.isP256(certificateKey) && methods.any { it.hasKey(certificateKey) }) {
            VeranaDidKeyBinding.PROVEN
        } else {
            VeranaDidKeyBinding.NOT_PROVEN
        }
    }

    private fun VeranaVerificationMethod.hasKey(key: ECPublicKey): Boolean {
        val jwk = publicKeyJwk ?: return false
        fun field(name: String) = (jwk[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (field("kty") != EC || field("crv") != P_256) return false
        return sameCoordinate(field("x"), key.w.affineX) && sameCoordinate(field("y"), key.w.affineY)
    }

    private fun sameCoordinate(published: String?, presented: BigInteger): Boolean {
        val bytes = published?.let { runCatching { Base64URL(it).decode() }.getOrNull() } ?: return false
        return bytes.isNotEmpty() && BigInteger(1, bytes) == presented
    }

    private companion object {
        const val EC = "EC"
        const val P_256 = "P-256"
    }
}
