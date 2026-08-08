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

package eu.europa.ec.corelogic.di

import android.content.Context
import eu.europa.ec.authenticationlogic.repository.SupabaseAuthRepository
import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.controller.crypto.CryptoController
import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.businesslogic.provider.UuidProvider
import eu.europa.ec.corelogic.config.WalletCoreConfig
import eu.europa.ec.corelogic.config.WalletCoreConfigImpl
import eu.europa.ec.businesslogic.controller.storage.PrefsControllerV2
import eu.europa.ec.businesslogic.controller.wallet.LocalWalletCleanupController
import eu.europa.ec.businesslogic.controller.wallet.UserDocumentOwnershipController
import eu.europa.ec.corelogic.controller.LocalWalletCleanupControllerImpl
import eu.europa.ec.corelogic.controller.UserDocumentOwnershipControllerImpl
import eu.europa.ec.corelogic.controller.VeranaTrustController
import eu.europa.ec.corelogic.controller.VeranaTrustControllerImpl
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.controller.WalletCoreDocumentsControllerImpl
import eu.europa.ec.corelogic.controller.WalletCoreLogController
import eu.europa.ec.corelogic.controller.WalletCoreLogControllerImpl
import eu.europa.ec.corelogic.controller.WalletCoreTransactionLogController
import eu.europa.ec.corelogic.controller.WalletCoreTransactionLogControllerImpl
import eu.europa.ec.corelogic.provider.IssuerOpenId4VciManagerFactory
import eu.europa.ec.corelogic.provider.IssuerOpenId4VciManagerFactoryImpl
import eu.europa.ec.corelogic.provider.WalletCoreAttestationProviderFactory
import eu.europa.ec.corelogic.provider.WalletCoreAttestationProviderFactoryImpl
import eu.europa.ec.eudi.wallet.EudiWallet
import eu.europa.ec.networklogic.repository.VeranaTrustRepository
import eu.europa.ec.networklogic.repository.WalletAttestationRepository
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.storagelogic.dao.BookmarkDao
import eu.europa.ec.storagelogic.dao.FailedReIssuedDocumentDao
import eu.europa.ec.storagelogic.dao.RevokedDocumentDao
import eu.europa.ec.storagelogic.dao.TransactionLogDao
import eu.europa.ec.storagelogic.dao.UserDocumentMappingDao
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import org.koin.core.annotation.Single
import org.koin.mp.KoinPlatform

const val PRESENTATION_SCOPE_ID = "presentation_scope_id"
const val PRESENTATION_WALLET_QUALIFIER = "presentation_wallet"

@Module
@ComponentScan("eu.europa.ec.corelogic")
class LogicCoreModule

@Single
fun provideEudiWallet(
    context: Context,
    walletCoreConfig: WalletCoreConfig,
    walletCoreLogController: WalletCoreLogController,
    walletCoreTransactionLogController: WalletCoreTransactionLogController,
): EudiWallet = createEudiWallet(
    context = context,
    walletCoreConfig = walletCoreConfig,
    walletCoreLogController = walletCoreLogController,
    walletCoreTransactionLogController = walletCoreTransactionLogController,
)

@Named(PRESENTATION_WALLET_QUALIFIER)
@Scope(WalletCoreScope::class)
@Scoped
fun providePresentationEudiWallet(
    context: Context,
    walletCoreConfig: WalletCoreConfig,
    walletCoreLogController: WalletCoreLogController,
    walletCoreTransactionLogController: WalletCoreTransactionLogController,
): EudiWallet = createEudiWallet(
    context = context,
    walletCoreConfig = walletCoreConfig,
    walletCoreLogController = walletCoreLogController,
    walletCoreTransactionLogController = walletCoreTransactionLogController,
)

private fun createEudiWallet(
    context: Context,
    walletCoreConfig: WalletCoreConfig,
    walletCoreLogController: WalletCoreLogController,
    walletCoreTransactionLogController: WalletCoreTransactionLogController,
): EudiWallet = EudiWallet(
    context = context,
    config = walletCoreConfig.config,
) {
    withLogger(walletCoreLogController)
    withTransactionLogger(walletCoreTransactionLogController)

    withKtorHttpClientFactory {
        ProvideKtorHttpClient.client()
    }
}

@Single
fun provideWalletCoreConfig(
    context: Context,
    configLogic: ConfigLogic,
    httpClient: io.ktor.client.HttpClient,
): WalletCoreConfig = WalletCoreConfigImpl(context, configLogic, httpClient)

@Single
fun provideVeranaTrustController(
    veranaTrustRepository: VeranaTrustRepository
): VeranaTrustController = VeranaTrustControllerImpl(veranaTrustRepository)

@Single
fun provideWalletCoreLogController(logController: LogController): WalletCoreLogController =
    WalletCoreLogControllerImpl(logController)

