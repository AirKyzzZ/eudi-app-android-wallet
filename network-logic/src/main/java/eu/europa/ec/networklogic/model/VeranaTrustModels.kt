package eu.europa.ec.networklogic.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class VeranaNetwork(
    val id: String,
    val name: String,
    val indexerUrl: String,
    val explorerUrl: String? = null,
    val production: Boolean,
    val trustedEcsEcosystemDids: List<String>? = null
)

@Serializable
data class VeranaEcsCredential(
    val ecsSchema: String,
    val ecosystemId: Int? = null,
    val claims: JsonObject? = null
)

@Serializable
data class VeranaTrustResolution(
    val did: String,
    val trustStatus: String, // "TRUSTED", "UNTRUSTED", "UNVERIFIED", "RESOLVING"
    val network: VeranaNetwork? = null,
    val evaluatedAt: String? = null,
    val expiresAt: String? = null,
    val ecsCredentials: List<VeranaEcsCredential> = emptyList(),
    val failedCredentialIds: List<String> = emptyList(),
    val reason: String? = null
)

enum class VeranaRole { ISSUER, VERIFIER }

data class VeranaAccreditation(
    val role: VeranaRole,
    val granted: Boolean,
    val schemaId: String? = null,
    val reason: String? = null,
    val credentialName: String? = null
)

data class SignedIssuerMetadata(
    val kid: String,
    val did: String,
    val payload: JsonObject,
    val signatureVerified: Boolean,
)
