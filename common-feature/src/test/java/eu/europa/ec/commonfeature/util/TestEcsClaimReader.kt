package eu.europa.ec.commonfeature.util

import eu.europa.ec.networklogic.model.VeranaTrustClaim
import eu.europa.ec.networklogic.model.VeranaTrustCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestEcsClaimReader {

    @Test
    fun `service claims read the v4 shape with digests`() {
        val service = EcsClaimReader.readEcsService(
            credential(
                ecsSchema = "ServiceCredential",
                claims = listOf(
                    claim("name", "Acme Service"),
                    claim("description", "A service"),
                    claim("descriptionFormat", "text/markdown"),
                    claim("minimumAgeRequired", "18"),
                    claim("logoUri", "https://acme.example/logo.png"),
                    claim("logoDigestSri", "sha256-logo"),
                    claim("termsAndConditionsUri", "https://acme.example/terms"),
                    claim("termsAndConditionsDigestSri", "sha256-terms"),
                    claim("privacyPolicyUri", "https://acme.example/privacy"),
                ),
            )
        )

        requireNotNull(service)
        assertEquals("Acme Service", service.name)
        assertEquals("text/markdown", service.descriptionFormat)
        assertEquals(18, service.minimumAgeRequired)
        assertEquals(EcsAssetRef("https://acme.example/logo.png", "sha256-logo"), service.logo)
        assertEquals(EcsAssetRef("https://acme.example/terms", "sha256-terms"), service.terms)
        assertEquals(EcsAssetRef("https://acme.example/privacy"), service.privacy)
    }

    @Test
    fun `organization claims read the v4 shape`() {
        val organization = EcsClaimReader.readEcsOrganization(
            credential(
                ecsSchema = "OrganizationCredential",
                claims = listOf(
                    claim("name", "Playground Organization (demo)"),
                    claim("countryCode", "ch"),
                    claim("registryId", "CHE-123"),
                ),
            )
        )

        requireNotNull(organization)
        assertEquals("Playground Organization (demo)", organization.name)
        assertEquals("CH", organization.countryCode)
        assertEquals("CHE-123", organization.registryId)
    }

    @Test
    fun `credentials are found by their v4 ECS schema`() {
        val service = credential(ecsSchema = "ServiceCredential")
        val persona = credential(ecsSchema = "PersonaCredential")

        assertEquals(service, EcsClaimReader.findServiceCredential(listOf(persona, service)))
        assertEquals(persona, EcsClaimReader.findOrganizationCredential(listOf(service, persona)))
        assertNull(EcsClaimReader.findServiceCredential(listOf(credential(ecsSchema = "ECS-SERVICE"))))
        assertNull(EcsClaimReader.readEcsService(null))
    }

    @Test
    fun `links are stripped from descriptions and counted`() {
        val stripped = EcsClaimReader.stripLinks(
            "See [our site](https://evil.example) and https://also-evil.example for details"
        )

        assertEquals("See our site and for details", stripped.text)
        assertEquals(2, stripped.removed)

        assertEquals(StrippedDescription("", 0), EcsClaimReader.stripLinks(null))
    }

    private fun credential(
        ecsSchema: String,
        claims: List<VeranaTrustClaim> = emptyList(),
    ) = VeranaTrustCredential(
        ecsSchema = ecsSchema,
        ecosystemId = 3L,
        claims = claims,
    )

    private fun claim(name: String, value: String) = VeranaTrustClaim(
        name = name,
        values = listOf(value),
    )
}
