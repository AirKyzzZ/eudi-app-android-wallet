package eu.europa.ec.corelogic.verana

import eu.europa.ec.networklogic.model.VeranaIndexerAnswer
import eu.europa.ec.networklogic.model.VeranaNetwork
import eu.europa.ec.networklogic.model.VeranaTrustCredential
import eu.europa.ec.networklogic.model.VeranaTrustResolution
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import eu.europa.ec.networklogic.model.VeranaUntrustedReason
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveVeranaTrustImplTest {

    private val indexer = FakeIndexerRepository()
    private val useCase = ResolveVeranaTrustImpl(indexer, listOf(TESTNET, DEVNET))

    @Test
    fun `a DID without a DID document is untrusted without asking any network`() = runTest {
        val resolution = useCase("did:key:z6MkExample")

        assertEquals(VeranaTrustStatus.UNTRUSTED, resolution.status)
        assertEquals(VeranaUntrustedReason.NO_DID_DOCUMENT, resolution.reason)
        assertTrue(indexer.resolved.isEmpty())
    }

    @Test
    fun `settles TRUSTED as soon as one network trusts the DID`() = runTest {
        indexer.resolveAnswer = { network, _ ->
            if (network == TESTNET) awaitCancellation() else resolved(trusted = true, network = DEVNET)
        }

        val resolution = useCase(DID)

        assertEquals(VeranaTrustStatus.TRUSTED, resolution.status)
        assertEquals(DEVNET, resolution.network)
    }

    @Test
    fun `is UNTRUSTED when every network answers and none trusts`() = runTest {
        indexer.resolveAnswer = { network, _ ->
            if (network == TESTNET) VeranaIndexerAnswer.NotRegistered else resolved(trusted = false, network = DEVNET)
        }

        val resolution = useCase(DID)

        assertEquals(VeranaTrustStatus.UNTRUSTED, resolution.status)
        assertEquals(VeranaUntrustedReason.NOT_TRUSTED, resolution.reason)
        assertEquals(DEVNET, resolution.network)
    }

    @Test
    fun `is UNTRUSTED for a DID no network knows`() = runTest {
        indexer.resolveAnswer = { _, _ -> VeranaIndexerAnswer.NotRegistered }

        val resolution = useCase(DID)

        assertEquals(VeranaTrustStatus.UNTRUSTED, resolution.status)
        assertEquals(VeranaUntrustedReason.NOT_REGISTERED, resolution.reason)
    }

    @Test
    fun `is UNVERIFIED when a network did not answer and none trusts`() = runTest {
        indexer.resolveAnswer = { network, _ ->
            if (network == TESTNET) VeranaIndexerAnswer.Unanswered else VeranaIndexerAnswer.NotRegistered
        }

        assertEquals(VeranaTrustStatus.UNVERIFIED, useCase(DID).status)
    }

    @Test
    fun `is UNVERIFIED without any network`() = runTest {
        assertEquals(VeranaTrustStatus.UNVERIFIED, ResolveVeranaTrustImpl(indexer, emptyList())(DID).status)
    }

    @Test
    fun `holds ECS credentials to the trusted ecosystems of the network`() = runTest {
        val restricted = DEVNET.copy(trustedEcsEcosystemDids = listOf(TRUSTED_ECOSYSTEM))
        val useCase = ResolveVeranaTrustImpl(indexer, listOf(restricted))
        indexer.resolveAnswer = { _, _ -> resolved(trusted = true, network = restricted) }

        indexer.ecosystemDid = { _, _ -> TRUSTED_ECOSYSTEM }
        assertEquals(VeranaTrustStatus.TRUSTED, useCase(DID).status)

        indexer.ecosystemDid = { _, _ -> "did:webvh:QmRogue:rogue.example" }
        val rogue = useCase(DID)
        assertEquals(VeranaTrustStatus.UNTRUSTED, rogue.status)
        assertEquals(VeranaUntrustedReason.ECOSYSTEM_NOT_TRUSTED, rogue.reason)

        indexer.ecosystemDid = { _, _ -> null }
        assertEquals(VeranaTrustStatus.UNVERIFIED, useCase(DID).status)
    }

    @Test
    fun `a network without an allowlist trusts ECS credentials from any ecosystem`() = runTest {
        indexer.resolveAnswer = { _, _ -> resolved(trusted = true, network = DEVNET) }
        indexer.ecosystemDid = { _, _ -> error("no ecosystem lookup without an allowlist") }

        assertEquals(VeranaTrustStatus.TRUSTED, ResolveVeranaTrustImpl(indexer, listOf(DEVNET))(DID).status)
    }

    private fun resolved(trusted: Boolean, network: VeranaNetwork) = VeranaIndexerAnswer.Resolved(
        VeranaTrustResolution(
            did = DID,
            status = if (trusted) VeranaTrustStatus.TRUSTED else VeranaTrustStatus.UNTRUSTED,
            reason = if (trusted) null else VeranaUntrustedReason.NOT_TRUSTED,
            network = network,
            credentials = listOf(
                VeranaTrustCredential(ecsSchema = "ServiceCredential", ecosystemId = ECOSYSTEM_ID, claims = emptyList()),
            ),
        )
    )

    private companion object {
        const val DID = "did:webvh:QmService:service.example"
        const val TRUSTED_ECOSYSTEM = "did:webvh:QmEcs:ecs.example"
        const val ECOSYSTEM_ID = 3L
        val DEVNET = VeranaNetwork(
            id = "vna-devnet-1",
            name = "Devnet",
            indexerUrl = "https://idx.devnet.example",
            production = false,
        )
        val TESTNET = VeranaNetwork(
            id = "vna-testnet-1",
            name = "Testnet",
            indexerUrl = "https://idx.testnet.example",
            production = false,
        )
    }
}
