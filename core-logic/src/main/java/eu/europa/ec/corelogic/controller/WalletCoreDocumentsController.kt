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

package eu.europa.ec.corelogic.controller

import androidx.core.net.toUri
import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.authenticationlogic.controller.authentication.DeviceAuthenticationResult
import eu.europa.ec.authenticationlogic.model.BiometricCrypto
import eu.europa.ec.businesslogic.extension.safeAsync
import eu.europa.ec.corelogic.config.AuthboundWalletProviderConfig
import eu.europa.ec.corelogic.config.VciConfig
import eu.europa.ec.corelogic.config.WalletCoreConfig
import eu.europa.ec.corelogic.extension.documentIdentifier
import eu.europa.ec.corelogic.extension.getLocalizedDisplayName
import eu.europa.ec.corelogic.extension.parseTransactionLog
import eu.europa.ec.corelogic.extension.toCoreTransactionLog
import eu.europa.ec.corelogic.extension.toTransactionLogData
import eu.europa.ec.corelogic.model.DeferredDocumentDataDomain
import eu.europa.ec.corelogic.model.DocumentCategories
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.corelogic.model.FormatType
import eu.europa.ec.corelogic.model.ScopedDocumentDomain
import eu.europa.ec.corelogic.model.TransactionLogDataDomain
import eu.europa.ec.corelogic.model.toDocumentIdentifier
import eu.europa.ec.corelogic.provider.IssuerOpenId4VciManagerFactory
import eu.europa.ec.corelogic.provider.MissingKeyAttestationChainException
import eu.europa.ec.corelogic.provider.WuaProofUserAuthRequiredException
import eu.europa.ec.eudi.openid4vci.CredentialIssuerMetadata
import eu.europa.ec.eudi.openid4vci.MsoMdocCredential
import eu.europa.ec.eudi.openid4vci.SdJwtVcCredential
import eu.europa.ec.eudi.statium.Status
import eu.europa.ec.eudi.wallet.EudiWallet
import eu.europa.ec.eudi.wallet.document.CreateDocumentSettings
import eu.europa.ec.eudi.wallet.document.DeferredDocument
import eu.europa.ec.eudi.wallet.document.Document
import eu.europa.ec.eudi.wallet.document.DocumentExtensions.getDefaultCreateDocumentSettings
import eu.europa.ec.eudi.wallet.document.DocumentExtensions.getDefaultKeyUnlockData
import eu.europa.ec.eudi.wallet.document.DocumentId
import eu.europa.ec.eudi.wallet.document.IssuedDocument
import eu.europa.ec.eudi.wallet.document.format.MsoMdocData
import eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcClaim
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcData
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcFormat
import eu.europa.ec.eudi.wallet.issue.openid4vci.DeferredIssueResult
import eu.europa.ec.eudi.wallet.issue.openid4vci.IssueEvent
import eu.europa.ec.eudi.wallet.issue.openid4vci.Offer
import eu.europa.ec.eudi.wallet.issue.openid4vci.OfferResult
import eu.europa.ec.eudi.wallet.issue.openid4vci.OpenId4VciManager
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.businesslogic.controller.wallet.UserDocumentOwnershipController
import eu.europa.ec.storagelogic.dao.BookmarkDao
import eu.europa.ec.storagelogic.dao.FailedReIssuedDocumentDao
import eu.europa.ec.storagelogic.dao.RevokedDocumentDao
import eu.europa.ec.storagelogic.dao.TransactionLogDao
import eu.europa.ec.storagelogic.model.Bookmark
import eu.europa.ec.storagelogic.model.FailedReIssuedDocument
import eu.europa.ec.storagelogic.model.RevokedDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.joinAll
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale

enum class IssuanceMethod {
    OPENID4VCI
}

sealed class IssueDocumentsPartialState {
    data class Success(val documentIds: List<DocumentId>) : IssueDocumentsPartialState()
    data class DeferredSuccess(val deferredDocuments: Map<DocumentId, FormatType>) :
        IssueDocumentsPartialState()

    data class PartialSuccess(
        val documentIds: List<DocumentId>,
        val nonIssuedDocuments: Map<String, String>,
    ) : IssueDocumentsPartialState()

    data class Failure(val errorMessage: String) : IssueDocumentsPartialState()
    data class UserAuthRequired(
        val crypto: BiometricCrypto,
        val resultHandler: DeviceAuthenticationResult,
    ) : IssueDocumentsPartialState()

    data object UserAuthCancelled : IssueDocumentsPartialState()
}

sealed class DeleteDocumentPartialState {
    data object Success : DeleteDocumentPartialState()
    data class Failure(val errorMessage: String) : DeleteDocumentPartialState()
}

sealed class DeleteAllDocumentsPartialState {
    data object Success : DeleteAllDocumentsPartialState()
    data class Failure(val errorMessage: String) : DeleteAllDocumentsPartialState()
}

sealed class ResolveDocumentOfferPartialState {
    data class Success(val offer: Offer) : ResolveDocumentOfferPartialState()
    data class Failure(val errorMessage: String) : ResolveDocumentOfferPartialState()
}

sealed class FetchScopedDocumentsPartialState {
    data class Success(val documents: List<ScopedDocumentDomain>) :
        FetchScopedDocumentsPartialState()

    data class Failure(val errorMessage: String) : FetchScopedDocumentsPartialState()
}

sealed class IssueDeferredDocumentPartialState {
    data class Issued(
        val deferredDocumentData: DeferredDocumentDataDomain,
    ) : IssueDeferredDocumentPartialState()

    data class NotReady(
        val deferredDocumentData: DeferredDocumentDataDomain,
    ) : IssueDeferredDocumentPartialState()

    data class Failed(
        val documentId: DocumentId,
        val errorMessage: String,
    ) : IssueDeferredDocumentPartialState()

