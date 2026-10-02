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

package eu.europa.ec.networklogic.di

import eu.europa.ec.businesslogic.config.AppBuildType
import eu.europa.ec.businesslogic.config.ConfigLogic
import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.networklogic.repository.VeranaDocumentRepository
import eu.europa.ec.networklogic.repository.VeranaDocumentRepositoryImpl
import eu.europa.ec.networklogic.repository.VeranaIndexerRepository
import eu.europa.ec.networklogic.repository.VeranaIndexerRepositoryImpl
import eu.europa.ec.networklogic.repository.WalletAttestationRepository
import eu.europa.ec.networklogic.repository.WalletAttestationRepositoryImpl
import eu.europa.ec.networklogic.util.VeranaHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@Configuration
@ComponentScan("eu.europa.ec.networklogic")
class LogicNetworkModule

@Single
fun provideJson(): Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    isLenient = true
}

@Single
fun provideHttpClient(json: Json, configLogic: ConfigLogic): HttpClient {
    return HttpClient(Android) {

        install(Logging) {
            logger = Logger.DEFAULT
            level = when (configLogic.appBuildType) {
                AppBuildType.DEBUG -> LogLevel.BODY
                AppBuildType.RELEASE -> LogLevel.NONE
            }
        }

        install(ContentNegotiation) {
            json(
                json = json,
                contentType = ContentType.Application.Json
            )
        }
    }
}

@Single
fun provideWalletAttestationRepository(httpClient: HttpClient): WalletAttestationRepository =
    WalletAttestationRepositoryImpl(httpClient)

// No ContentNegotiation: it appends application/json to Accept, and signed issuer metadata needs application/jwt alone.
@Single
fun provideVeranaHttpClient(logController: LogController): VeranaHttpClient = VeranaHttpClient(
    httpClient = HttpClient(Android) {
        engine {
            connectTimeout = VERANA_TIMEOUT_MILLIS
            socketTimeout = VERANA_TIMEOUT_MILLIS
        }
    },
    logController = logController,
)

private const val VERANA_TIMEOUT_MILLIS = 15_000

@Single
fun provideVeranaIndexerRepository(httpClient: VeranaHttpClient): VeranaIndexerRepository =
    VeranaIndexerRepositoryImpl(httpClient)

@Single
fun provideVeranaDocumentRepository(httpClient: VeranaHttpClient): VeranaDocumentRepository =
    VeranaDocumentRepositoryImpl(httpClient)
