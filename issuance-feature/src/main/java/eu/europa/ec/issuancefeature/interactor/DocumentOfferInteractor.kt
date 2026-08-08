/*
 * Copyright (c) 2025 European Commission
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

package eu.europa.ec.issuancefeature.interactor

import android.content.Context
import eu.europa.ec.authenticationlogic.controller.authentication.BiometricsAvailability
import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.businesslogic.util.safeLet
import eu.europa.ec.commonfeature.config.SuccessUIConfig
import eu.europa.ec.commonfeature.di.CredentialOfferIssuanceScope
import eu.europa.ec.commonfeature.interactor.DeviceAuthenticationInteractor
import eu.europa.ec.corelogic.controller.IssueDocumentsPartialState
import eu.europa.ec.corelogic.controller.ResolveDocumentOfferPartialState
import eu.europa.ec.corelogic.controller.VeranaTrustController
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.extension.documentIdentifier
import eu.europa.ec.corelogic.extension.getIssuerLogo
import eu.europa.ec.corelogic.extension.getIssuerName
import eu.europa.ec.corelogic.extension.getName
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.eudi.openid4vci.TxCodeInputMode
import eu.europa.ec.eudi.wallet.document.DocumentId
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcFormat
import eu.europa.ec.eudi.wallet.issue.openid4vci.Offer
import eu.europa.ec.issuancefeature.ui.offer.model.DocumentOfferUi
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.resourceslogic.theme.values.ThemeColors
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.utils.PERCENTAGE_25
import eu.europa.ec.uilogic.config.ConfigNavigation
import eu.europa.ec.uilogic.navigation.CommonScreens
import eu.europa.ec.uilogic.navigation.helper.generateComposableArguments
import eu.europa.ec.uilogic.navigation.helper.generateComposableNavigationLink
import eu.europa.ec.uilogic.serializer.UiSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import java.net.URI

sealed class ResolveDocumentOfferInteractorPartialState {
    data class Success(
        val documents: List<DocumentOfferUi>,
        val issuerName: String,
        val issuerLogo: URI?,
        val txCodeLength: Int?,
        val offer: Offer,
        val veranaIssuerUrl: String? = null,
        val veranaVct: String? = null
    ) : ResolveDocumentOfferInteractorPartialState()

    data class NoDocument(
        val issuerName: String,
        val issuerLogo: URI?,
    ) : ResolveDocumentOfferInteractorPartialState()

    data class Failure(val errorMessage: String) : ResolveDocumentOfferInteractorPartialState()
}

sealed class IssueDocumentsInteractorPartialState {
    data class Success(
        val documentIds: List<DocumentId>,
    ) : IssueDocumentsInteractorPartialState()

    data class DeferredSuccess(
        val successRoute: String,
    ) : IssueDocumentsInteractorPartialState()

    data class Failure(val errorMessage: String) : IssueDocumentsInteractorPartialState()

    data class UserAuthRequired(
        val crypto: BiometricCrypto,
        val resultHandler: DeviceAuthenticationResult
    ) : IssueDocumentsInteractorPartialState()

    data object UserAuthCancelled : IssueDocumentsInteractorPartialState()

    data object IssuerNotTrusted : IssueDocumentsInteractorPartialState()
}

interface DocumentOfferInteractor {

    val credentialOffers: MutableMap<String, Offer>

    fun resolveDocumentOffer(offerUri: String): Flow<ResolveDocumentOfferInteractorPartialState>

    fun issueDocuments(
        offerUri: String,
        issuerName: String,
        navigation: ConfigNavigation,
        txCode: String? = null
    ): Flow<IssueDocumentsInteractorPartialState>

    fun issueDocumentsFromOffer(
        offer: Offer,
        issuerName: String,
        navigation: ConfigNavigation,
        txCode: String? = null
    ): Flow<IssueDocumentsInteractorPartialState>

    fun handleUserAuthentication(
        context: Context,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult
    )

    fun resumeOpenId4VciWithAuthorization(uri: String)
}

@Scope(CredentialOfferIssuanceScope::class)
@Scoped
class DocumentOfferInteractorImpl(
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
    private val deviceAuthenticationInteractor: DeviceAuthenticationInteractor,
    private val resourceProvider: ResourceProvider,
    private val uiSerializer: UiSerializer,
    private val logController: LogController,
    private val configLogic: ConfigLogic,
    private val veranaTrustController: VeranaTrustController
) : DocumentOfferInteractor {

    private val genericErrorMsg
        get() = resourceProvider.genericErrorMessage()

    override val credentialOffers: MutableMap<String, Offer> = mutableMapOf()

    override fun resolveDocumentOffer(offerUri: String): Flow<ResolveDocumentOfferInteractorPartialState> =
        flow {
            logController.d("DocumentOfferInteractor", "resolveDocumentOffer started offerUri=$offerUri")
            val userLocale = resourceProvider.getLocale()
            walletCoreDocumentsController.resolveDocumentOffer(
                offerUri = offerUri
            ).map { response ->
                logController.d("DocumentOfferInteractor", "resolveDocumentOffer response=${response::class.simpleName}")
                when (response) {
                    is ResolveDocumentOfferPartialState.Failure -> {
                        logController.e("DocumentOfferInteractor") { "resolveDocumentOffer failure: ${response.errorMessage}" }
                        ResolveDocumentOfferInteractorPartialState.Failure(errorMessage = response.errorMessage)
                    }
                    is ResolveDocumentOfferPartialState.Success -> {

                        credentialOffers[offerUri] = response.offer

                        val offerHasNoDocuments = response.offer.offeredDocuments.isEmpty()
                        if (offerHasNoDocuments) {
                            ResolveDocumentOfferInteractorPartialState.NoDocument(
                                issuerName = response.offer.getIssuerName(userLocale),
                                issuerLogo = response.offer.getIssuerLogo(userLocale),
                            )
                        } else {
                            val codeMinLength = 4
                            val codeMaxLength = 6
                            safeLet(
                                response.offer.txCodeSpec?.inputMode,
                                response.offer.txCodeSpec?.length
                            ) { inputMode, length ->
                                if ((length !in codeMinLength..codeMaxLength) || inputMode == TxCodeInputMode.TEXT) {
                                    return@map ResolveDocumentOfferInteractorPartialState.Failure(
                                        errorMessage = resourceProvider.getString(
                                            R.string.issuance_document_offer_error_invalid_txcode_format,
                                            codeMinLength,
                                            codeMaxLength
                                        )
                                    )
                                }
                            }
                            val hasMainPid =
                                walletCoreDocumentsController.getMainPidDocument() != null
                            val hasPidInOffer =
                                response.offer.offeredDocuments.any { offeredDocument ->
                                    val id = offeredDocument.documentIdentifier
                                    id == DocumentIdentifier.MdocPid || id == DocumentIdentifier.SdJwtPid
                                }

                            if (hasMainPid || hasPidInOffer || !configLogic.forcePidActivation) {
                                logController.d(
                                    "DocumentOfferInteractor",
                                    "resolveDocumentOffer parsed issuer=${response.offer.credentialOffer?.credentialIssuerIdentifier} txCode=${response.offer.txCodeSpec?.length} grants=${response.offer.credentialOffer?.grants}"
                                )
                                ResolveDocumentOfferInteractorPartialState.Success(
                                    documents = response.offer.offeredDocuments.map { offeredDocument ->
                                        DocumentOfferUi(
                                            title = offeredDocument.getName(userLocale).orEmpty(),
                                        )
                                    },
                                    issuerName = response.offer.getIssuerName(userLocale),
                                    issuerLogo = response.offer.getIssuerLogo(userLocale),
                                    txCodeLength = response.offer.txCodeSpec?.length,
                                    offer = response.offer,
                                    veranaIssuerUrl = response.offer.issuerMetadata
                                        .credentialIssuerIdentifier.value.value.toString(),
                                    veranaVct = response.offer.offeredDocuments.firstNotNullOfOrNull {
                                        (it.documentFormat as? SdJwtVcFormat)?.vct
                                    }
                                )
                            } else {
                                ResolveDocumentOfferInteractorPartialState.Failure(
                                    errorMessage = resourceProvider.getString(
                                        R.string.issuance_document_offer_error_missing_pid_text
                                    )
                                )
                            }
                        }
                    }
                }
            }.collect {
                emit(it)
            }
        }.safeAsync {
            logController.e("DocumentOfferInteractor") { "resolveDocumentOffer exception: ${it.message}" }
            ResolveDocumentOfferInteractorPartialState.Failure(
                errorMessage = it.localizedMessage ?: genericErrorMsg
            )
        }

    override fun issueDocuments(
        offerUri: String,
        issuerName: String,
        navigation: ConfigNavigation,
        txCode: String?
    ): Flow<IssueDocumentsInteractorPartialState> =
        flow {
            logController.d("DocumentOfferInteractor", "issueDocuments started offerUri=$offerUri txCodeProvided=${!txCode.isNullOrBlank()}")
            // The verdict gates the action, not just the dialog: a disabled button is not a
            // security boundary when another entry point can reach this flow.
            if (veranaTrustController.latestIssuerState.value?.allowAction == false) {
                emit(IssueDocumentsInteractorPartialState.IssuerNotTrusted)
                return@flow
            }
            credentialOffers[offerUri]?.let { offer ->
                walletCoreDocumentsController.issueDocumentsByOffer(
                    offer = offer,
                    txCode = txCode
                ).map { response ->
                    logController.d("DocumentOfferInteractor", "issueDocuments response=${response::class.simpleName}")
                    when (response) {
                        is IssueDocumentsPartialState.Failure -> {
                            logController.e("DocumentOfferInteractor") { "issueDocuments failure: ${response.errorMessage}" }
                            IssueDocumentsInteractorPartialState.Failure(errorMessage = response.errorMessage)
                        }

                        is IssueDocumentsPartialState.PartialSuccess -> {
                            IssueDocumentsInteractorPartialState.Success(
                                documentIds = response.documentIds
                            )
                        }

                        is IssueDocumentsPartialState.Success -> {
                            IssueDocumentsInteractorPartialState.Success(
                                documentIds = response.documentIds
                            )
                        }

                        is IssueDocumentsPartialState.UserAuthRequired -> {
                            IssueDocumentsInteractorPartialState.UserAuthRequired(
                                crypto = response.crypto,
                                resultHandler = response.resultHandler
                            )
                        }

                        is IssueDocumentsPartialState.DeferredSuccess -> {
                            IssueDocumentsInteractorPartialState.DeferredSuccess(
                                successRoute = buildGenericSuccessRouteForDeferred(
                                    description = resourceProvider.getString(
                                        R.string.issuance_document_offer_deferred_success_description,
                                        issuerName
                                    ),
                                    navigation = navigation
                                )
                            )
                        }

                        is IssueDocumentsPartialState.UserAuthCancelled -> {
                            IssueDocumentsInteractorPartialState.UserAuthCancelled
                        }
                    }
                }.collect {
                    emit(it)
                }
            } ?: emit(
                IssueDocumentsInteractorPartialState.Failure(
                    errorMessage = genericErrorMsg
                )
            )
        }.safeAsync {
            logController.e("DocumentOfferInteractor") { "issueDocuments exception: ${it.message}" }
            IssueDocumentsInteractorPartialState.Failure(
                errorMessage = it.localizedMessage ?: genericErrorMsg
            )
        }

    override fun issueDocumentsFromOffer(
        offer: Offer,
        issuerName: String,
        navigation: ConfigNavigation,
        txCode: String?
    ): Flow<IssueDocumentsInteractorPartialState> =
        flow {
            logController.d("DocumentOfferInteractor", "issueDocumentsFromOffer started txCodeProvided=${!txCode.isNullOrBlank()}")
            if (veranaTrustController.latestIssuerState.value?.allowAction == false) {
                emit(IssueDocumentsInteractorPartialState.IssuerNotTrusted)
                return@flow
            }
            walletCoreDocumentsController.issueDocumentsByOffer(
                offer = offer,
                txCode = txCode
            ).map { response ->
                logController.d("DocumentOfferInteractor", "issueDocumentsFromOffer response=${response::class.simpleName}")
                when (response) {
                    is IssueDocumentsPartialState.Failure -> {
                        logController.e("DocumentOfferInteractor") { "issueDocumentsFromOffer failure: ${response.errorMessage}" }
                        IssueDocumentsInteractorPartialState.Failure(errorMessage = response.errorMessage)
                    }
                    is IssueDocumentsPartialState.PartialSuccess -> {
                        IssueDocumentsInteractorPartialState.Success(
                            documentIds = response.documentIds
                        )
                    }
                    is IssueDocumentsPartialState.Success -> {
                        IssueDocumentsInteractorPartialState.Success(
                            documentIds = response.documentIds
                        )
                    }
                    is IssueDocumentsPartialState.UserAuthRequired -> {
                        IssueDocumentsInteractorPartialState.UserAuthRequired(
                            crypto = response.crypto,
                            resultHandler = response.resultHandler
                        )
                    }
                    is IssueDocumentsPartialState.DeferredSuccess -> {
                        IssueDocumentsInteractorPartialState.DeferredSuccess(
                            successRoute = buildGenericSuccessRouteForDeferred(
                                description = resourceProvider.getString(
                                    R.string.issuance_document_offer_deferred_success_description,
                                    issuerName
                                ),
                                navigation = navigation
                            )
                        )
                    }

                    is IssueDocumentsPartialState.UserAuthCancelled -> {
                        IssueDocumentsInteractorPartialState.UserAuthCancelled
                    }
                }
            }.collect {
                emit(it)
            }
        }.safeAsync {
            logController.e("DocumentOfferInteractor") { "issueDocumentsFromOffer exception: ${it.message}" }
            IssueDocumentsInteractorPartialState.Failure(
                errorMessage = it.localizedMessage ?: genericErrorMsg
            )
        }

    override fun handleUserAuthentication(
        context: Context,
        crypto: BiometricCrypto,
        notifyOnAuthenticationFailure: Boolean,
        resultHandler: DeviceAuthenticationResult
    ) {
        deviceAuthenticationInteractor.getBiometricsAvailability {
            when (it) {
                is BiometricsAvailability.CanAuthenticate -> {
                    deviceAuthenticationInteractor.authenticateWithBiometrics(
                        context = context,
                        crypto = crypto,
                        notifyOnAuthenticationFailure = notifyOnAuthenticationFailure,
                        resultHandler = resultHandler
                    )
                }

                is BiometricsAvailability.NonEnrolled -> {
                    deviceAuthenticationInteractor.launchBiometricSystemScreen()
                }

                is BiometricsAvailability.Failure -> {
                    resultHandler.onAuthenticationFailure()
                }
            }
        }
    }

    override fun resumeOpenId4VciWithAuthorization(uri: String) {
        walletCoreDocumentsController.resumeOpenId4VciWithAuthorization(uri)
    }

    private fun buildGenericSuccessRouteForDeferred(
        description: String,
        navigation: ConfigNavigation
    ): String {
        val successScreenArguments = getDeferredSuccessScreenArguments(description, navigation)
        return generateComposableNavigationLink(
            screen = CommonScreens.Success,
            arguments = successScreenArguments
        )
    }

    private fun getDeferredSuccessScreenArguments(
        description: String,
        navigation: ConfigNavigation
    ): String {
        val (textElementsConfig, imageConfig, buttonText) = Triple(
            first = SuccessUIConfig.TextElementsConfig(
                text = resourceProvider.getString(R.string.issuance_document_offer_deferred_success_text),
                description = description,
                color = ThemeColors.pending
            ),
            second = SuccessUIConfig.ImageConfig(
                type = SuccessUIConfig.ImageConfig.Type.Drawable(
                    icon = AppIcons.InProgress,
                ),
                tint = ThemeColors.primary,
                screenPercentageSize = PERCENTAGE_25,
            ),
            third = resourceProvider.getString(R.string.issuance_document_offer_deferred_success_primary_button_text)
        )

        return generateComposableArguments(
            mapOf(
                SuccessUIConfig.serializedKeyName to uiSerializer.toBase64(
                    SuccessUIConfig(
                        textElementsConfig = textElementsConfig,
                        imageConfig = imageConfig,
                        buttonConfig = listOf(
                            SuccessUIConfig.ButtonConfig(
                                text = buttonText,
                                style = SuccessUIConfig.ButtonConfig.Style.PRIMARY,
                                navigation = navigation
                            )
                        ),
                        onBackScreenToNavigate = navigation,
                    ),
                    SuccessUIConfig.Parser
                ).orEmpty()
            )
        )
    }
}