    data class Expired(
        val documentId: DocumentId,
    ) : IssueDeferredDocumentPartialState()
}

/**
 * Controller for interacting with internal local storage of Core for CRUD operations on documents
 * */
interface WalletCoreDocumentsController {

    /**
     * @return All the documents from the Database.
     * */
    suspend fun getAllDocuments(): List<Document>

    suspend fun getAllIssuedDocuments(): List<IssuedDocument>

    suspend fun getAllDocumentsByType(documentIdentifiers: List<DocumentIdentifier>): List<IssuedDocument>

    suspend fun getDocumentById(documentId: DocumentId): Document?

    suspend fun getMainPidDocument(): IssuedDocument?

    fun issueDocuments(
        issuanceMethod: IssuanceMethod,
        configIds: List<String>,
        issuerId: String,
        prioritizeDeferred: Boolean = false
    ): Flow<IssueDocumentsPartialState>

    fun issueDocumentsByOffer(
        offer: Offer,
        txCode: String? = null,
        prioritizeDeferred: Boolean = true
    ): Flow<IssueDocumentsPartialState>

    fun reIssueDocument(
        documentId: DocumentId,
        issuerId: String,
        allowAuthorizationFallback: Boolean,
        prioritizeDeferred: Boolean = false
    ): Flow<IssueDocumentsPartialState>

    fun deleteDocument(
        documentId: String,
    ): Flow<DeleteDocumentPartialState>

    fun deleteAllDocuments(): Flow<DeleteAllDocumentsPartialState>

    fun resolveDocumentOffer(offerUri: String): Flow<ResolveDocumentOfferPartialState>

    fun issueDeferredDocument(docId: DocumentId): Flow<IssueDeferredDocumentPartialState>

    fun resumeOpenId4VciWithAuthorization(uri: String)

    suspend fun getScopedDocuments(locale: Locale): FetchScopedDocumentsPartialState

    fun getAllDocumentCategories(): DocumentCategories

    suspend fun getRevokedDocumentIds(): List<String>

    suspend fun isDocumentRevoked(id: String): Boolean

    suspend fun resolveDocumentStatus(document: IssuedDocument): Result<Status>

    suspend fun getTransactionLogs(): List<TransactionLogDataDomain>

    suspend fun getTransactionLog(id: String): TransactionLogDataDomain?

    suspend fun isDocumentBookmarked(documentId: DocumentId): Boolean

    suspend fun storeBookmark(bookmarkId: DocumentId)

    suspend fun deleteBookmark(bookmarkId: DocumentId)

    suspend fun isDocumentLowOnCredentials(document: IssuedDocument, availableCredentials: Int): Boolean

    suspend fun storeRevokedDocuments(revokedDocuments: List<IssuedDocument>)

    suspend fun removeRevokedDocumentsFromStorage(ids: List<String>)

    suspend fun replaceFailedReIssuedDocumentIds(ids: List<String>)

    suspend fun getFailedReIssuedDocumentIds(): List<String>
}