@Single
fun provideWalletCoreTransactionLogController(
    transactionLogDao: TransactionLogDao,
    supabaseAuthRepository: SupabaseAuthRepository,
    uuidProvider: UuidProvider
): WalletCoreTransactionLogController = WalletCoreTransactionLogControllerImpl(
    transactionLogDao = transactionLogDao,
    supabaseAuthRepository = supabaseAuthRepository,
    uuidProvider = uuidProvider
)

@Single
fun provideWalletCoreAttestationProviderFactory(
    walletAttestationRepository: WalletAttestationRepository,
    supabaseAuthRepository: SupabaseAuthRepository,
    cryptoController: CryptoController,
): WalletCoreAttestationProviderFactory = WalletCoreAttestationProviderFactoryImpl(
    walletAttestationRepository = walletAttestationRepository,
    supabaseAuthRepository = supabaseAuthRepository,
    cryptoController = cryptoController,
)

@Single
fun provideIssuerOpenId4VciManagerFactory(
    context: Context,
    walletCoreAttestationProviderFactory: WalletCoreAttestationProviderFactory,
): IssuerOpenId4VciManagerFactory = IssuerOpenId4VciManagerFactoryImpl(
    context = context,
    walletCoreAttestationProviderFactory = walletCoreAttestationProviderFactory,
)

@Single
fun provideUserDocumentOwnershipController(
    userDocumentMappingDao: UserDocumentMappingDao,
    eudiWallet: EudiWallet,
    supabaseAuthRepository: SupabaseAuthRepository,
    prefsControllerV2: PrefsControllerV2,
    bookmarkDao: BookmarkDao,
    revokedDocumentDao: RevokedDocumentDao,
    transactionLogDao: TransactionLogDao,
    logController: LogController
): UserDocumentOwnershipController = UserDocumentOwnershipControllerImpl(
    userDocumentMappingDao = userDocumentMappingDao,
    eudiWallet = eudiWallet,
    supabaseAuthRepository = supabaseAuthRepository,
    prefsControllerV2 = prefsControllerV2,
    bookmarkDao = bookmarkDao,
    revokedDocumentDao = revokedDocumentDao,
    transactionLogDao = transactionLogDao,
    logController = logController
)

@Single
fun provideLocalWalletCleanupController(
    eudiWallet: EudiWallet,
    bookmarkDao: BookmarkDao,
    transactionLogDao: TransactionLogDao,
    revokedDocumentDao: RevokedDocumentDao,
    failedReIssuedDocumentDao: FailedReIssuedDocumentDao,
    ownershipController: UserDocumentOwnershipController,
    logController: LogController
): LocalWalletCleanupController = LocalWalletCleanupControllerImpl(
    eudiWallet,
    bookmarkDao,
    transactionLogDao,
    revokedDocumentDao,
    failedReIssuedDocumentDao,
    ownershipController,
    logController
)

@Single
fun provideWalletCoreDocumentsController(
    resourceProvider: ResourceProvider,
    eudiWallet: EudiWallet,
    walletCoreConfig: WalletCoreConfig,
    issuerOpenId4VciManagerFactory: IssuerOpenId4VciManagerFactory,
    bookmarkDao: BookmarkDao,
    transactionLogDao: TransactionLogDao,
    revokedDocumentDao: RevokedDocumentDao,
    failedReIssuedDocumentDao: FailedReIssuedDocumentDao,
    ownershipController: UserDocumentOwnershipController,
    logController: LogController
): WalletCoreDocumentsController =
    WalletCoreDocumentsControllerImpl(
        resourceProvider,
        eudiWallet,
        walletCoreConfig,
        issuerOpenId4VciManagerFactory,
        bookmarkDao,
        transactionLogDao,
        revokedDocumentDao,
        failedReIssuedDocumentDao,
        ownershipController,
        logController
    )

/**
 * Koin scope that lives for all the document presentation flow. It is manually handled from the
 * ViewModels that start and participate on the presentation process
 * */
@Scope
class WalletPresentationScope

/**
 * Koin scope that lives for the current activity-level wallet session.
 */
@Scope
class WalletCoreScope

/**
 * Get Koin scope that lives during document presentation flow
 * */
fun getOrCreatePresentationScope(
    scopeId: String = PRESENTATION_SCOPE_ID
): org.koin.core.scope.Scope =
    KoinPlatform.getKoin().getOrCreateScope<WalletPresentationScope>(scopeId)

inline fun <reified T : Any> getOrCreateKoinScope(scopeId: String): org.koin.core.scope.Scope =
    KoinPlatform.getKoin().getOrCreateScope<T>(scopeId)

fun getOrNullKoinScope(scopeId: String): org.koin.core.scope.Scope? =
    KoinPlatform.getKoin().getScopeOrNull(scopeId)
