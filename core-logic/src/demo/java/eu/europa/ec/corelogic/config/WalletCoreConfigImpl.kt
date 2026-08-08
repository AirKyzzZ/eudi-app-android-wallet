/*
 * Copyright (c) 2023 European Commission
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

package eu.europa.ec.corelogic.config

import android.content.Context
import android.os.Build
import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.corelogic.BuildConfig
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.eudi.iso18013.transfer.readerauth.ReaderTrustStore
import eu.europa.ec.eudi.wallet.EudiWalletConfig
import eu.europa.ec.eudi.wallet.document.CreateDocumentSettings.CredentialPolicy
import eu.europa.ec.eudi.wallet.issue.openid4vci.OpenId4VciManager
import eu.europa.ec.eudi.wallet.issue.openid4vci.dpop.DPopConfig
import eu.europa.ec.eudi.wallet.transfer.openId4vp.ClientIdScheme
import eu.europa.ec.eudi.wallet.transfer.openId4vp.Format
import eu.europa.ec.eudi.wallet.transfer.openId4vp.PreregisteredVerifier
import eu.europa.ec.resourceslogic.R
import java.security.cert.X509Certificate
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class WalletCoreConfigImpl(
    private val context: Context,
    private val configLogic: ConfigLogic,
    @Suppress("unused") private val httpClient: io.ktor.client.HttpClient,
) : WalletCoreConfig {

    private companion object {

        const val OPENID4VP_VERIFIER_LEGAL_NAME = "Authbound.io"
        const val OPENID4VP_VERIFIER_CLIENT_ID = "Verifier"

        /**
         * Checks if running on an emulator.
         * Duplicated from EmulatorDetector to avoid cross-module dependency.
         */
        fun isEmulator(): Boolean {
            return (Build.FINGERPRINT.startsWith("generic")
                    || Build.FINGERPRINT.startsWith("unknown")
                    || Build.MODEL.contains("google_sdk")
                    || Build.MODEL.contains("Emulator")
                    || Build.MODEL.contains("Android SDK built for x86")
                    || Build.MANUFACTURER.contains("Genymotion")
                    || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                    || Build.PRODUCT == "google_sdk"
                    || Build.HARDWARE.contains("goldfish")
                    || Build.HARDWARE.contains("ranchu"))
        }

        /**
         * Returns the appropriate localhost address.
         * - Emulator: 10.0.2.2 (Android's special alias for host machine loopback)
         * - Real device: 127.0.0.1 (requires `adb reverse tcp:PORT tcp:PORT`)
         */
        fun getLocalhostAddress(): String = if (isEmulator()) "10.0.2.2" else "127.0.0.1"
    }

    // Use local helper to get correct localhost address for physical devices vs emulators
    private val localhostAddress: String
        get() = getLocalhostAddress()

    private val authboundWalletProviderConfig: WalletProviderConfig
        get() = AuthboundWalletProviderConfig(
            baseUrl = "${configLogic.environmentConfig.getServerHost()}/v1/mobile/wallet-provider"
        )

    private val euWalletProviderConfig: WalletProviderConfig
        get() = EuReferenceWalletProviderConfig()

    private val openId4vpVerifierApiUri: String
        get() = "http://$localhostAddress:3008"

    private val openId4vpLocalVerifierApiUri: String
        get() = "http://$localhostAddress:8080"

    private var _config: EudiWalletConfig? = null

    override val config: EudiWalletConfig
        get() {
            if (_config == null) {
                _config = EudiWalletConfig {
                    configureDocumentKeyCreation(
                        userAuthenticationRequired = true,
                        userAuthenticationTimeout = 30.seconds,
                        useStrongBoxForKeys = true
                    )

                    configureOpenId4Vp {
                        withClientIdSchemes(
                            listOf(
                                ClientIdScheme.X509SanDns,
                                ClientIdScheme.X509Hash,
                                ClientIdScheme.Preregistered(listOf(
                                    PreregisteredVerifier(
                                        clientId = OPENID4VP_VERIFIER_CLIENT_ID,
                                        verifierApi = openId4vpVerifierApiUri,
                                        legalName = OPENID4VP_VERIFIER_LEGAL_NAME
                                    ),
//                                PreregisteredVerifier(
//                                    clientId = OPENID4VP_VERIFIER_CLIENT_ID,
//                                    verifierApi = openId4vpLocalVerifierApiUri,
//                                    legalName = OPENID4VP_VERIFIER_LEGAL_NAME
//                                )
                                ))
                            )
                        )
                        withSchemes(
                            listOf(
                                BuildConfig.OPENID4VP_SCHEME,
                                BuildConfig.EUDI_OPENID4VP_SCHEME,
                                BuildConfig.MDOC_OPENID4VP_SCHEME,
                                BuildConfig.HAIP_OPENID4VP_SCHEME
                            )
                        )
                        withFormats(
                            Format.MsoMdoc.ES256, Format.SdJwtVc.ES256
                        )
                    }

                    configureDCAPI {
                        withEnabled(true)
                    }

                    configureReaderTrustStore(
                        context,
                        R.raw.authbound_verifier_root_ca,
                        R.raw.pidissuerca02_cz,
                        R.raw.pidissuerca02_ee,
                        R.raw.pidissuerca02_eu,
                        R.raw.pidissuerca02_lu,
                        R.raw.pidissuerca02_nl,
                        R.raw.pidissuerca02_pt,
                        R.raw.pidissuerca02_ut,
                        R.raw.dc4eu,
                        R.raw.r45_staging,
                        R.raw.multipaz
                    )

                    // The cast signs requests under self-signed development certificates; chain
                    // trust is deliberately delegated to the Verana registry gate, which blocks
                    // the action itself.
                    configureReaderTrustStore(object : ReaderTrustStore {
                        override fun createCertificationTrustPath(
                            chain: List<X509Certificate>
                        ): List<X509Certificate> = chain

                        override fun validateCertificationTrustPath(
                            chain: List<X509Certificate>
                        ): Boolean = true
                    })
                }
            }
            return _config!!
        }

    override val issuersConfig: List<VciConfig>
        get() = listOf(
            // Fallback profile for issuers outside the EU lists, the Verana demo cast among
            // them: public client, first in the unmatched-offer walk; the Authbound issuers
            // still win their own offers by exact issuer-url match.
            VciConfig(
                walletProviderConfig = euWalletProviderConfig,
                config = OpenId4VciManager.Config.Builder()
                    .withIssuerUrl(issuerUrl = "https://playground.testnet.verana.network")
                    .withClientAuthenticationType(
                        OpenId4VciManager.ClientAuthenticationType.None(clientId = "verana-eudi-wallet")
                    )
                    .withAuthFlowRedirectionURI(BuildConfig.ISSUE_AUTHORIZATION_DEEPLINK)
                    .withParUsage(OpenId4VciManager.Config.ParUsage.IF_SUPPORTED)
                    .withDPopConfig(DPopConfig.Default)
                    .build(),
                order = 0
            ),
            VciConfig(
                walletProviderConfig = authboundWalletProviderConfig,
                config = OpenId4VciManager.Config.Builder()
                    .withIssuerUrl(issuerUrl = "https://issuer.authbound.io/api/v1/openid4vci")
                    .withClientAuthenticationType(OpenId4VciManager.ClientAuthenticationType.AttestationBased)
                    .withAuthFlowRedirectionURI(BuildConfig.ISSUE_AUTHORIZATION_DEEPLINK)
                    .withParUsage(OpenId4VciManager.Config.ParUsage.IF_SUPPORTED)
                    .withDPopConfig(DPopConfig.Default)
                    .build(),
                order = 1
            ),
            VciConfig(
                walletProviderConfig = euWalletProviderConfig,
                config = OpenId4VciManager.Config.Builder()
                    .withIssuerUrl(issuerUrl = "https://issuer.eudiw.dev")
                    .withClientAuthenticationType(OpenId4VciManager.ClientAuthenticationType.AttestationBased)
                    .withAuthFlowRedirectionURI(BuildConfig.ISSUE_AUTHORIZATION_DEEPLINK)
                    .withParUsage(OpenId4VciManager.Config.ParUsage.IF_SUPPORTED)
                    .withDPopConfig(DPopConfig.Default)
                    .build(),
                order = 2
            ),
            VciConfig(
                walletProviderConfig = euWalletProviderConfig,
                config = OpenId4VciManager.Config.Builder()
                    .withIssuerUrl(issuerUrl = "https://issuer-backend.eudiw.dev")
                    .withClientAuthenticationType(OpenId4VciManager.ClientAuthenticationType.AttestationBased)
                    .withAuthFlowRedirectionURI(BuildConfig.ISSUE_AUTHORIZATION_DEEPLINK)
                    .withParUsage(OpenId4VciManager.Config.ParUsage.IF_SUPPORTED)
                    .withDPopConfig(DPopConfig.Default)
                    .build(),
                order = 3
            ),
        )

    override val documentIssuanceConfig: DocumentIssuanceConfig
        get() = DocumentIssuanceConfig(
            defaultRule = DocumentIssuanceRule(
                policy = CredentialPolicy.RotateUse,
                numberOfCredentials = 1
            ),
            documentSpecificRules = mapOf(
                DocumentIdentifier.MdocPid to DocumentIssuanceRule(
                    policy = CredentialPolicy.OneTimeUse,
                    numberOfCredentials = 10
                ),
                DocumentIdentifier.SdJwtPid to DocumentIssuanceRule(
                    policy = CredentialPolicy.OneTimeUse,
                    numberOfCredentials = 10
                ),
            ),
            reissuanceRule = ReIssuanceRule(
                minNumberOfCredentials = 2,
                minExpirationHours = 24,
                backgroundInterval = Duration.ofMinutes(15)
            )
        )
}
