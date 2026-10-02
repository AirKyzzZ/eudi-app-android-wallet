package eu.europa.ec.networklogic.model

import kotlinx.serialization.json.JsonObject

data class VeranaNetwork(
    val id: String,
    val name: String,
    val indexerUrl: String,
    val explorerUrl: String? = null,
    val production: Boolean,
    val trustedEcsEcosystemDids: List<String>? = null,
)

val VERANA_NETWORKS = listOf(
    VeranaNetwork(
        id = "vna-devnet-1",
        name = "Devnet",
        indexerUrl = "https://idx.devnet.verana.network",
        explorerUrl = "https://explorer.devnet.verana.network",
        production = false,
    ),
    VeranaNetwork(
        id = "vna-testnet-1",
        name = "Testnet",
        indexerUrl = "https://idx.testnet.verana.network",
        explorerUrl = "https://explorer.testnet.verana.network",
        production = false,
    ),
)

fun List<VeranaNetwork>.networkLabel(): String? = filterNot { it.production }
    .joinToString(separator = " · ") { it.name.uppercase() }
    .ifEmpty { null }

enum class VeranaTrustRole {
    ISSUER,
    VERIFIER,
}

enum class VeranaTrustStatus {
    TRUSTED,
    UNTRUSTED,
    UNVERIFIED,
}

enum class VeranaUntrustedReason {
    NO_DID_DOCUMENT,
    NOT_REGISTERED,
    NOT_TRUSTED,
    ECOSYSTEM_NOT_TRUSTED,
    DID_NOT_PROVEN,
}

data class VeranaTrustClaim(
    val name: String,
    val values: List<String>,
    val safeHttpUrl: String? = null,
)

data class VeranaTrustCredential(
    val ecsSchema: String,
    val ecosystemId: Long? = null,
    val id: String? = null,
    val claims: List<VeranaTrustClaim>,
)

data class VeranaTrustResolution(
    val did: String,
    val status: VeranaTrustStatus,
    val reason: VeranaUntrustedReason? = null,
    val network: VeranaNetwork? = null,
    val evaluatedAt: String? = null,
    val expiresAt: String? = null,
    val credentials: List<VeranaTrustCredential> = emptyList(),
    val unresolvableCredentialIds: List<String> = emptyList(),
) {
    companion object {
        fun unresolved(
            did: String,
            status: VeranaTrustStatus,
            reason: VeranaUntrustedReason? = null,
            network: VeranaNetwork? = null,
        ) = VeranaTrustResolution(
            did = did,
            status = status,
            reason = reason,
            network = network,
        )
    }
}

sealed interface VeranaIndexerAnswer {
    data class Resolved(val resolution: VeranaTrustResolution) : VeranaIndexerAnswer
    data object NotRegistered : VeranaIndexerAnswer
    data object Unanswered : VeranaIndexerAnswer
}

data class VeranaVerificationMethod(
    val id: String,
    val type: String?,
    val publicKeyJwk: JsonObject?,
    val publicKeyMultibase: String?,
)

data class VeranaDidDocument(
    val id: String,
    val assertionMethods: List<VeranaVerificationMethod>,
    val authenticationMethods: List<VeranaVerificationMethod>,
)

data class VeranaSchemaRef(
    val network: VeranaNetwork,
    val schemaId: String,
) {
    companion object {
        private val SCHEMA_REF = Regex("""^vpr:verana:([^:]+):cs:(\d+)$""")

        fun parse(ref: String?, networks: List<VeranaNetwork>): VeranaSchemaRef? {
            val match = ref?.let(SCHEMA_REF::matchEntire) ?: return null
            return networks.firstOrNull { it.id == match.groupValues[1] }
                ?.let { network -> VeranaSchemaRef(network = network, schemaId = match.groupValues[2]) }
        }
    }
}
