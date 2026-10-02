package eu.europa.ec.corelogic.verana

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.util.X509CertUtils
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import eu.europa.ec.corelogic.model.VeranaIssuerIdentity
import eu.europa.ec.networklogic.repository.VeranaDocumentRepository
import kotlinx.serialization.json.JsonObject
import java.security.interfaces.ECPublicKey
import java.time.Instant

fun interface VerifyVeranaIssuerMetadata {
    suspend operator fun invoke(jwt: String, credentialIssuer: String): VeranaIssuerIdentity
}

class VerifyVeranaIssuerMetadataImpl(
    private val documentRepository: VeranaDocumentRepository,
    private val clock: () -> Instant = Instant::now,
) : VerifyVeranaIssuerMetadata {

    override suspend fun invoke(jwt: String, credentialIssuer: String): VeranaIssuerIdentity {
        val signed = runCatching { SignedJWT.parse(jwt) }.getOrNull() ?: return VeranaIssuerIdentity.NoDid
        val leaf = runCatching { signed.header.x509CertChain?.firstOrNull()?.decode() }.getOrNull()
            ?.let(X509CertUtils::parse)
        val kid = signed.header.keyID?.takeIf { it.startsWith(DID_PREFIX) }
        val did = when {
            leaf != null -> VeranaX509.didOf(leaf)
            else -> kid?.substringBefore('#')
        } ?: return VeranaIssuerIdentity.NoDid

        val key = if (leaf != null) {
            VeranaX509.p256Key(leaf)
        } else {
            val document = documentRepository.fetchDidDocument(did) ?: return VeranaIssuerIdentity.Unavailable(did)
            document.assertionMethods.firstOrNull { it.id == kid }?.publicKeyJwk?.let(::p256Key)
        } ?: return VeranaIssuerIdentity.NotProven(did)

        val claims = runCatching { signed.jwtClaimsSet }.getOrNull() ?: return VeranaIssuerIdentity.NotProven(did)
        val signatureHolds = signed.header.algorithm == JWSAlgorithm.ES256 &&
                runCatching { signed.verify(ECDSAVerifier(key)) }.getOrDefault(false)

        return if (signatureHolds && claims.namesIssuer(credentialIssuer) && !claims.expiredAt(clock())) {
            VeranaIssuerIdentity.Signed(did = did, certificateKey = key)
        } else {
            VeranaIssuerIdentity.NotProven(did)
        }
    }

    private fun p256Key(jwk: JsonObject): ECPublicKey? = runCatching { ECKey.parse(jwk.toString()).toECPublicKey() }
        .getOrNull()
        ?.takeIf(VeranaX509::isP256)

    private fun JWTClaimsSet.namesIssuer(credentialIssuer: String): Boolean {
        val named = listOf(getClaim("credential_issuer"), getClaim("sub")).filterIsInstance<String>()
        return named.isNotEmpty() && named.all { it.trimEnd('/') == credentialIssuer.trimEnd('/') }
    }

    private fun JWTClaimsSet.expiredAt(now: Instant): Boolean =
        expirationTime?.toInstant()?.isBefore(now) == true

    private companion object {
        const val DID_PREFIX = "did:"
    }
}
