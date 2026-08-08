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

package eu.europa.ec.assemblylogic.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.europa.ec.authenticationfeature.router.featureAuthenticationGraph
import eu.europa.ec.commonfeature.router.featureCommonGraph
import eu.europa.ec.dashboardfeature.router.featureDashboardGraph
import eu.europa.ec.issuancefeature.router.featureIssuanceGraph
import eu.europa.ec.presentationfeature.router.presentationGraph
import eu.europa.ec.proximityfeature.router.featureProximityGraph

import eu.europa.ec.authenticationlogic.gate.LocalUnlockTracker
import eu.europa.ec.startupfeature.router.featureStartupGraph
import eu.europa.ec.uilogic.component.utils.NfcTagHandler
import eu.europa.ec.uilogic.container.EudiComponentActivity
import org.koin.android.ext.android.inject

open class MainActivity : EudiComponentActivity() {

    private val nfcTagHandler: NfcTagHandler by inject()
    private val localUnlockTracker: LocalUnlockTracker by inject()

    /**
     * Tracks whether the Activity was stopped (app went to background).
     * Used to decide if we need to re-check the lock state on [onStart].
     * Resets to false on new Activity instances (e.g., after recreate or config change).
     */
    private var wasBackgrounded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, ">>> onCreate() — wasBackgrounded=$wasBackgrounded, savedInstanceState=${savedInstanceState != null}")
        initializeActivityUi(intent)
    }

    internal open fun initializeActivityUi(startIntent: Intent?) {
        nfcTagHandler.initialize(this)
        enableEdgeToEdge()
        setContent {
            Content(startIntent) {
                featureStartupGraph(it)
                featureCommonGraph(it)
                featureDashboardGraph(it)
                presentationGraph(it)
                featureProximityGraph(it)
                featureIssuanceGraph(it)
                featureAuthenticationGraph(it)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, ">>> onStart() — wasBackgrounded=$wasBackgrounded")
        if (wasBackgrounded) {
            wasBackgrounded = false
            val unlocked = localUnlockTracker.isUnlocked()
            Log.d(TAG, "    onStart() — was backgrounded, isUnlocked()=$unlocked")
            if (!unlocked) {
                val deepLink: Uri? = getPendingDeepLinkUri()
                val pendingIntent: Intent? = getPendingIntentForRestart()
                Log.d(TAG, "    onStart() — NOT unlocked, restarting clean (no saved state) to trigger PIN flow, pendingDeepLink=$deepLink, pendingAction=${pendingIntent?.action}")
                // Must NOT use recreate() — it preserves savedInstanceState which
                // restores the Compose Navigation back stack to Dashboard.
                // Instead, launch a fresh MainActivity and finish this one.
                // Preserve any pending external request so it survives the restart.
                val restartIntent: Intent = Intent(pendingIntent ?: Intent()).apply {
                    setClass(this@MainActivity, MainActivity::class.java)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    if (data == null) {
                        deepLink?.let { data = it }
                    }
                }
                startActivity(restartIntent)
                finish()
                return
            } else {
                Log.d(TAG, "    onStart() — still unlocked, continuing normally")
            }
        } else {
            Log.d(TAG, "    onStart() — NOT backgrounded (fresh start or recreate), skipping lock check")
        }
    }

    override fun onStop() {
        super.onStop()
        wasBackgrounded = true
        Log.d(TAG, ">>> onStop() — wasBackgrounded=true")
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onResume() {
        super.onResume()
        // Re-enable NFC dispatch if a screen had requested it
        nfcTagHandler.enableForegroundDispatch(this)
    }

    override fun onPause() {
        super.onPause()
        nfcTagHandler.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Try to handle as NFC intent first
        nfcTagHandler.handleIntent(intent)
    }
}
