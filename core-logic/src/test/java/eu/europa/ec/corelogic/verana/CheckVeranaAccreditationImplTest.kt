package eu.europa.ec.corelogic.verana

import eu.europa.ec.corelogic.model.VeranaAccreditationReason
import eu.europa.ec.corelogic.model.VeranaAccreditationStatus
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.ECOSYSTEM_DID
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.ISSUER_DID
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.UNACCREDITED_ISSUER_DID
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.VCT
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.VERIFIER_DID
import eu.europa.ec.corelogic.verana.VeranaDevnetFixtures.VTJSC_ID
import eu.europa.ec.networklogic.model.VERANA_NETWORKS
import eu.europa.ec.networklogic.model.VeranaTrustClaim
import eu.europa.ec.networklogic.model.VeranaTrustCredential
import eu.europa.ec.networklogic.model.VeranaTrustResolution
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CheckVeranaAccreditationImplTest {

    private val devnet = VERANA_NETWORKS.first()

    private val documents = FakeDocumentRepository(
        json = mapOf(VCT to VeranaDevnetFixtures.typeMetadata, VTJSC_ID to VeranaDevnetFixtures.vtjsc),
        didDocuments = mapOf(ECOSYSTEM_DID to VeranaDevnetFixtures.ecosystemDidDocument),
    )

    private val indexer = FakeIndexerRepository(
        schemaEcosystemId = { network, schemaId -> 6L.takeIf { network == devnet && schemaId == "8" } },
        ecosystemDid = { network, ecosystemId -> ECOSYSTEM_DID.takeIf { network == devnet && ecosystemId == 6L } },
        activeParticipant = { _, did, role, schemaId ->
            did == ISSUER_DID && role == VeranaTrustRole.ISSUER && schemaId == "8" ||
                    did == VERIFIER_DID && role == VeranaTrustRole.VERIFIER && schemaId == "8"
        },
    )

    private val resolveVeranaTrust = ResolveVeranaTrust { did ->
        VeranaTrustResolution(
            did = did,
            status = VeranaTrustStatus.TRUSTED,
            credentials = listOf(
                VeranaTrustCredential(
                    ecsSchema = "ServiceCredential",
                    claims = listOf(VeranaTrustClaim("name", listOf("Playground Demo"))),
                )
            ),
        )
    }

    private val useCase = CheckVeranaAccreditationImpl(
        documentRepository = documents,
        indexerRepository = indexer,
        resolveVeranaTrust = resolveVeranaTrust,
        networks = VERANA_NETWORKS,
        clock = { NOW },
    )

    @Test
    fun `grants the accredited devnet issuer through the VTJSC chain`() = runTest {
        val accreditation = useCase(ISSUER_DID, VeranaTrustRole.ISSUER, VCT)

        assertEquals(VeranaAccreditationStatus.GRANTED, accreditation.status)
        assertEquals(VeranaAccreditationReason.ACTIVE_PARTICIPANT, accreditation.reason)
        assertEquals("DemoCredential", accreditation.credentialName)
        assertEquals("Playground Demo", accreditation.ecosystemName)
        assertEquals("8", accreditation.schemaId)
        assertEquals("vna-devnet-1", accreditation.networkId)
    }

    @Test
    fun `grants the accredited devnet verifier`() = runTest {
        assertEquals(
            VeranaAccreditationStatus.GRANTED,
            useCase(VERIFIER_DID, VeranaTrustRole.VERIFIER, VCT).status,
        )
    }

    @Test
    fun `refuses the unaccredited devnet issuer`() = runTest {
        val accreditation = useCase(UNACCREDITED_ISSUER_DID, VeranaTrustRole.ISSUER, VCT)

        assertEquals(VeranaAccreditationStatus.REFUSED, accreditation.status)
        assertEquals(VeranaAccreditationReason.NO_ACTIVE_PARTICIPANT, accreditation.reason)
    }

    @Test
    fun `an issuer participant does not satisfy a verifier check`() = runTest {
        val accreditation = useCase(ISSUER_DID, VeranaTrustRole.VERIFIER, VCT)

        assertEquals(VeranaAccreditationStatus.REFUSED, accreditation.status)
        assertTrue(Triple(ISSUER_DID, VeranaTrustRole.VERIFIER, "8") in indexer.participantQueries)
    }

    @Test
    fun `refuses a VTJSC whose proof does not verify`() = runTest {
        val validUntil = "validUntil" to JsonPrimitive("2099-01-01T00:00:00.000Z")
        documents.json = documents.json + (VTJSC_ID to JsonObject(VeranaDevnetFixtures.vtjsc + validUntil))

        val accreditation = useCase(ISSUER_DID, VeranaTrustRole.ISSUER, VCT)

        assertEquals(VeranaAccreditationStatus.REFUSED, accreditation.status)
        assertEquals(VeranaAccreditationReason.SCHEMA_CREDENTIAL_INVALID, accreditation.reason)
        assertTrue(indexer.participantQueries.isEmpty())
    }

    @Test
    fun `refuses a VTJSC outside its validity window`() = runTest {
        val expired = CheckVeranaAccreditationImpl(
            documentRepository = documents,
            indexerRepository = indexer,
            resolveVeranaTrust = resolveVeranaTrust,
            networks = VERANA_NETWORKS,
            clock = { Instant.parse("2040-01-01T00:00:00Z") },
        )

        val accreditation = expired(ISSUER_DID, VeranaTrustRole.ISSUER, VCT)

        assertEquals(VeranaAccreditationReason.SCHEMA_CREDENTIAL_INVALID, accreditation.reason)
    }

    @Test
    fun `refuses a VTJSC not issued by the ecosystem that owns the schema`() = runTest {
        indexer.ecosystemDid = { _, _ -> "did:webvh:QmOther:other.example" }

        val accreditation = useCase(ISSUER_DID, VeranaTrustRole.ISSUER, VCT)

        assertEquals(VeranaAccreditationStatus.REFUSED, accreditation.status)
        assertEquals(VeranaAccreditationReason.ECOSYSTEM_MISMATCH, accreditation.reason)
    }

    @Test
    fun `refuses a schema on a network the wallet does not know`() = runTest {
        val testnetOnly = CheckVeranaAccreditationImpl(
            documentRepository = documents,
            indexerRepository = indexer,
            resolveVeranaTrust = resolveVeranaTrust,
            networks = VERANA_NETWORKS.drop(1),
            clock = { NOW },
        )

        assertEquals(
            VeranaAccreditationReason.UNKNOWN_NETWORK,
            testnetOnly(ISSUER_DID, VeranaTrustRole.ISSUER, VCT).reason,
        )
    }

    @Test
    fun `refuses a credential type that names no schema credential`() = runTest {
        documents.json = mapOf(
            VCT to JsonObject(mapOf("vct" to JsonPrimitive(VCT), "name" to JsonPrimitive("DemoCredential"))),
        )

        val withoutSchema = useCase(ISSUER_DID, VeranaTrustRole.ISSUER, VCT)
        val withoutVct = useCase(ISSUER_DID, VeranaTrustRole.ISSUER, null)

        assertEquals(VeranaAccreditationStatus.REFUSED, withoutSchema.status)
        assertEquals(VeranaAccreditationReason.NO_VERANA_SCHEMA, withoutSchema.reason)
        assertEquals("DemoCredential", withoutSchema.credentialName)
        assertEquals(VeranaAccreditationReason.NO_VERANA_SCHEMA, withoutVct.reason)
    }

    @Test
    fun `cannot decide when the indexer does not answer`() = runTest {
        indexer.activeParticipant = { _, _, _, _ -> null }

        assertEquals(VeranaAccreditationStatus.UNDETERMINED, useCase(ISSUER_DID, VeranaTrustRole.ISSUER, VCT).status)
    }

    @Test
    fun `cannot decide when the ecosystem DID does not resolve`() = runTest {
        documents.didDocuments = emptyMap()

        assertEquals(VeranaAccreditationStatus.UNDETERMINED, useCase(ISSUER_DID, VeranaTrustRole.ISSUER, VCT).status)
    }

    @Test
    fun `cannot decide when the type metadata does not load`() = runTest {
        documents.json = emptyMap()

        assertEquals(VeranaAccreditationStatus.UNDETERMINED, useCase(ISSUER_DID, VeranaTrustRole.ISSUER, VCT).status)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-02T00:00:00Z")
    }
}
