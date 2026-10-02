package eu.europa.ec.commonfeature.util

import eu.europa.ec.networklogic.model.VeranaTrustCredential

data class EcsAssetRef(
    val uri: String,
    val digest: String? = null,
)

data class EcsService(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val description: String? = null,
    val descriptionFormat: String = "text/plain",
    val logo: EcsAssetRef? = null,
    val minimumAgeRequired: Int? = null,
    val terms: EcsAssetRef? = null,
    val privacy: EcsAssetRef? = null,
)

data class EcsOrganization(
    val id: String? = null,
    val name: String? = null,
    val logo: EcsAssetRef? = null,
    val registryId: String? = null,
    val address: String? = null,
    val countryCode: String? = null,
    val registryUri: String? = null,
    val legalJurisdiction: String? = null,
    val organizationKind: String? = null,
    val lei: String? = null,
)

data class StrippedDescription(
    val text: String,
    val removed: Int,
)

object EcsClaimReader {

    fun findServiceCredential(credentials: List<VeranaTrustCredential>?): VeranaTrustCredential? =
        credentials?.firstOrNull { it.ecsSchema in SERVICE_SCHEMAS }

    fun findOrganizationCredential(credentials: List<VeranaTrustCredential>?): VeranaTrustCredential? =
        credentials?.firstOrNull { it.ecsSchema in ORGANIZATION_SCHEMAS }

    fun readEcsService(credential: VeranaTrustCredential?): EcsService? {
        credential ?: return null

        val format = credential.claim("descriptionFormat")
        return EcsService(
            id = credential.claim("id"),
            name = credential.claim("name"),
            type = credential.claim("type"),
            description = credential.claim("description"),
            descriptionFormat = if (format == "text/markdown") "text/markdown" else "text/plain",
            logo = credential.asset("logoUri", "logoDigestSri"),
            minimumAgeRequired = credential.claim("minimumAgeRequired")?.toIntOrNull(),
            terms = credential.asset("termsAndConditionsUri", "termsAndConditionsDigestSri"),
            privacy = credential.asset("privacyPolicyUri", "privacyPolicyDigestSri"),
        )
    }

    fun readEcsOrganization(credential: VeranaTrustCredential?): EcsOrganization? {
        credential ?: return null

        return EcsOrganization(
            id = credential.claim("id"),
            name = credential.claim("name"),
            logo = credential.asset("logoUri", "logoDigestSri"),
            registryId = credential.claim("registryId"),
            address = credential.claim("address"),
            countryCode = credential.claim("countryCode")?.uppercase(),
            registryUri = credential.claim("registryUri"),
            legalJurisdiction = credential.claim("legalJurisdiction"),
            organizationKind = credential.claim("organizationKind"),
            lei = credential.claim("lei"),
        )
    }

    fun stripLinks(description: String?): StrippedDescription {
        if (description.isNullOrEmpty()) return StrippedDescription(text = "", removed = 0)

        var removed = 0
        val withoutMarkdown = MARKDOWN_LINK.replace(description) { match ->
            removed += 1
            match.groupValues[1]
        }
        val text = BARE_URL.replace(withoutMarkdown) {
            removed += 1
            ""
        }

        return StrippedDescription(
            text = text.replace(MULTI_WHITESPACE, " ").trim(),
            removed = removed,
        )
    }

    private fun VeranaTrustCredential.claim(name: String): String? =
        claims.firstOrNull { it.name == name }?.values?.singleOrNull()?.takeIf { it.isNotEmpty() }

    private fun VeranaTrustCredential.asset(uriClaim: String, digestClaim: String): EcsAssetRef? {
        val uri = claim(uriClaim) ?: return null
        return EcsAssetRef(uri = uri, digest = claim(digestClaim))
    }

    private val SERVICE_SCHEMAS = setOf("ServiceCredential")
    private val ORGANIZATION_SCHEMAS = setOf("OrganizationCredential", "PersonaCredential")
    private val MARKDOWN_LINK = Regex("""\[([^\]]*)]\(([^)]*)\)""")
    private val BARE_URL = Regex("""\bhttps?://\S+""", RegexOption.IGNORE_CASE)
    private val MULTI_WHITESPACE = Regex("""\s{2,}""")
}
