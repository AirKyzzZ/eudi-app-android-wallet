package eu.europa.ec.corelogic.verana

import com.nimbusds.jose.jwk.Curve
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey

object VeranaX509 {
    private const val URI_NAME = 6
    private const val DID_PREFIX = "did:"

    fun didOf(certificate: X509Certificate): String? =
        runCatching { certificate.subjectAlternativeNames }.getOrNull()
            ?.firstNotNullOfOrNull { name ->
                (name.getOrNull(1) as? String)?.takeIf { name.getOrNull(0) == URI_NAME && it.startsWith(DID_PREFIX) }
            }
            ?.substringBefore('#')

    fun p256Key(certificate: X509Certificate): ECPublicKey? =
        (certificate.publicKey as? ECPublicKey)?.takeIf(::isP256)

    fun isP256(key: ECPublicKey): Boolean =
        runCatching { Curve.forECParameterSpec(key.params) }.getOrNull() == Curve.P_256
}
