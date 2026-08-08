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
import android.util.Log
import eu.europa.ec.businesslogic.config.E2eRuntimeConfig
import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.util.EmulatorDetector
import eu.europa.ec.corelogic.BuildConfig
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.eudi.iso18013.transfer.response.ReaderAuthPolicy
import eu.europa.ec.eudi.wallet.EudiWalletConfig
import eu.europa.ec.eudi.wallet.document.CreateDocumentSettings.CredentialPolicy
import eu.europa.ec.eudi.wallet.issue.openid4vci.OpenId4VciManager
import eu.europa.ec.eudi.wallet.issue.openid4vci.dpop.DPopConfig
import eu.europa.ec.eudi.wallet.transfer.openId4vp.ClientIdScheme
import eu.europa.ec.eudi.wallet.transfer.openId4vp.Format
import eu.europa.ec.eudi.wallet.transfer.openId4vp.PreregisteredVerifier
import eu.europa.ec.resourceslogic.R
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.time.Duration
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.time.Duration.Companion.seconds

internal class WalletCoreConfigImpl(
    private val context: Context,
    private val configLogic: ConfigLogic,
    private val httpClient: HttpClient,
) : WalletCoreConfig {

    companion object {
        private const val TAG = "WalletCoreConfig"
        private const val MOBILE_BACKEND_PORT = 3009
        private const val ISSUER_PORT = 5070
        private const val VERIFIER_PORT = 8080
        private const val OPENID4VP_VERIFIER_LEGAL_NAME = "Authbound E2E Verifier"
        private const val OPENID4VP_VERIFIER_CLIENT_ID = "Verifier"
    }

    private val localhostAddress: String = EmulatorDetector.getLocalhostAddress()

    private val isE2eMode: Boolean = E2eRuntimeConfig.isEnabled

    private val e2eIssuerBaseUrl: String
        get() = resolveConfiguredUrl(
            configuredUrl = E2eRuntimeConfig.issuerBaseUrl,
            fallbackUrl = "http://$localhostAddress:$ISSUER_PORT"
        )

    private val e2eVerifierApiUrl: String
        get() = resolveConfiguredUrl(
            configuredUrl = E2eRuntimeConfig.verifierApiUrl,
            fallbackUrl = "http://$localhostAddress:$VERIFIER_PORT"
        )
    private val trustApiClient by lazy {
        TrustApiClient(
            httpClient = httpClient,
            baseUrl = "http://$localhostAddress:$MOBILE_BACKEND_PORT/v1/mobile/trust",
        )
    }

    private val authboundWalletProviderConfig: WalletProviderConfig
        get() = AuthboundWalletProviderConfig(
            baseUrl = "${configLogic.environmentConfig.getServerHost()}/v1/mobile/wallet-provider"
        )

    private val euWalletProviderConfig: WalletProviderConfig
        get() = EuReferenceWalletProviderConfig()

    // Dynamic trust data fetched once at config init time
    private val dynamicTrustData: DynamicTrustData by lazy {
        runBlocking {
            try {
                val certs = trustApiClient.fetchTrustedCertificates()
                DynamicTrustData(certificates = certs)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch dynamic trust data: ${e.message}")
                DynamicTrustData(certificates = null)
            }
        }
    }

    private var _config: EudiWalletConfig? = null

    override val config: EudiWalletConfig
        get() {
            if (_config == null) {
                val dynamicCerts = parseDynamicCertificates(dynamicTrustData.certificates)
                val clientIdSchemes = buildList {
                    add(ClientIdScheme.X509SanDns)
                    add(ClientIdScheme.X509Hash)
                    if (isE2eMode) {
                        add(
                            ClientIdScheme.Preregistered(
                                listOf(
                                    PreregisteredVerifier(
                                        clientId = OPENID4VP_VERIFIER_CLIENT_ID,
                                        verifierApi = e2eVerifierApiUrl,
                                        legalName = OPENID4VP_VERIFIER_LEGAL_NAME
                                    )
                                )
                            )
                        )
                    }
                }

                _config = EudiWalletConfig {
                    configureDocumentKeyCreation(
                        userAuthenticationRequired = true,
                        userAuthenticationTimeout = 30.seconds,
                        useStrongBoxForKeys = true
                    )
                    configureOpenId4Vp {
                        withClientIdSchemes(clientIdSchemes)
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

                    if (dynamicCerts.isNotEmpty()) {
                        Log.i(TAG, "Adding ${dynamicCerts.size} dynamic CA certificates to trust store")
                        configureReaderTrustStore(dynamicCerts)
                    }

                    // The cast signs requests under self-signed development certificates; chain
                    // trust is deliberately delegated to the Verana registry gate, which blocks
                    // the action itself.
                    configureReaderAuthPolicy(ReaderAuthPolicy.DoNotEnforce)
                }
            }
            return _config!!
        }

    private val defaultIssuers: List<VciConfig> = listOf(
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
                .withIssuerUrl(issuerUrl = "https://staging-issuer.authbound.io/api/v1/openid4vci")
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

    private val e2eIssuers: List<VciConfig> = listOf(
        VciConfig(
            walletProviderConfig = authboundWalletProviderConfig,
            config = OpenId4VciManager.Config.Builder()
                .withIssuerUrl(issuerUrl = "${e2eIssuerBaseUrl}/api/v1/openid4vci")
                .withClientAuthenticationType(
                    OpenId4VciManager.ClientAuthenticationType.None(clientId = "wallet.authbound.io")
                )
                .withAuthFlowRedirectionURI(BuildConfig.ISSUE_AUTHORIZATION_DEEPLINK)
                .withParUsage(OpenId4VciManager.Config.ParUsage.IF_SUPPORTED)
                .withDPopConfig(DPopConfig.Default)
                .build(),
            order = 0
        )
    )

    override val issuersConfig: List<VciConfig>
        get() = if (isE2eMode) e2eIssuers else defaultIssuers

    override val documentIssuanceConfig: DocumentIssuanceConfig
        get() = DocumentIssuanceConfig(
            defaultRule = DocumentIssuanceRule(
                policy = CredentialPolicy.RotateUse,
                numberOfCredentials = 1
            ),
            documentSpecificRules = mapOf(
                DocumentIdentifier.MdocPid to DocumentIssuanceRule(
                    policy = CredentialPolicy.OneTimeUse,
                    numberOfCredentials = 60
                ),
                DocumentIdentifier.SdJwtPid to DocumentIssuanceRule(
                    policy = CredentialPolicy.OneTimeUse,
                    numberOfCredentials = 60
                ),
            ),
            reissuanceRule = ReIssuanceRule(
                minNumberOfCredentials = 2,
                minExpirationHours = 24,
                backgroundInterval = Duration.ofMinutes(15)
            )
        )
}

private fun resolveConfiguredUrl(configuredUrl: String, fallbackUrl: String): String {
    return configuredUrl.trim().removeSuffix("/").ifEmpty { fallbackUrl }
}

private fun parseDynamicCertificates(pems: List<String>?): List<X509Certificate> {
    if (pems.isNullOrEmpty()) return emptyList()

    val factory = CertificateFactory.getInstance("X.509")
    return pems.mapNotNull { pem ->
        try {
            factory.generateCertificate(
                ByteArrayInputStream(pem.toByteArray())
            ) as X509Certificate
        } catch (e: Exception) {
            Log.w("WalletCoreConfig", "Failed to parse dynamic certificate: ${e.message}")
            null
        }
    }
}

private data class DynamicTrustData(
    val certificates: List<String>?,
)
