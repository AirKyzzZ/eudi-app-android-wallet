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

import eu.europa.ec.corelogic.controller.VeranaTrustState
import eu.europa.ec.corelogic.controller.VeranaVerdict
import eu.europa.ec.uilogic.component.VeranaTrustCardUi
import eu.europa.ec.uilogic.component.VeranaVerdictUi

fun VeranaTrustState.toCardUi(
    asksFor: Boolean = false,
    credentialName: String? = null,
): VeranaTrustCardUi = VeranaTrustCardUi(
    verdict = when (verdict) {
        VeranaVerdict.RESOLVING -> VeranaVerdictUi.RESOLVING
        VeranaVerdict.TRUSTED -> VeranaVerdictUi.TRUSTED
        VeranaVerdict.UNTRUSTED -> VeranaVerdictUi.UNTRUSTED
        VeranaVerdict.UNVERIFIED -> VeranaVerdictUi.UNVERIFIED
    },
    sentence = sentence,
    did = did,
    serviceName = serviceName,
    operatorName = operatorName,
    serviceCredentialPresented = serviceCredentialPresented,
    operatorCredentialPresented = operatorCredentialPresented,
    serviceSelfIssued = serviceSelfIssued,
    operatorSelfIssued = operatorSelfIssued,
    serviceDescription = serviceDescription,
    operatorAddress = operatorAddress,
    operatorCountryCode = operatorCountryCode,
    operatorRegistryId = operatorRegistryId,
    minimumAgeRequired = minimumAgeRequired,
    hasTerms = hasTerms,
    hasPrivacyPolicy = hasPrivacyPolicy,
    credentialName = credentialName,
    asksFor = asksFor,
    accreditationGranted = accreditation?.granted,
    accreditationReason = accreditation?.takeIf { !it.granted }?.reason,
)