class WalletCoreDocumentsControllerImpl(
    private val resourceProvider: ResourceProvider,
    private val eudiWallet: EudiWallet,
    private val walletCoreConfig: WalletCoreConfig,
    private val issuerOpenId4VciManagerFactory: IssuerOpenId4VciManagerFactory,
    private val bookmarkDao: BookmarkDao,
    private val transactionLogDao: TransactionLogDao,
    private val revokedDocumentDao: RevokedDocumentDao,
    private val failedReIssuedDocumentDao: FailedReIssuedDocumentDao,
    private val ownershipController: UserDocumentOwnershipController,
    private val logController: LogController,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : WalletCoreDocumentsController {

    companion object {
        private const val TAG = "WalletCoreDocs"
    }

    private val genericErrorMessage
        get() = resourceProvider.genericErrorMessage()

    private val documentErrorMessage
        get() = resourceProvider.getString(R.string.issuance_generic_error)

    private val openId4VciManagers: Map<VciConfig, OpenId4VciManager> by lazy {
        walletCoreConfig.issuersConfig.associateWith { vciConfig ->
            issuerOpenId4VciManagerFactory.create(eudiWallet, vciConfig)
        }
    }

    override suspend fun getAllDocuments(): List<Document> = withContext(dispatcher) {
        val ownedIds = ownershipController.getCurrentUserDocumentIds()
        eudiWallet.getDocuments { it is IssuedDocument || it is DeferredDocument }
            .filter { it.id in ownedIds }
    }

    override suspend fun getAllIssuedDocuments(): List<IssuedDocument> =
        getAllDocuments().filterIsInstance<IssuedDocument>()

    override suspend fun getScopedDocuments(locale: Locale): FetchScopedDocumentsPartialState {
        return withContext(dispatcher) {
            runCatching {

                // Fetch metadata from each issuer, gracefully handling failures
                val metadata: Map<VciConfig, CredentialIssuerMetadata> =
                    openId4VciManagers.mapNotNull { (vciConfig, manager) ->
                        manager.getIssuerMetadata()
                            .onFailure { error ->
                                logController.w(TAG) {
                                    "Failed to fetch metadata from issuer: ${vciConfig.config.issuerUrl} - ${error.message}"
                                }
                            }
                            .getOrNull()
                            ?.let { vciConfig to it }
                    }.toMap()

                val documents: List<ScopedDocumentDomain> =
                    metadata.flatMap { (vciConfig, meta) ->
                        meta.credentialConfigurationsSupported.map { (id, config) ->

                            val name: String = config.credentialMetadata.getLocalizedDisplayName(
                                userLocale = locale,
                                fallback = id.value
                            )

                            val isPid = when (config) {
                                is MsoMdocCredential -> config.docType.toDocumentIdentifier() == DocumentIdentifier.MdocPid
                                is SdJwtVcCredential -> config.type.toDocumentIdentifier() == DocumentIdentifier.SdJwtPid
                                else -> false
                            }

                            val formatType = when (config) {
                                is MsoMdocCredential -> config.docType
                                is SdJwtVcCredential -> config.type
                                else -> null
                            }

                            ScopedDocumentDomain(
                                name = name,
                                configurationId = id.value,
                                credentialIssuerId = vciConfig.config.issuerUrl,
                                credentialIssuerOrder = vciConfig.order,
                                formatType = formatType,
                                isPid = isPid
                            )
                        }
                    }

                if (documents.isNotEmpty()) {
                    FetchScopedDocumentsPartialState.Success(documents = documents)
                } else {
                    FetchScopedDocumentsPartialState.Failure(errorMessage = genericErrorMessage)
                }
            }
        }.getOrElse {
            FetchScopedDocumentsPartialState.Failure(
                errorMessage = it.localizedMessage ?: genericErrorMessage
            )
        }
    }

    override suspend fun getAllDocumentsByType(documentIdentifiers: List<DocumentIdentifier>): List<IssuedDocument> =
        getAllDocuments()
            .filterIsInstance<IssuedDocument>()
            .filter {
                when (it.format) {
                    is MsoMdocFormat -> documentIdentifiers.any { id ->
                        id.formatType == (it.format as MsoMdocFormat).docType
                    }

                    is SdJwtVcFormat -> documentIdentifiers.any { id ->
                        id.formatType == (it.format as SdJwtVcFormat).vct
                    }
                }
            }

    override suspend fun getDocumentById(documentId: DocumentId): Document? = withContext(dispatcher) {
        val isOwned = ownershipController.isDocumentOwnedByCurrentUser(documentId)
        if (!isOwned) null
        else eudiWallet.getDocumentById(documentId = documentId)
    }

    override suspend fun getMainPidDocument(): IssuedDocument? =
        getAllDocumentsByType(
            documentIdentifiers = listOf(
                DocumentIdentifier.MdocPid,
                DocumentIdentifier.SdJwtPid
            )
        ).minByOrNull { it.createdAt }

    override fun issueDocuments(
        issuanceMethod: IssuanceMethod,
        configIds: List<String>,
        issuerId: String,
        prioritizeDeferred: Boolean
    ): Flow<IssueDocumentsPartialState> = flow {
        when (issuanceMethod) {
            IssuanceMethod.OPENID4VCI -> {
                issueDocumentsWithOpenId4VCI(
                    configIds,
                    issuerId,
                    prioritizeDeferred
                ).collect { response ->
                    when (response) {
                        is IssueDocumentsPartialState.Failure -> emit(
                            IssueDocumentsPartialState.Failure(
                                errorMessage = documentErrorMessage
                            )
                        )

                        is IssueDocumentsPartialState.Success -> emit(
                            IssueDocumentsPartialState.Success(
                                response.documentIds
                            )
                        )

                        is IssueDocumentsPartialState.UserAuthRequired -> emit(
                            IssueDocumentsPartialState.UserAuthRequired(
                                crypto = response.crypto,
                                resultHandler = response.resultHandler
                            )
                        )

                        is IssueDocumentsPartialState.PartialSuccess -> emit(
                            IssueDocumentsPartialState.Success(
                                response.documentIds
                            )
                        )

                        is IssueDocumentsPartialState.DeferredSuccess -> emit(
                            IssueDocumentsPartialState.DeferredSuccess(
                                response.deferredDocuments
                            )
                        )

                        is IssueDocumentsPartialState.UserAuthCancelled -> emit(
                            IssueDocumentsPartialState.UserAuthCancelled
                        )
                    }
                }
            }
        }
    }.safeAsync {
        IssueDocumentsPartialState.Failure(errorMessage = documentErrorMessage)
    }

    override fun issueDocumentsByOffer(
        offer: Offer,
        txCode: String?,
        prioritizeDeferred: Boolean
    ): Flow<IssueDocumentsPartialState> =
        callbackFlow {
            val issuerId: String = offer
                .issuerMetadata
                .credentialIssuerIdentifier
                .toString()

            val managerEntry: Map.Entry<VciConfig, OpenId4VciManager>? =
                getVciManagerEntry(issuerId = issuerId)
            if (managerEntry == null) {
                logController.e(TAG) { "issueDocumentsByOffer: no configured manager for issuer" }
                trySendBlocking(
                    IssueDocumentsPartialState.Failure(errorMessage = documentErrorMessage)
                )
                close()
                return@callbackFlow
            }

            val manager: OpenId4VciManager = managerEntry.value
            val allowWuaProofAuthenticationRetry: Boolean =
                canRetryAfterWuaProofAuthentication(managerEntry.key)
            val startIssuance: () -> Unit = {
                manager.issueDocumentByOffer(
                    offer = offer,
                    onIssueEvent = issuanceCallback(
                        prioritizeDeferred = prioritizeDeferred,
                        allowWuaProofAuthenticationRetry = allowWuaProofAuthenticationRetry,
                        retryIssuance = { onIssueEvent ->
                            manager.issueDocumentByOffer(
                                offer = offer,
                                txCode = txCode,
                                onIssueEvent = onIssueEvent,
                            )
                        }
                    ),
                    txCode = txCode,
                )
            }
            startIssuanceAfterPlatformAuthIfNeeded(
                vciConfig = managerEntry.key,
                startIssuance = startIssuance
            )

            awaitClose()
        }.safeAsync {
            IssueDocumentsPartialState.Failure(
                errorMessage = documentErrorMessage
            )
        }

    override fun reIssueDocument(
        documentId: DocumentId,
        issuerId: String,
        allowAuthorizationFallback: Boolean,
        prioritizeDeferred: Boolean
    ): Flow<IssueDocumentsPartialState> =
        callbackFlow {
            val managerEntry: Map.Entry<VciConfig, OpenId4VciManager>? =
                getVciManagerEntry(issuerId = issuerId)
            if (managerEntry == null) {
                logController.e(TAG) { "reIssueDocument: no configured manager for issuer" }
                trySendBlocking(IssueDocumentsPartialState.Failure(errorMessage = documentErrorMessage))
                close()
                return@callbackFlow
            }
            val manager: OpenId4VciManager = managerEntry.value
            val allowWuaProofAuthenticationRetry: Boolean =
                canRetryAfterWuaProofAuthentication(managerEntry.key)
            manager.reissueDocument(
                documentId = documentId,
                allowAuthorizationFallback = allowAuthorizationFallback,
                onIssueEvent = issuanceCallback(
                    prioritizeDeferred = prioritizeDeferred,
                    allowWuaProofAuthenticationRetry = allowWuaProofAuthenticationRetry,
                    retryIssuance = { onIssueEvent ->
                        manager.reissueDocument(
                            documentId = documentId,
                            allowAuthorizationFallback = allowAuthorizationFallback,
                            onIssueEvent = onIssueEvent
                        )
                    }
                )
            )
            awaitClose()
        }.safeAsync {
            IssueDocumentsPartialState.Failure(errorMessage = documentErrorMessage)
        }

    override fun deleteDocument(documentId: String): Flow<DeleteDocumentPartialState> = flow {
        val isOwned = ownershipController.isDocumentOwnedByCurrentUser(documentId)
        if (!isOwned) {
            emit(DeleteDocumentPartialState.Failure(errorMessage = genericErrorMessage))
            return@flow
        }
        val userId = ownershipController.requireCurrentUserId()
        eudiWallet.deleteDocumentById(documentId = documentId)
            .kotlinResult
            .onSuccess {
                revokedDocumentDao.delete(documentId, userId)
                ownershipController.unbindDocument(documentId)
                emit(DeleteDocumentPartialState.Success)
            }
            .onFailure {
                emit(
                    DeleteDocumentPartialState.Failure(
                        errorMessage = it.localizedMessage
                            ?: genericErrorMessage
                    )
                )
            }
    }.safeAsync {
        DeleteDocumentPartialState.Failure(
            errorMessage = it.localizedMessage ?: genericErrorMessage
        )
    }

    override fun deleteAllDocuments(): Flow<DeleteAllDocumentsPartialState> =
        flow {

            val allDocuments = getAllDocuments()
            val mainPidDocument = getMainPidDocument()

            mainPidDocument?.let { safeMainPidDocument ->

                val restOfDocuments = allDocuments.filterNot { doc ->
                    doc.id == safeMainPidDocument.id
                }

                var restOfAllDocsDeleted = true
                var restOfAllDocsDeletedFailureReason = ""

                restOfDocuments.forEach { document ->

                    deleteDocument(
                        documentId = document.id
                    ).collect { deleteDocumentPartialState ->
                        when (deleteDocumentPartialState) {
                            is DeleteDocumentPartialState.Failure -> {
                                restOfAllDocsDeleted = false
                                restOfAllDocsDeletedFailureReason =
                                    deleteDocumentPartialState.errorMessage
                            }

                            is DeleteDocumentPartialState.Success -> {}
                        }
                    }
                }

                if (restOfAllDocsDeleted) {
                    deleteDocument(
                        documentId = safeMainPidDocument.id
                    ).collect { deleteMainPidDocumentPartialState ->
                        when (deleteMainPidDocumentPartialState) {
                            is DeleteDocumentPartialState.Failure -> emit(
                                DeleteAllDocumentsPartialState.Failure(
                                    errorMessage = deleteMainPidDocumentPartialState.errorMessage
                                )
                            )

                            is DeleteDocumentPartialState.Success -> emit(
                                DeleteAllDocumentsPartialState.Success
                            )
                        }
                    }
                } else {
                    emit(DeleteAllDocumentsPartialState.Failure(errorMessage = restOfAllDocsDeletedFailureReason))
                }
            } ?: emit(
                DeleteAllDocumentsPartialState.Failure(
                    errorMessage = genericErrorMessage
                )
            )
        }.safeAsync {
            DeleteAllDocumentsPartialState.Failure(
                errorMessage = it.localizedMessage ?: genericErrorMessage
            )
        }

    override fun resolveDocumentOffer(offerUri: String): Flow<ResolveDocumentOfferPartialState> =
        callbackFlow {
            val managerEntries: List<Map.Entry<VciConfig, OpenId4VciManager>> =
                getVciManagerEntriesForOffer(offerUri)
            if (managerEntries.isEmpty()) {
                logController.e(TAG) { "resolveDocumentOffer: no VCI managers configured" }
                trySendBlocking(
                    ResolveDocumentOfferPartialState.Failure(genericErrorMessage)
                )
                close()
                return@callbackFlow
            }
            resolveDocumentOfferWithManagers(offerUri, managerEntries)
            awaitClose()
        }.safeAsync {
            logController.e(TAG) { "resolveDocumentOffer exception: ${it.message}" }
            ResolveDocumentOfferPartialState.Failure(
                errorMessage = it.localizedMessage ?: genericErrorMessage
            )
        }

    override fun issueDeferredDocument(docId: DocumentId): Flow<IssueDeferredDocumentPartialState> =
        callbackFlow {
            val deferredDoc: DeferredDocument? = getDocumentById(docId) as? DeferredDocument
            if (deferredDoc == null) {
                trySendBlocking(
                    IssueDeferredDocumentPartialState.Failed(
                        documentId = docId,
                        errorMessage = documentErrorMessage
                    )
                )
                close()
                return@callbackFlow
            }

            val manager = deferredDoc.issuerMetadata?.credentialIssuerIdentifier
                ?.let { id ->
                    getVciManagerEntry(issuerId = id)?.value
                }
            if (manager == null) {
                logController.e(TAG) { "issueDeferredDocument: no configured manager for issuer" }
                trySendBlocking(
                    IssueDeferredDocumentPartialState.Failed(
                        documentId = docId,
                        errorMessage = documentErrorMessage
                    )
                )
                close()
                return@callbackFlow
            }

            manager.issueDeferredDocument(
                deferredDocument = deferredDoc,
                executor = null,
                onIssueResult = { deferredIssuanceResult ->
                    when (deferredIssuanceResult) {
                        is DeferredIssueResult.DocumentFailed -> {
                            trySendBlocking(
                                IssueDeferredDocumentPartialState.Failed(
                                    documentId = deferredIssuanceResult.documentId,
                                    errorMessage = deferredIssuanceResult.cause.localizedMessage
                                        ?: documentErrorMessage
                                )
                            )
                        }

                        is DeferredIssueResult.DocumentIssued -> {
                            launch {
                                val isBound: Boolean =
                                    bindDocumentToCurrentUser(deferredIssuanceResult.documentId)
                                if (!isBound) {
                                    trySendBlocking(
                                        IssueDeferredDocumentPartialState.Failed(
                                            documentId = deferredIssuanceResult.documentId,
                                            errorMessage = documentErrorMessage
                                        )
                                    )
                                    return@launch
                                }
                                trySendBlocking(
                                    IssueDeferredDocumentPartialState.Issued(
                                        DeferredDocumentDataDomain(
                                            documentId = deferredIssuanceResult.documentId,
                                            formatType = deferredIssuanceResult.docType,
                                            docName = deferredIssuanceResult.name
                                        )
                                    )
                                )
                            }
                        }

                        is DeferredIssueResult.DocumentNotReady -> {
                            trySendBlocking(
                                IssueDeferredDocumentPartialState.NotReady(
                                    DeferredDocumentDataDomain(
                                        documentId = deferredIssuanceResult.documentId,
                                        formatType = deferredIssuanceResult.docType,
                                        docName = deferredIssuanceResult.name
                                    )
                                )
                            )
                        }

                        is DeferredIssueResult.DocumentExpired -> {
                            trySendBlocking(
                                IssueDeferredDocumentPartialState.Expired(
                                    documentId = deferredIssuanceResult.documentId
                                )
                            )
                        }
                    }
                }
            )

            awaitClose()
        }.safeAsync {
            IssueDeferredDocumentPartialState.Failed(
                documentId = docId,
                errorMessage = it.localizedMessage ?: genericErrorMessage
            )
        }

    override fun resumeOpenId4VciWithAuthorization(uri: String) {
        for (manager in openId4VciManagers.values) {
            try {
                manager.resumeWithAuthorization(uri)
                break
            } catch (_: Exception) {
            }
        }
    }

    override fun getAllDocumentCategories(): DocumentCategories {
        return walletCoreConfig.documentCategories
    }

    override suspend fun getTransactionLogs(): List<TransactionLogDataDomain> =
        withContext(dispatcher) {
            val userId = ownershipController.getCurrentUserId() ?: ""
            transactionLogDao.retrieveAllForUser(userId)
                .mapNotNull { transactionLog ->
                    transactionLog
                        .toCoreTransactionLog()
                        ?.parseTransactionLog()
                        ?.toTransactionLogData(transactionLog.identifier)
                }
        }

    override suspend fun getTransactionLog(id: String): TransactionLogDataDomain? =
        withContext(dispatcher) {
            val userId = ownershipController.getCurrentUserId() ?: ""
            transactionLogDao.retrieve(id, userId)
                ?.toCoreTransactionLog()
                ?.parseTransactionLog()
                ?.toTransactionLogData(id)
        }

    override suspend fun isDocumentBookmarked(documentId: DocumentId): Boolean {
        val userId = ownershipController.requireCurrentUserId()
        return bookmarkDao.retrieve(documentId, userId) != null
    }

    override suspend fun storeBookmark(bookmarkId: DocumentId) {
        val userId = ownershipController.requireCurrentUserId()
        bookmarkDao.store(Bookmark(bookmarkId, userId))
    }

    override suspend fun deleteBookmark(bookmarkId: DocumentId) {
        val userId = ownershipController.requireCurrentUserId()
        bookmarkDao.delete(bookmarkId, userId)
    }

    override suspend fun isDocumentLowOnCredentials(
        document: IssuedDocument,
        availableCredentials: Int,
    ): Boolean {
        return document.credentialPolicy == CreateDocumentSettings.CredentialPolicy.OneTimeUse
                && availableCredentials <= 1
    }

    override suspend fun getRevokedDocumentIds(): List<String> {
        val userId = ownershipController.getCurrentUserId() ?: ""
        return revokedDocumentDao.retrieveAllForUser(userId).map { it.identifier }
    }

    override suspend fun isDocumentRevoked(id: String): Boolean {
        val userId = ownershipController.getCurrentUserId() ?: ""
        return revokedDocumentDao.retrieve(id, userId) != null
    }

    override suspend fun storeRevokedDocuments(revokedDocuments: List<IssuedDocument>) {
        val userId = ownershipController.requireCurrentUserId()
        revokedDocumentDao.storeAll(
            revokedDocuments.map { RevokedDocument(identifier = it.id, userId = userId) }
        )
    }

    override suspend fun removeRevokedDocumentsFromStorage(ids: List<String>) {
        val userId = ownershipController.requireCurrentUserId()
        ids.forEach { revokedDocumentDao.delete(it, userId) }
    }

    override suspend fun replaceFailedReIssuedDocumentIds(ids: List<String>) {
        val userId = ownershipController.requireCurrentUserId()
        failedReIssuedDocumentDao.deleteAllForUser(userId)
        if (ids.isNotEmpty()) {
            failedReIssuedDocumentDao.storeAll(
                ids.distinct().map { FailedReIssuedDocument(identifier = it, userId = userId) }
            )
        }
    }

    override suspend fun getFailedReIssuedDocumentIds(): List<String> {
        val userId: String = ownershipController.getCurrentUserId() ?: ""
        return failedReIssuedDocumentDao.retrieveAllForUser(userId).map { it.identifier }
    }

    override suspend fun resolveDocumentStatus(document: IssuedDocument): Result<Status> =
        eudiWallet.resolveStatus(document)

    private fun issueDocumentsWithOpenId4VCI(
        configIds: List<String>,
        issuerId: String,
        prioritizeDeferred: Boolean
    ): Flow<IssueDocumentsPartialState> =
        callbackFlow {

            val managerEntry: Map.Entry<VciConfig, OpenId4VciManager>? =
                getVciManagerEntry(issuerId = issuerId)
            require(managerEntry != null) { documentErrorMessage }
            val manager: OpenId4VciManager = managerEntry.value
            val allowWuaProofAuthenticationRetry: Boolean =
                canRetryAfterWuaProofAuthentication(managerEntry.key)

            val onIssueEvent: OpenId4VciManager.OnIssueEvent = issuanceCallback(
                prioritizeDeferred = prioritizeDeferred,
                allowWuaProofAuthenticationRetry = allowWuaProofAuthenticationRetry,
                retryIssuance = { onIssueEvent ->
                    manager.issueDocumentByConfigurationIdentifiers(
                        credentialConfigurationIds = configIds,
                        onIssueEvent = onIssueEvent,
                    )
                }
            )

            startIssuanceAfterPlatformAuthIfNeeded(
                vciConfig = managerEntry.key,
                startIssuance = {
                    manager.issueDocumentByConfigurationIdentifiers(
                        credentialConfigurationIds = configIds,
                        onIssueEvent = onIssueEvent,
                    )
                }
            )

            awaitClose()

        }.safeAsync {
            IssueDocumentsPartialState.Failure(
                errorMessage = documentErrorMessage
            )
        }

    private fun ProducerScope<IssueDocumentsPartialState>.startIssuanceAfterPlatformAuthIfNeeded(
        vciConfig: VciConfig,
        startIssuance: () -> Unit,
    ) {
        if (!requiresPlatformAuthenticationBeforeIssuance(vciConfig)) {
            startIssuance()
            return
        }
        val started: AtomicBoolean = AtomicBoolean(false)
        trySendBlocking(
            IssueDocumentsPartialState.UserAuthRequired(
                crypto = BiometricCrypto(null),
                resultHandler = DeviceAuthenticationResult(
                    onAuthenticationSuccess = {
                        if (started.compareAndSet(false, true)) {
                            startIssuance()
                        }
                    },
                    onAuthenticationError = {
                        trySendBlocking(IssueDocumentsPartialState.UserAuthCancelled)
                        close()
                    },
                    onAuthenticationFailure = {
                        trySendBlocking(IssueDocumentsPartialState.UserAuthCancelled)
                        close()
                    }
                )
            )
        )
    }

    private fun requiresPlatformAuthenticationBeforeIssuance(vciConfig: VciConfig): Boolean {
        return vciConfig.walletProviderConfig is AuthboundWalletProviderConfig &&
                eudiWallet.config.userAuthenticationRequired
    }

    private fun canRetryAfterWuaProofAuthentication(vciConfig: VciConfig): Boolean {
        return vciConfig.walletProviderConfig !is AuthboundWalletProviderConfig
    }

    private fun ProducerScope<IssueDocumentsPartialState>.issuanceCallback(
        prioritizeDeferred: Boolean = true,
        allowWuaProofAuthenticationRetry: Boolean,
        retryIssuance: (OpenId4VciManager.OnIssueEvent) -> Unit,
    ): OpenId4VciManager.OnIssueEvent {

        var totalDocumentsToBeIssued = 0
        val nonIssuedDocuments: MutableMap<FormatType, String> = mutableMapOf()
        val deferredDocuments: MutableMap<DocumentId, FormatType> = mutableMapOf()
        val issuedDocuments: MutableMap<DocumentId, FormatType> = mutableMapOf()
        val pendingDocumentBindings: MutableList<Job> = mutableListOf()
        val authCancelled = AtomicBoolean(false)
        val awaitingWuaAuth = AtomicBoolean(false)
        val superseded = AtomicBoolean(false)
        val bindingFailed = AtomicBoolean(false)
        lateinit var listener: OpenId4VciManager.OnIssueEvent
        listener = OpenId4VciManager.OnIssueEvent { event ->
            if (authCancelled.get() || superseded.get()) return@OnIssueEvent

            when (event) {
                is IssueEvent.DocumentFailed -> {
                    logController.e(TAG) { "Document issuance FAILED: docType=${event.docType} name=${event.name} cause=${event.cause::class.simpleName}: ${event.cause.message}" }
                    logController.e(TAG, event.cause)
                    nonIssuedDocuments[event.docType] = event.name
                }

                is IssueEvent.DocumentRequiresCreateSettings -> {
                    launch {
                        val offeredDocIdentifier = event.offeredDocument.documentIdentifier

                        val documentIssuanceRule = walletCoreConfig
                            .documentIssuanceConfig
                            .getRuleForDocument(documentIdentifier = offeredDocIdentifier)

                        event.resume(
                            eudiWallet.getDefaultCreateDocumentSettings(
                                offeredDocument = event.offeredDocument,
                                credentialPolicy = documentIssuanceRule.policy,
                                numberOfCredentials = documentIssuanceRule.numberOfCredentials,
                            )
                        )
                    }
                }

                is IssueEvent.DocumentRequiresUserAuth -> {
                    launch {
                        val keyUnlockDataMap =
                            event.keysRequireAuth.mapValues { (keyAlias, secureArea) ->
                                getDefaultKeyUnlockData(secureArea, keyAlias)
                            }

                        if (!eudiWallet.config.userAuthenticationRequired) {
                            event.resume(keyUnlockDataMap)
                        } else {
                            val keyUnlockData = keyUnlockDataMap.values.firstOrNull()
                            val cryptoObject = keyUnlockData?.getCryptoObjectForSigning()

                            trySendBlocking(
                                IssueDocumentsPartialState.UserAuthRequired(
                                    crypto = BiometricCrypto(cryptoObject),
                                    resultHandler = DeviceAuthenticationResult(
                                        onAuthenticationSuccess = { event.resume(keyUnlockDataMap) },
                                        onAuthenticationError = {
                                            authCancelled.set(true)
                                            event.cancel(null)
                                            trySendBlocking(IssueDocumentsPartialState.UserAuthCancelled)
                                            close()
                                        }
                                    )
                                )
                            )
                        }
                    }
                }

                is IssueEvent.Failure -> {
                    if (event.cause is WuaProofUserAuthRequiredException) {
                        if (!allowWuaProofAuthenticationRetry) {
                            logController.e(TAG) {
                                "WUA authentication requested during non-retryable issuance; refusing unsafe issuance retry"
                            }
                            trySendBlocking(
                                IssueDocumentsPartialState.Failure(
                                    errorMessage = documentErrorMessage
                                )
                            )
                            return@OnIssueEvent
                        }
                        if (awaitingWuaAuth.getAndSet(true)) return@OnIssueEvent
                        trySendBlocking(
                            IssueDocumentsPartialState.UserAuthRequired(
                                crypto = BiometricCrypto(null),
                                resultHandler = DeviceAuthenticationResult(
                                    onAuthenticationSuccess = {
                                        awaitingWuaAuth.set(false)
                                        if (!superseded.compareAndSet(false, true)) {
                                            return@DeviceAuthenticationResult
                                        }
                                        retryIssuance(
                                            issuanceCallback(
                                                prioritizeDeferred = prioritizeDeferred,
                                                allowWuaProofAuthenticationRetry =
                                                    allowWuaProofAuthenticationRetry,
                                                retryIssuance = retryIssuance,
                                            )
                                        )
                                    },
                                    onAuthenticationError = {
                                        authCancelled.set(true)
                                        trySendBlocking(IssueDocumentsPartialState.UserAuthCancelled)
                                        close()
                                    }
                                )
                            )
                        )
                        return@OnIssueEvent
                    }
                    if (event.cause is MissingKeyAttestationChainException) {
                        logController.w(TAG) { event.cause.message.orEmpty() }
                    }
                    logController.e(TAG) { "Issuance FAILURE event received: ${event.cause::class.simpleName}: ${event.cause.message}" }
                    logController.e(TAG, event.cause)
                    trySendBlocking(
                        IssueDocumentsPartialState.Failure(
                            errorMessage = documentErrorMessage
                        )
                    )
                }

                is IssueEvent.Finished -> {
                    launch {
                        if (awaitingWuaAuth.get()) {
                            logController.d(TAG, "Ignoring Finished event while waiting for WUA authentication")
                            return@launch
                        }
                        if (authCancelled.get()) {
                            logController.d(TAG, "Ignoring Finished event after auth cancellation")
                            return@launch
                        }
                        pendingDocumentBindings.toList().joinAll()
                        if (bindingFailed.get()) {
                            trySendBlocking(IssueDocumentsPartialState.Failure(documentErrorMessage))
                            return@launch
                        }
                        logController.d(TAG, "Issuance finished: issued=${issuedDocuments.size} deferred=${deferredDocuments.size} failed=${nonIssuedDocuments.size}")
                        if (deferredDocuments.isNotEmpty() && (prioritizeDeferred || (issuedDocuments.isEmpty()))) {
                            trySendBlocking(IssueDocumentsPartialState.DeferredSuccess(deferredDocuments))
                            return@launch
                        }
                        if (event.issuedDocuments.isEmpty()) {
                            trySendBlocking(IssueDocumentsPartialState.Failure(documentErrorMessage))
                            return@launch
                        }
                        if (event.issuedDocuments.size == totalDocumentsToBeIssued) {
                            trySendBlocking(IssueDocumentsPartialState.Success(event.issuedDocuments))
                            return@launch
                        }
                        trySendBlocking(
                            IssueDocumentsPartialState.PartialSuccess(
                                documentIds = event.issuedDocuments,
                                nonIssuedDocuments = nonIssuedDocuments
                            )
                        )
                    }
                }

                is IssueEvent.DocumentIssued -> {
                    issuedDocuments[event.documentId] = event.docType
                    logController.d(TAG, "Document issued: id=${event.documentId} docType=${event.docType}")
                    logIssuedDocumentDetails(event.document)
                    pendingDocumentBindings += launch {
                        if (!bindDocumentToCurrentUser(event.documentId)) {
                            bindingFailed.set(true)
                        }
                    }
                }

                is IssueEvent.Started -> {
                    totalDocumentsToBeIssued = event.total
                    logController.d(TAG, "Issuance started: total documents to issue = ${event.total}")
                }

                is IssueEvent.DocumentDeferred -> {
                    logController.d(TAG, "Document deferred: id=${event.documentId} docType=${event.docType}")
                    deferredDocuments[event.documentId] = event.docType
                    pendingDocumentBindings += launch {
                        if (!bindDocumentToCurrentUser(event.documentId)) {
                            bindingFailed.set(true)
                        }
                    }
                }
            }
        }

        return listener
    }

    private suspend fun bindDocumentToCurrentUser(documentId: DocumentId): Boolean {
        return runCatching {
            ownershipController.bindDocumentToCurrentUser(documentId)
        }.onFailure { e ->
            logController.e(TAG) { "Failed to bind document $documentId: ${e.message}" }
        }.isSuccess
    }

    private fun getVciManagerEntry(
        issuerId: String?
    ): Map.Entry<VciConfig, OpenId4VciManager>? {
        val configuredEntry: Map.Entry<VciConfig, OpenId4VciManager>? = issuerId?.let { id ->
            openId4VciManagers.entries.firstOrNull { (vciConfig, _) ->
                vciConfig.config.issuerUrl == id
            }
        }
        // Verana demo issuers are per-service subdomains, so no static issuerUrl matches; fall back
        // to the lowest-order manager (the public-client profile) and let the offer metadata drive.
        return configuredEntry ?: openId4VciManagers.entries.minByOrNull { it.key.order }
    }

    private fun getVciManagerEntriesForOffer(
        offerUri: String
    ): List<Map.Entry<VciConfig, OpenId4VciManager>> {
        val issuerId: String? = extractCredentialIssuerFromOfferUri(offerUri).getOrNull()
        val entries: List<Map.Entry<VciConfig, OpenId4VciManager>> =
            openId4VciManagers.entries.toList()
        val configuredEntry: Map.Entry<VciConfig, OpenId4VciManager>? = issuerId?.let { id ->
            entries.firstOrNull { (vciConfig, _) -> vciConfig.config.issuerUrl == id }
        }
        return if (configuredEntry == null) {
            entries
        } else {
            listOf(configuredEntry) + entries.filterNot { it.key.config.issuerUrl == issuerId }
        }
    }

    private fun ProducerScope<ResolveDocumentOfferPartialState>.resolveDocumentOfferWithManagers(
        offerUri: String,
        managerEntries: List<Map.Entry<VciConfig, OpenId4VciManager>>,
        index: Int = 0
    ) {
        val managerEntry: Map.Entry<VciConfig, OpenId4VciManager>? = managerEntries.getOrNull(index)
        if (managerEntry == null) {
            trySendBlocking(ResolveDocumentOfferPartialState.Failure(genericErrorMessage))
            close()
            return
        }
        managerEntry.value.resolveDocumentOffer(offerUri) { result ->
            when (result) {
                is OfferResult.Failure -> {
                    logController.e(TAG) {
                        "resolveDocumentOffer failed for issuer=${managerEntry.key.config.issuerUrl} cause=${result.cause::class.java.name} message=${result.cause.message}"
                    }
                    if (index < managerEntries.lastIndex) {
                        resolveDocumentOfferWithManagers(offerUri, managerEntries, index + 1)
                    } else {
                        trySendBlocking(
                            ResolveDocumentOfferPartialState.Failure(
                                result.cause.localizedMessage ?: genericErrorMessage
                            )
                        )
                        close()
                    }
                }
                is OfferResult.Success -> {
                    trySendBlocking(ResolveDocumentOfferPartialState.Success(result.offer))
                    close()
                }
            }
        }
    }

    /**
     * Logs metadata about an issued document and claim identifiers (without values)
     * to avoid PII leaking into logs.
     */
    private fun logIssuedDocumentDetails(document: IssuedDocument) {
        try {
            logController.d(TAG, "Issued credential: id=${document.id} name=${document.name} format=${document.format}")

            val issuerMeta = document.issuerMetadata
            if (issuerMeta != null) {
                logController.d(TAG, "  Issuer: ${issuerMeta.display?.firstOrNull()?.name ?: "(no display name)"}")
            }

            val data = document.data
            when (data) {
                is MsoMdocData -> {
                    logController.d(TAG, "  MsoMdoc docType=${data.format.docType} namespaces=${data.nameSpaces.keys} claims=${data.claims.size}")
                    data.claims.forEach { claim ->
                        logController.d(TAG, "    [${claim.nameSpace}] ${claim.identifier}")
                    }
                }
                is SdJwtVcData -> {
                    logController.d(TAG, "  SdJwtVc vct=${data.format.vct} claims=${data.claims.size}")
                    logSdJwtVcClaimIdentifiers(data.claims, depth = 0)
                }
            }
        } catch (e: Exception) {
            logController.e(TAG) { "Failed to log document details: ${e.message}" }
        }
    }

    private fun logSdJwtVcClaimIdentifiers(claims: List<SdJwtVcClaim>, depth: Int) {
        val indent = "  ".repeat(depth)
        for (claim in claims) {
            val sd = if (claim.selectivelyDisclosable) " [SD]" else ""
            if (claim.children.isEmpty()) {
                logController.d(TAG, "    $indent${claim.identifier}$sd")
            } else {
                logController.d(TAG, "    $indent${claim.identifier}$sd {")
                logSdJwtVcClaimIdentifiers(claim.children, depth + 1)
                logController.d(TAG, "    $indent}")
            }
        }
    }

    private fun extractCredentialIssuerFromOfferUri(offerUri: String): Result<String> =
        runCatching {
            val uri = offerUri.toUri()
            val credentialOffer = uri.getQueryParameter("credential_offer")
            if (!credentialOffer.isNullOrBlank()) {
                val decoded = URLDecoder.decode(credentialOffer, "UTF-8")
                val json = JSONObject(decoded)
                return@runCatching json.getString("credential_issuer")
            }
            throw IllegalArgumentException("Missing credential offer parameters")
        }
}
