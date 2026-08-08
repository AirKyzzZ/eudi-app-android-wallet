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

package eu.europa.ec.presentationfeature.interactor

import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.businesslogic.provider.UuidProvider
import eu.europa.ec.commonfeature.config.RequestUriConfig
import eu.europa.ec.commonfeature.config.toDomainConfig
import eu.europa.ec.commonfeature.interactor.ScopedPresentationInteractor
import eu.europa.ec.commonfeature.interactor.ScopedPresentationInteractorDelegate
import eu.europa.ec.commonfeature.ui.request.model.RequestDocumentItemUi
import eu.europa.ec.commonfeature.ui.request.transformer.RequestTransformer
import eu.europa.ec.corelogic.controller.TransferEventPartialState
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.controller.WalletCorePresentationController
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcFormat
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.uilogic.navigation.helper.IntentAction
import java.security.cert.X509Certificate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

sealed class PresentationRequestInteractorPartialState {
    data class Success(
        val verifierName: String?,
        val verifierIsTrusted: Boolean,
        val requestDocuments: List<RequestDocumentItemUi>,
        val veranaCertificates: List<X509Certificate> = emptyList(),
        val veranaVct: String? = null,
    ) : PresentationRequestInteractorPartialState()

    data class NoData(
        val verifierName: String?,
        val verifierIsTrusted: Boolean,
        val veranaCertificates: List<X509Certificate> = emptyList(),
    ) : PresentationRequestInteractorPartialState()

    data class Failure(val error: String) : PresentationRequestInteractorPartialState()
    data object Disconnect : PresentationRequestInteractorPartialState()
}

interface PresentationRequestInteractor : ScopedPresentationInteractor {
    fun getRequestDocuments(): Flow<PresentationRequestInteractorPartialState>
    fun stopPresentation()
    fun updateRequestedDocuments(items: List<RequestDocumentItemUi>)
    fun setConfig(config: RequestUriConfig, intentAction: IntentAction? = null)
}

class PresentationRequestInteractorImpl(
    private val resourceProvider: ResourceProvider,
    private val uuidProvider: UuidProvider,
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
    private val logController: LogController,
    walletCorePresentationController: WalletCorePresentationController? = null
) : PresentationRequestInteractor,
    ScopedPresentationInteractorDelegate(walletCorePresentationController) {

    companion object {
        private const val TAG = "PresentationRequest"
    }

    private val genericErrorMsg
        get() = resourceProvider.genericErrorMessage()

    override fun setConfig(config: RequestUriConfig, intentAction: IntentAction?) {
        setScopeId(config.presentationScopeId)
        walletCorePresentationController.setConfig(config.toDomainConfig(intentAction))
    }

    override fun getRequestDocuments(): Flow<PresentationRequestInteractorPartialState> =
        walletCorePresentationController.events.mapNotNull { response ->
            when (response) {
                is TransferEventPartialState.RequestReceived -> {
                    if (response.requestData.all { it.requestedItems.isEmpty() }) {
                        PresentationRequestInteractorPartialState.NoData(
                            verifierName = response.verifierName,
                            verifierIsTrusted = response.verifierIsTrusted,
                            veranaCertificates = response.veranaCertificates,
                        )
                    } else {
                        val documentsDomain = RequestTransformer.transformToDomainItems(
                            storageDocuments = walletCoreDocumentsController.getAllIssuedDocuments(),
                            requestDocuments = response.requestData,
                            resourceProvider = resourceProvider,
                            uuidProvider = uuidProvider
                        ).getOrThrow()
                            .filterNot {
                                walletCoreDocumentsController.isDocumentRevoked(it.docId)
                            }

                        val storageDocuments = walletCoreDocumentsController.getAllIssuedDocuments()
                        val matchedDocumentIds = documentsDomain.map { it.docId }.toSet()
                        val veranaVct = storageDocuments
                            .filter { it.id in matchedDocumentIds }
                            .firstNotNullOfOrNull { (it.format as? SdJwtVcFormat)?.vct }

                        if (documentsDomain.isNotEmpty()) {
                            PresentationRequestInteractorPartialState.Success(
                                verifierName = response.verifierName,
                                verifierIsTrusted = response.verifierIsTrusted,
                                veranaCertificates = response.veranaCertificates,
                                veranaVct = veranaVct,
                                requestDocuments = RequestTransformer.transformToUiItems(
                                    documentsDomain = documentsDomain,
                                    resourceProvider = resourceProvider,
                                )
                            )
                        } else {
                            PresentationRequestInteractorPartialState.NoData(
                                verifierName = response.verifierName,
                                verifierIsTrusted = response.verifierIsTrusted,
                                veranaCertificates = response.veranaCertificates,
                            )
                        }
                    }
                }

                is TransferEventPartialState.Error -> {
                    logController.e(TAG) { "Transfer error received in interactor: ${response.error}" }
                    PresentationRequestInteractorPartialState.Failure(error = response.error)
                }

                is TransferEventPartialState.Disconnected -> {
                    PresentationRequestInteractorPartialState.Disconnect
                }

                else -> null
            }
        }.safeAsync {
            logController.e(TAG) { "getRequestDocuments() flow exception: ${it::class.qualifiedName}: ${it.message}" }
            logController.e(TAG, it)
            PresentationRequestInteractorPartialState.Failure(
                error = it.localizedMessage ?: genericErrorMsg
            )
        }

    override fun stopPresentation() {
        walletCorePresentationController.stopPresentation()
    }

    override fun updateRequestedDocuments(items: List<RequestDocumentItemUi>) {
        val disclosedDocuments = RequestTransformer.createDisclosedDocuments(items)
        walletCorePresentationController.updateRequestedDocuments(disclosedDocuments.toMutableList())
    }
}
