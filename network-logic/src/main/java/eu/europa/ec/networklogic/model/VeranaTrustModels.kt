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

package eu.europa.ec.networklogic.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class VeranaTrustCredential(
    val ecsType: String? = null,
    val result: String? = null,
    val format: String? = null,
    val issuedBy: String? = null,
    val claims: JsonObject? = null,
) {
    val isValid: Boolean get() = result == "VALID"
}

@Serializable
data class VeranaTrustResolution(
    val did: String,
    val trustStatus: String,
    val production: Boolean,
    val evaluatedAt: String? = null,
    val evaluatedAtBlock: Long? = null,
    val expiresAt: String? = null,
    val credentials: List<VeranaTrustCredential>? = null,
)

@Serializable
data class VeranaPermission(
    val id: String? = null,
    val did: String? = null,
    @SerialName("schema_id") val schemaId: String? = null,
    val type: String? = null,
    @SerialName("effective_from") val effectiveFrom: String? = null,
    @SerialName("effective_until") val effectiveUntil: String? = null,
    val revoked: String? = null,
    val slashed: String? = null,
    @SerialName("vp_state") val validationState: String? = null,
)

data class VeranaSchema(
    val id: String,
    val trustRegistryId: String?,
    val title: String?,
    val vprId: String?,
)

data class VeranaAccreditation(
    val role: VeranaRole,
    val granted: Boolean,
    val schemaId: String? = null,
    val permissionId: String? = null,
    val reason: String? = null,
)

enum class VeranaRole(val vprTypeCode: Int) { ISSUER(1), VERIFIER(2) }

data class SignedIssuerMetadata(
    val kid: String,
    val did: String,
    val payload: JsonObject,
    val signatureVerified: Boolean,
)
