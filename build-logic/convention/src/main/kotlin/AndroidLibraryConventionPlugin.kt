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

import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.google.android.libraries.mapsplatform.secrets_gradle_plugin.SecretsPluginExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
import project.convention.logic.addConfigField
import project.convention.logic.AppFlavor
import project.convention.logic.config.LibraryModule
import project.convention.logic.config.LibraryPluginConfig
import project.convention.logic.configureFlavors
import project.convention.logic.configureGradleManagedDevices
import project.convention.logic.configureKotlinAndroid
import project.convention.logic.configurePrintApksTask
import project.convention.logic.disableUnnecessaryAndroidTests
import project.convention.logic.getProperty
import project.convention.logic.libs

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {

        with(target) {

            val config =
                extensions.create<LibraryPluginConfig>("moduleConfig", LibraryModule.Unspecified)

            val walletScheme = "authbound-wallet"
            val walletHost = "*"
            val verificationPortalHost = project.getProperty<String>("VERIFICATION_PORTAL_HOST")
                ?: "app.authbound.io"

            val eudiOpenId4VpScheme = "eudi-openid4vp"
            val eudiOpenid4VpHost = "*"

            val mdocOpenId4VpScheme = "mdoc-openid4vp"
            val mdocOpenid4VpHost = "*"

            val openId4VpScheme = "openid4vp"
            val openid4VpHost = "*"

            val haipOpenId4VpScheme = "haip-vp"
            val haipOpenid4VpHost = "*"

            val credentialOfferScheme = "openid-credential-offer"
            val credentialOfferHost = "*"

            val credentialOfferHaipScheme = "haip-vci"
            val credentialOfferHaipHost = "*"

            val openId4VciAuthorizationScheme = "io.authbound.wallet"
            val openId4VciAuthorizationHost = "authorization"

            val rqesScheme = "rqes"
            val rqesHost = "oauth"
            val rqesPath = "/callback"

            // TODO This is temporary until proper value
            val rqesDocRetrievalScheme = "eudi-rqes"
            val rqesDocRetrievalHost = "*"

            with(pluginManager) {
                apply("com.android.library")
                apply("project.android.library.kover")
                apply("project.android.lint")
                apply("project.android.koin")
                apply("kotlinx-serialization")
                apply("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
                apply("kotlin-parcelize")
            }

            extensions.configure<LibraryExtension>("android") {
                configureKotlinAndroid(this)
                with(defaultConfig) {

                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"



                    addConfigField("SUPABASE_URL", project.getProperty("SUPABASE_URL") ?: "")
                    addConfigField("SUPABASE_ANON_KEY", project.getProperty("SUPABASE_ANON_KEY") ?: "")
                    addConfigField("GOOGLE_AUTH_CLIENT_ID", project.getProperty("GOOGLE_AUTH_CLIENT_ID") ?: "")
                    addConfigField("E2E_MODE", project.getProperty<String>("E2E_MODE")?.toBoolean() ?: false)
                    addConfigField("E2E_ISSUER_BASE_URL", "")
                    addConfigField("E2E_VERIFIER_API_URL", "")
                    addConfigField("E2E_VERIFIER_UI_URL", "")
                    addConfigField("DEEPLINK", "$walletScheme://")
                    addConfigField("VERIFICATION_PORTAL_HOST", verificationPortalHost)
                    addConfigField("EUDI_OPENID4VP_SCHEME", eudiOpenId4VpScheme)
                    addConfigField("MDOC_OPENID4VP_SCHEME", mdocOpenId4VpScheme)
                    addConfigField("OPENID4VP_SCHEME", openId4VpScheme)
                    addConfigField("HAIP_OPENID4VP_SCHEME", haipOpenId4VpScheme)
                    addConfigField("CREDENTIAL_OFFER_SCHEME", credentialOfferScheme)
                    addConfigField("CREDENTIAL_OFFER_HAIP_SCHEME", credentialOfferHaipScheme)
                    addConfigField("ISSUE_AUTHORIZATION_SCHEME", openId4VciAuthorizationScheme)
                    addConfigField("ISSUE_AUTHORIZATION_HOST", openId4VciAuthorizationHost)
                    addConfigField(
                        "ISSUE_AUTHORIZATION_DEEPLINK",
                        "$openId4VciAuthorizationScheme://$openId4VciAuthorizationHost"
                    )
                    addConfigField("RQES_SCHEME", rqesScheme)
                    addConfigField("RQES_HOST", rqesHost)
                    addConfigField("RQES_DEEPLINK", "$rqesScheme://$rqesHost$rqesPath")
                    addConfigField("RQES_DOC_RETRIEVAL_SCHEME", rqesDocRetrievalScheme)

                    // Manifest placeholders for Wallet deepLink
                    manifestPlaceholders["deepLinkScheme"] = walletScheme
                    manifestPlaceholders["deepLinkHost"] = walletHost
                    manifestPlaceholders["verificationPortalHost"] = verificationPortalHost

                    // Manifest placeholders used for OpenId4VP
                    manifestPlaceholders["eudiOpenid4vpScheme"] = eudiOpenId4VpScheme
                    manifestPlaceholders["eudiOpenid4vpHost"] = eudiOpenid4VpHost
                    manifestPlaceholders["mdocOpenid4vpScheme"] = mdocOpenId4VpScheme
                    manifestPlaceholders["mdocOpenid4vpHost"] = mdocOpenid4VpHost
                    manifestPlaceholders["openid4vpScheme"] = openId4VpScheme
                    manifestPlaceholders["openid4vpHost"] = openid4VpHost
                    manifestPlaceholders["haipOpenid4vpScheme"] = haipOpenId4VpScheme
                    manifestPlaceholders["haipOpenid4vpHost"] = haipOpenid4VpHost

                    // Manifest placeholders used for OpenId4VCI
                    manifestPlaceholders["credentialOfferHost"] = credentialOfferHost
                    manifestPlaceholders["credentialOfferScheme"] = credentialOfferScheme
                    manifestPlaceholders["credentialOfferHaipHost"] = credentialOfferHaipHost
                    manifestPlaceholders["credentialOfferHaipScheme"] = credentialOfferHaipScheme

                    // Manifest placeholders used for OpenId4VCI Authorization
                    manifestPlaceholders["openId4VciAuthorizationScheme"] =
                        openId4VciAuthorizationScheme
                    manifestPlaceholders["openId4VciAuthorizationHost"] =
                        openId4VciAuthorizationHost

                    // Manifest placeholders used for RQES
                    manifestPlaceholders["rqesHost"] = rqesHost
                    manifestPlaceholders["rqesScheme"] = rqesScheme
                    manifestPlaceholders["rqesPath"] = rqesPath

                    // Manifest placeholders used for RQES Document Retrieval
                    manifestPlaceholders["rqesDocRetrievalScheme"] = rqesDocRetrievalScheme
                    manifestPlaceholders["rqesDocRetrievalHost"] = rqesDocRetrievalHost
                }
                configureFlavors(this) { flavor ->
                    val isDevFlavor = flavor == AppFlavor.Dev
                    addConfigField(
                        "E2E_MODE",
                        if (isDevFlavor) {
                            (project.getProperty<String>("E2E_MODE") ?: "false").toBoolean()
                        } else {
                            false
                        }
                    )
                    addConfigField(
                        "E2E_ISSUER_BASE_URL",
                        if (isDevFlavor) project.getProperty("E2E_ISSUER_BASE_URL") ?: "" else ""
                    )
                    addConfigField(
                        "E2E_VERIFIER_API_URL",
                        if (isDevFlavor) project.getProperty("E2E_VERIFIER_API_URL") ?: "" else ""
                    )
                    addConfigField(
                        "E2E_VERIFIER_UI_URL",
                        if (isDevFlavor) project.getProperty("E2E_VERIFIER_UI_URL") ?: "" else ""
                    )
                }
                configureGradleManagedDevices(this)
            }
            extensions.configure<LibraryAndroidComponentsExtension> {
                configurePrintApksTask(this)
                disableUnnecessaryAndroidTests(target)
            }
            extensions.configure<SecretsPluginExtension> {
                defaultPropertiesFileName = "secrets.defaults.properties"
                ignoreList.add("sdk.*")
            }
            dependencies {
                add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
                add("implementation", libs.findLibrary("kotlinx-coroutines-guava").get())
                add("implementation", libs.findLibrary("kotlinx.serialization.json").get())
                add("implementation", libs.findLibrary("androidx-work-ktx").get())
            }
            afterEvaluate {
                if (!config.module.isLogicModule && !config.module.isFeatureCommon) {
                    dependencies {
                        add("implementation", project(LibraryModule.CommonFeature.path))
                    }
                }
            }
        }
    }
}
