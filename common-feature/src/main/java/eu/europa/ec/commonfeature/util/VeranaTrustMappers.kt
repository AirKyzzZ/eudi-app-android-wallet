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

package eu.europa.ec.commonfeature.util

import eu.europa.ec.corelogic.model.VeranaAccreditationReason
import eu.europa.ec.corelogic.model.VeranaAccreditationStatus
import eu.europa.ec.corelogic.model.VeranaTrustEvidence
import eu.europa.ec.corelogic.model.VeranaTrustState
import eu.europa.ec.corelogic.model.canRetry
import eu.europa.ec.networklogic.model.VERANA_NETWORKS
import eu.europa.ec.networklogic.model.VeranaTrustCredential
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import eu.europa.ec.networklogic.model.VeranaUntrustedReason
import eu.europa.ec.networklogic.model.networkLabel
import eu.europa.ec.uilogic.component.VeranaAskUi
import eu.europa.ec.uilogic.component.VeranaConditionsUi
import eu.europa.ec.uilogic.component.VeranaIdentityUi
import eu.europa.ec.uilogic.component.VeranaLinkUi
import eu.europa.ec.uilogic.component.VeranaStepToneUi
import eu.europa.ec.uilogic.component.VeranaTrustCardUi
import eu.europa.ec.uilogic.component.VeranaVerdictUi
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// The card wording is shared with the other Verana wallets, so it is deliberately not localized.
fun VeranaTrustState.toCardUi(
    partyName: String?,
    credentialName: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): VeranaTrustCardUi? = when (this) {
    VeranaTrustState.NoDid -> null
    is VeranaTrustState.Resolving -> VeranaTrustCardUi(
        verdict = VeranaVerdictUi.RESOLVING,
        note = "Checking the Verana public registry…",
        did = did,
    )

    is VeranaTrustState.Evaluated -> evidence.toCardUi(partyName, credentialName, zone)
}

private fun VeranaTrustEvidence.toCardUi(
    partyName: String?,
    credentialName: String?,
    zone: ZoneId,
): VeranaTrustCardUi {
    val verdict = when (resolution.status) {
        VeranaTrustStatus.TRUSTED -> VeranaVerdictUi.TRUSTED
        VeranaTrustStatus.UNTRUSTED -> VeranaVerdictUi.UNTRUSTED
        VeranaTrustStatus.UNVERIFIED -> VeranaVerdictUi.UNVERIFIED
    }
    val credentials = resolution.credentials
    val service = EcsClaimReader.findServiceCredential(credentials)
        ?.takeIf { rowTone(verdict, it) == VeranaStepToneUi.OK }
        ?.let(EcsClaimReader::readEcsService)
    val credentialLabel = accreditation?.credentialName ?: credentialName ?: "this credential"

    return VeranaTrustCardUi(
        verdict = verdict,
        note = verdictNote(),
        did = did,
        networkLabel = (resolution.network?.let(::listOf) ?: VERANA_NETWORKS).networkLabel(),
        identities = if (credentials.isEmpty()) emptyList() else identities(verdict, credentials),
        checkedAt = resolution.evaluatedAt?.let { "Checked ${it.toDisplayTime(zone)}" },
        ask = VeranaAskUi(
            label = if (role == VeranaTrustRole.ISSUER) "OFFERS YOU" else "ASKS YOU FOR",
            credentialName = credentialLabel,
            granted = askGranted(),
            sentence = askSentence(
                party = service?.name ?: partyName ?: did.middleTruncated(),
                credentialLabel = credentialLabel,
            ),
        ),
        conditions = service
            ?.takeIf { it.terms != null || it.privacy != null || it.minimumAgeRequired != null }
            ?.let { safeService ->
                VeranaConditionsUi(
                    minimumAge = safeService.minimumAgeRequired,
                    links = listOfNotNull(
                        safeService.terms?.let { VeranaLinkUi("Terms & conditions", it.uri, it.digest != null) },
                        safeService.privacy?.let { VeranaLinkUi("Privacy policy", it.uri, it.digest != null) },
                    ),
                )
            },
        canRetry = canRetry,
    )
}

