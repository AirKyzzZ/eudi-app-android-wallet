package eu.europa.ec.corelogic.model

import eu.europa.ec.networklogic.model.VeranaTrustResolution
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import java.security.interfaces.ECPublicKey

enum class VeranaAccreditationStatus {
    GRANTED,
    REFUSED,
    UNDETERMINED,
}

enum class VeranaAccreditationReason {
    ACTIVE_PARTICIPANT,
    NO_ACTIVE_PARTICIPANT,
    NO_VERANA_SCHEMA,
    SCHEMA_CREDENTIAL_MALFORMED,
    SCHEMA_CREDENTIAL_INVALID,
    UNKNOWN_NETWORK,
    ECOSYSTEM_MISMATCH,
    REGISTRY_UNREACHABLE,
}

data class VeranaAccreditation(
    val status: VeranaAccreditationStatus,
    val reason: VeranaAccreditationReason,
    val credentialName: String? = null,
    val ecosystemName: String? = null,
    val schemaId: String? = null,
    val networkId: String? = null,
)

enum class VeranaDidKeyBinding {
    PROVEN,
    NOT_PROVEN,
    UNAVAILABLE,
}

data class VeranaTrustEvidence(
    val role: VeranaTrustRole,
    val did: String,
    val vct: String?,
    val resolution: VeranaTrustResolution,
    val accreditation: VeranaAccreditation?,
)

val VeranaTrustEvidence.blocksAccept: Boolean
    get() = !(resolution.status == VeranaTrustStatus.TRUSTED &&
            accreditation?.status == VeranaAccreditationStatus.GRANTED)

val VeranaTrustEvidence.canRetry: Boolean
    get() = resolution.status == VeranaTrustStatus.UNVERIFIED ||
            accreditation?.status == VeranaAccreditationStatus.UNDETERMINED

sealed interface VeranaTrustState {
    data class Resolving(val role: VeranaTrustRole, val did: String?) : VeranaTrustState
    data class Evaluated(val evidence: VeranaTrustEvidence) : VeranaTrustState
    data object NoDid : VeranaTrustState
}

val VeranaTrustState?.blocksAction: Boolean
    get() = when (this) {
        null, VeranaTrustState.NoDid -> false
        is VeranaTrustState.Resolving -> true
        is VeranaTrustState.Evaluated -> evidence.blocksAccept
    }

sealed interface VeranaIssuerIdentity {
    data object NoDid : VeranaIssuerIdentity
    data class NotProven(val did: String) : VeranaIssuerIdentity
    data class Unavailable(val did: String) : VeranaIssuerIdentity
    data class Signed(val did: String, val certificateKey: ECPublicKey) : VeranaIssuerIdentity
}
