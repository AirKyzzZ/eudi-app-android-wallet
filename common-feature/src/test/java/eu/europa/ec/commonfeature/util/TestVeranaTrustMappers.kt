package eu.europa.ec.commonfeature.util

import eu.europa.ec.corelogic.model.VeranaAccreditation
import eu.europa.ec.corelogic.model.VeranaAccreditationReason
import eu.europa.ec.corelogic.model.VeranaAccreditationStatus
import eu.europa.ec.corelogic.model.VeranaTrustEvidence
import eu.europa.ec.corelogic.model.VeranaTrustState
import eu.europa.ec.networklogic.model.VERANA_NETWORKS
import eu.europa.ec.networklogic.model.VeranaTrustClaim
import eu.europa.ec.networklogic.model.VeranaTrustCredential
import eu.europa.ec.networklogic.model.VeranaTrustResolution
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import eu.europa.ec.networklogic.model.VeranaUntrustedReason
import eu.europa.ec.uilogic.component.VeranaStepToneUi
import eu.europa.ec.uilogic.component.VeranaVerdictUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TestVeranaTrustMappers {

    @Test
    fun `a counterparty without a DID shows no card`() {
        assertNull(VeranaTrustState.NoDid.toCardUi(partyName = "Issuer", credentialName = "PID"))
    }

    @Test
    fun `resolving shows the DID being checked`() {
        val card = requireNotNull(
            VeranaTrustState.Resolving(VeranaTrustRole.ISSUER, DID).toCardUi(partyName = null, credentialName = null)
        )

        assertEquals(VeranaVerdictUi.RESOLVING, card.verdict)
        assertEquals(DID, card.did)
        assertEquals("Checking the Verana public registry…", card.note)
        assertNull(card.ask)
    }

    @Test
    fun `a trusted and authorized issuer shows its DID, network, identity and Q2 sentence`() {
        val card = requireNotNull(
            evaluated(
                role = VeranaTrustRole.ISSUER,
                status = VeranaTrustStatus.TRUSTED,
                accreditation = granted(),
            ).toCardUi(partyName = "Display name", credentialName = "Offered title", zone = ZoneOffset.UTC)
        )

        assertEquals(VeranaVerdictUi.TRUSTED, card.verdict)
        assertEquals(DID, card.did)
        assertEquals("DEVNET", card.networkLabel)
        assertEquals(listOf("SERVICE", "OPERATED BY"), card.identities.map { it.label })
        assertEquals("Accredited Issuer (demo)", card.identities[0].headline)
        assertEquals("Playground Organization (demo)", card.identities[1].headline)
        assertEquals("CH", card.identities[1].countryCode)
        assertTrue(card.identities.all { it.tone == VeranaStepToneUi.OK && it.verified })
        assertTrue(card.checkedAt!!.startsWith("Checked "))
        assertEquals("OFFERS YOU", card.ask?.label)
        assertEquals("DemoCredential", card.ask?.credentialName)
        assertEquals(true, card.ask?.granted)
        assertEquals(
            "Accredited Issuer (demo) is an authorized issuer of DemoCredential in Playground Demo",
            card.ask?.sentence,
        )
        assertFalse(card.canRetry)
    }

    @Test
    fun `an unaccredited verifier reads as not authorized`() {
        val card = requireNotNull(
            evaluated(
                role = VeranaTrustRole.VERIFIER,
                status = VeranaTrustStatus.TRUSTED,
                accreditation = granted().copy(
                    status = VeranaAccreditationStatus.REFUSED,
                    reason = VeranaAccreditationReason.NO_ACTIVE_PARTICIPANT,
                ),
            ).toCardUi(partyName = null, credentialName = null)
        )

        assertEquals("ASKS YOU FOR", card.ask?.label)
        assertEquals(false, card.ask?.granted)
        assertEquals(
            "Accredited Issuer (demo) is not an authorized verifier of DemoCredential in Playground Demo",
            card.ask?.sentence,
        )
    }

    @Test
    fun `an untrusted counterparty withholds its claims`() {
        val card = requireNotNull(
            evaluated(
                role = VeranaTrustRole.ISSUER,
                status = VeranaTrustStatus.UNTRUSTED,
                reason = VeranaUntrustedReason.NOT_TRUSTED,
                accreditation = granted(),
            ).toCardUi(partyName = "Display name", credentialName = null)
        )

        assertEquals(VeranaVerdictUi.UNTRUSTED, card.verdict)
        assertEquals("Service claims not verified", card.identities[0].headline)
        assertTrue(card.identities.all { it.tone == VeranaStepToneUi.BAD && !it.verified })
        assertNull(card.conditions)
        assertEquals(
            "Display name is an authorized issuer of DemoCredential in Playground Demo",
            card.ask?.sentence,
        )
    }

    @Test
    fun `a DID the certificate does not prove says so`() {
        val card = requireNotNull(
            evaluated(
                role = VeranaTrustRole.VERIFIER,
                status = VeranaTrustStatus.UNTRUSTED,
                reason = VeranaUntrustedReason.DID_NOT_PROVEN,
                accreditation = null,
                credentials = emptyList(),
            ).toCardUi(partyName = "Verifier", credentialName = "PID")
        )

        assertEquals("This service could not prove that it controls this DID.", card.note)
        assertTrue(card.identities.isEmpty())
        assertEquals("This could not be checked against the registry.", card.ask?.sentence)
        assertFalse(card.canRetry)
    }

    @Test
    fun `an unverified counterparty can be retried and labels every network`() {
        val card = requireNotNull(
            evaluated(
                role = VeranaTrustRole.ISSUER,
                status = VeranaTrustStatus.UNVERIFIED,
                accreditation = null,
                credentials = emptyList(),
                withNetwork = false,
            ).toCardUi(partyName = null, credentialName = null)
        )

        assertEquals(VeranaVerdictUi.UNVERIFIED, card.verdict)
        assertEquals("COULD NOT VERIFY", card.verdict.label)
        assertEquals("DEVNET · TESTNET", card.networkLabel)
        assertTrue(card.canRetry)
    }

    @Test
    fun `an undetermined accreditation can be retried`() {
        val card = requireNotNull(
            evaluated(
                role = VeranaTrustRole.ISSUER,
                status = VeranaTrustStatus.TRUSTED,
                accreditation = VeranaAccreditation(
                    status = VeranaAccreditationStatus.UNDETERMINED,
                    reason = VeranaAccreditationReason.REGISTRY_UNREACHABLE,
                ),
            ).toCardUi(partyName = null, credentialName = "Offered title")
        )

        assertNull(card.ask?.granted)
        assertEquals("Offered title", card.ask?.credentialName)
        assertTrue(card.canRetry)
    }

    @Test
    fun `a credential type without a Verana schema is not called an authorization`() {
        val card = requireNotNull(
            evaluated(
                role = VeranaTrustRole.VERIFIER,
                status = VeranaTrustStatus.TRUSTED,
                accreditation = VeranaAccreditation(
                    status = VeranaAccreditationStatus.REFUSED,
                    reason = VeranaAccreditationReason.NO_VERANA_SCHEMA,
                ),
            ).toCardUi(partyName = null, credentialName = null)
        )

        assertEquals(
            "this credential does not name a Verana schema, so no authorization can be checked.",
            card.ask?.sentence,
        )
    }

    private fun evaluated(
        role: VeranaTrustRole,
        status: VeranaTrustStatus,
        accreditation: VeranaAccreditation?,
        reason: VeranaUntrustedReason? = null,
        credentials: List<VeranaTrustCredential> = ECS_CREDENTIALS,
        withNetwork: Boolean = true,
    ) = VeranaTrustState.Evaluated(
        VeranaTrustEvidence(
            role = role,
            did = DID,
            vct = "https://playground-demo.playground.devnet.verana.network/vt/vct/8",
            resolution = VeranaTrustResolution(
                did = DID,
                status = status,
                reason = reason,
                network = VERANA_NETWORKS.first().takeIf { withNetwork },
                evaluatedAt = "2026-10-01T15:56:54.684Z",
                credentials = credentials,
            ),
            accreditation = accreditation,
        )
    )

    private fun granted() = VeranaAccreditation(
        status = VeranaAccreditationStatus.GRANTED,
        reason = VeranaAccreditationReason.ACTIVE_PARTICIPANT,
        credentialName = "DemoCredential",
        ecosystemName = "Playground Demo",
        schemaId = "8",
        networkId = "vna-devnet-1",
    )

    private companion object {
        const val DID =
            "did:webvh:Qmaup6XHn4UMTEUu9PWMEwcRAYmZ74SpPpUGYpnXj9dTPf:demo-issuer-accredited.playground.devnet.verana.network"
        val ECS_CREDENTIALS = listOf(
            VeranaTrustCredential(
                ecsSchema = "ServiceCredential",
                ecosystemId = 3L,
                claims = listOf(
                    VeranaTrustClaim("name", listOf("Accredited Issuer (demo)")),
                    VeranaTrustClaim("minimumAgeRequired", listOf("0")),
                ),
            ),
            VeranaTrustCredential(
                ecsSchema = "OrganizationCredential",
                ecosystemId = 3L,
                claims = listOf(
                    VeranaTrustClaim("name", listOf("Playground Organization (demo)")),
                    VeranaTrustClaim("countryCode", listOf("ch")),
                ),
            ),
        )
    }
}