private fun identities(
    verdict: VeranaVerdictUi,
    credentials: List<VeranaTrustCredential>,
): List<VeranaIdentityUi> {
    val serviceCredential = EcsClaimReader.findServiceCredential(credentials)
    val organizationCredential = EcsClaimReader.findOrganizationCredential(credentials)
    val serviceTone = rowTone(verdict, serviceCredential)
    val organizationTone = rowTone(verdict, organizationCredential)

    val service = serviceCredential
        ?.takeIf { serviceTone == VeranaStepToneUi.OK }
        ?.let(EcsClaimReader::readEcsService)
    val organization = organizationCredential
        ?.takeIf { organizationTone == VeranaStepToneUi.OK }
        ?.let(EcsClaimReader::readEcsOrganization)

    val serviceRow = if (service != null) {
        val description = EcsClaimReader.stripLinks(service.description)
        VeranaIdentityUi(
            label = "SERVICE",
            tone = serviceTone,
            headline = service.name ?: "Not presented",
            verified = true,
            lines = listOfNotNull(
                description.text.takeIf { it.isNotEmpty() },
                description.removed.takeIf { it > 0 }?.let { removed ->
                    "$removed link${if (removed > 1) "s" else ""} removed from this description before display"
                },
            ),
        )
    } else {
        VeranaIdentityUi(
            label = "SERVICE",
            tone = serviceTone,
            headline = if (serviceCredential != null) {
                "Service claims not verified"
            } else {
                "No ECS-Service credential presented"
            },
            verified = false,
            lines = listOfNotNull(withheldCopy(serviceCredential, serviceTone)),
        )
    }

    val organizationRow = if (organization != null) {
        VeranaIdentityUi(
            label = "OPERATED BY",
            tone = organizationTone,
            headline = organization.name ?: "Not presented",
            verified = true,
            lines = listOfNotNull(organization.address),
            countryCode = organization.countryCode,
            registryId = organization.registryId,
        )
    } else {
        VeranaIdentityUi(
            label = "OPERATED BY",
            tone = organizationTone,
            headline = if (organizationCredential != null) {
                "Operator claims not verified"
            } else {
                "No ECS-Organization credential presented"
            },
            verified = false,
            lines = listOf(
                withheldCopy(organizationCredential, organizationTone)
                    ?: "Nothing verifies who operates this service"
            ),
        )
    }

    return listOf(serviceRow, organizationRow)
}

private fun rowTone(verdict: VeranaVerdictUi, credential: VeranaTrustCredential?): VeranaStepToneUi = when {
    verdict == VeranaVerdictUi.UNVERIFIED -> VeranaStepToneUi.NONE
    verdict == VeranaVerdictUi.TRUSTED && credential != null -> VeranaStepToneUi.OK
    else -> VeranaStepToneUi.BAD
}

private fun withheldCopy(credential: VeranaTrustCredential?, tone: VeranaStepToneUi): String? = when {
    tone == VeranaStepToneUi.NONE -> "Not checked."
    credential == null -> null
    else -> "Nothing in the registry vouches for this credential, so its claims are not shown."
}

private fun VeranaTrustEvidence.verdictNote(): String = when (resolution.status) {
    VeranaTrustStatus.UNVERIFIED ->
        "This counterparty could not be verified against the Verana registry. It is neither trusted nor untrusted."

    VeranaTrustStatus.TRUSTED -> "The Verana public registry trusts this service."
    VeranaTrustStatus.UNTRUSTED -> when (resolution.reason) {
        VeranaUntrustedReason.NO_DID_DOCUMENT -> "This service cannot present verifiable trust credentials."
        VeranaUntrustedReason.NOT_REGISTERED -> "The Verana public registry does not know this service."
        VeranaUntrustedReason.ECOSYSTEM_NOT_TRUSTED ->
            "Only ecosystems this wallet does not accept vouch for this service."

        VeranaUntrustedReason.DID_NOT_PROVEN -> "This service could not prove that it controls this DID."
        VeranaUntrustedReason.NOT_TRUSTED, null -> "The Verana public registry does not vouch for this service."
    }
}

private fun VeranaTrustEvidence.askGranted(): Boolean? = when (accreditation?.status) {
    VeranaAccreditationStatus.GRANTED -> true
    VeranaAccreditationStatus.REFUSED -> false
    VeranaAccreditationStatus.UNDETERMINED, null -> null
}

private fun VeranaTrustEvidence.askSentence(party: String, credentialLabel: String): String {
    val verb = when (role) {
        VeranaTrustRole.ISSUER -> "issuer"
        VeranaTrustRole.VERIFIER -> "verifier"
    }
    val inEcosystem = accreditation?.ecosystemName?.let { " in $it" }.orEmpty()
    return when {
        accreditation?.reason == VeranaAccreditationReason.NO_VERANA_SCHEMA ->
            "$credentialLabel does not name a Verana schema, so no authorization can be checked."

        askGranted() == true -> "$party is an authorized $verb of $credentialLabel$inEcosystem"
        askGranted() == false -> "$party is not an authorized $verb of $credentialLabel$inEcosystem"
        else -> "This could not be checked against the registry."
    }
}

private fun String.toDisplayTime(zone: ZoneId): String = runCatching {
    OffsetDateTime.parse(this)
        .atZoneSameInstant(zone)
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
}.getOrDefault(this)

private fun String.middleTruncated(maxLength: Int = 40): String =
    if (length <= maxLength) this else "${take(maxLength - 10)}…${takeLast(9)}"
