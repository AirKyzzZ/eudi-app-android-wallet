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

package eu.europa.ec.networklogic.di

import eu.europa.ec.businesslogic.config.AppBuildType
import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.networklogic.api.ApiClient
import eu.europa.ec.networklogic.api.KtorApiClient
import eu.europa.ec.networklogic.repository.VeranaTrustRepository
import eu.europa.ec.networklogic.repository.VeranaTrustRepositoryImpl
import eu.europa.ec.networklogic.repository.WalletAttestationRepository
import eu.europa.ec.networklogic.repository.WalletAttestationRepositoryImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@ComponentScan("eu.europa.ec.networklogic")
class LogicNetworkModule

// ============================================================================
// JSON Configuration
// ============================================================================

@Single
fun provideJson(): Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    isLenient = true
}

// ============================================================================
// Ktor HttpClient (unified client for all HTTP operations)
// ============================================================================

@Single
fun provideHttpClient(json: Json, configLogic: ConfigLogic): HttpClient {
    val readTimeout = configLogic.environmentConfig.readTimeoutSeconds * 1000
    val connectTimeout = configLogic.environmentConfig.connectTimeoutSeconds * 1000

    return HttpClient(Android) {
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeout
            requestTimeoutMillis = readTimeout
            socketTimeoutMillis = readTimeout
        }

        install(Logging) {
            logger = Logger.DEFAULT
            level = networkLogLevelFor(configLogic.appBuildType)
        }

        install(ContentNegotiation) {
            json(
                json = json,
                contentType = ContentType.Application.Json
            )
        }
    }
}

internal fun networkLogLevelFor(appBuildType: AppBuildType): LogLevel {
    return when (appBuildType) {
        AppBuildType.DEBUG -> LogLevel.NONE
        AppBuildType.RELEASE -> LogLevel.NONE
    }
}

// ============================================================================
// Repositories
// ============================================================================

@Single
fun provideWalletAttestationRepository(httpClient: HttpClient): WalletAttestationRepository =
    WalletAttestationRepositoryImpl(httpClient)

// ============================================================================
// API Client (Ktor-based, replaces Retrofit)
// ============================================================================

@Single
fun provideApiClient(httpClient: HttpClient, configLogic: ConfigLogic): ApiClient =
    KtorApiClient(
        httpClient = httpClient,
        baseUrl = configLogic.environmentConfig.getServerHost(),
        gatewayBaseUrl = configLogic.environmentConfig.getGatewayHost()
    )

// Its own client: the shared one is also Wallet-Core's, and a trust check must not
// change SDK traffic behaviour or inherit its logging of credential bodies. No
// ContentNegotiation either - it appends application/json to the Accept header, and the
// signed-metadata fetch must ask for application/jwt alone to be answered with the JWS.
@Single
fun provideVeranaTrustRepository(): VeranaTrustRepository =
    VeranaTrustRepositoryImpl(
        httpClient = HttpClient(Android) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
            }
        },
        resolverUrl = "https://resolver.testnet.verana.network",
        apiUrl = "https://api.testnet.verana.network",
    )
