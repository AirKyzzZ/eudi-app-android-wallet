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

package eu.europa.ec.startupfeature.interactor

import eu.europa.ec.authenticationlogic.controller.storage.RecoveryCheckpointController
import eu.europa.ec.authenticationlogic.gate.LocalUnlockTracker
import eu.europa.ec.authenticationlogic.model.LocalAuthRouteDecision
import eu.europa.ec.authenticationlogic.model.LocalUnlockStatus
import eu.europa.ec.authenticationlogic.model.Profile
import eu.europa.ec.authenticationlogic.model.RecoveryCheckpoint
import eu.europa.ec.authenticationlogic.repository.SupabaseAuthRepository
import eu.europa.ec.authenticationlogic.storage.LocalAuthKeys
import eu.europa.ec.authenticationlogic.usecase.GetLegalAcceptanceStateUseCase
import eu.europa.ec.authenticationlogic.usecase.GetMyProfileUseCase
import eu.europa.ec.authenticationlogic.usecase.IsProfileCompletedUseCase
import eu.europa.ec.authenticationlogic.usecase.IsWalletActivatedUseCase
import eu.europa.ec.authenticationlogic.usecase.ResolveLocalAuthRouteUseCase
import eu.europa.ec.authenticationlogic.usecase.SignOutMode
import eu.europa.ec.authenticationlogic.usecase.SignOutUseCase
import eu.europa.ec.authenticationlogic.usecase.WalletActivationStatus
import eu.europa.ec.businesslogic.controller.device.DeviceController
import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.businesslogic.controller.storage.PrefKeysV2
import eu.europa.ec.businesslogic.controller.storage.PrefsControllerV2
import eu.europa.ec.businesslogic.controller.wallet.UserDocumentOwnershipController
import eu.europa.ec.businesslogic.config.E2eRuntimeConfig
import eu.europa.ec.commonfeature.interactor.QuickPinInteractor
import eu.europa.ec.startupfeature.model.StartupState
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Streamlined splash interactor with proper session initialization and state-based routing.
 *
 * IMPROVEMENTS:
 * - Returns StartupState for better error handling and logging
 * - Three-level state checking: Authentication → Onboarding → Local Unlock
 * - TTL-based PIN skip for hot starts
 * - Comprehensive logging for debugging startup issues
 *
 * Startup Flow:
 * 1. Wait for Supabase session to initialize (not in Initializing state)
 * 2. Check authentication status (Supabase is source of truth)
 * 3. If authenticated: Check profile completion → wallet activation → local unlock
 * 4. Return appropriate StartupState based on checks
 */
interface SplashInteractor {
    /**
     * Determine the startup state based on authentication, profile, wallet, and unlock status.
     *
     * @return StartupState containing the appropriate screen route and log message
     */
    suspend fun determineStartupState(): StartupState
}

class SplashInteractorImpl(
    private val supabaseAuthRepository: SupabaseAuthRepository,
    private val prefKeys: PrefKeysV2,
    private val prefsController: PrefsControllerV2,
    private val logController: LogController,
    private val getMyProfileUseCase: GetMyProfileUseCase,
    private val getLegalAcceptanceStateUseCase: GetLegalAcceptanceStateUseCase,
    private val isWalletActivatedUseCase: IsWalletActivatedUseCase,
    private val isProfileCompletedUseCase: IsProfileCompletedUseCase,
    private val quickPinInteractor: QuickPinInteractor,
    private val localUnlockTracker: LocalUnlockTracker,
    private val deviceController: DeviceController,
    private val signOutUseCase: SignOutUseCase,
    private val ownershipController: UserDocumentOwnershipController,
    private val recoveryCheckpointController: RecoveryCheckpointController,
    private val resolveLocalAuthRouteUseCase: ResolveLocalAuthRouteUseCase
) : SplashInteractor {

    companion object {
        private const val TAG = "SplashInteractor"
        private const val SESSION_RESTORE_GRACE_MS = 2_000L
        private const val SESSION_DATA_READY_TIMEOUT_MS = 2_000L
        private const val SESSION_DATA_POLL_INTERVAL_MS = 100L
    }

    override suspend fun determineStartupState(): StartupState {
        try {
            logController.i(TAG) { "Starting startup state determination..." }

            if (E2eRuntimeConfig.isEnabled) {
                logController.i(TAG) { "E2E mode enabled, bypassing auth and onboarding gates" }
                val unlockState = checkLocalUnlockState()
                logController.i(TAG) { unlockState.logMessage }
                return unlockState
            }

            // Level 1: Authentication Check
            val authState = checkAuthenticationState()
            if (authState != null) {
                logController.i(TAG) { authState.logMessage }
                return authState
            }

            // Migrate orphaned documents to current user (one-time per user)
            migrateOrphanedDocumentsIfNeeded()

            // Level 2: Account Deletion Check
            val accountDeletionState = checkAccountDeletionState()
            if (accountDeletionState != null) {
                logController.i(TAG) { accountDeletionState.logMessage }
                return accountDeletionState
            }

            // Level 3: Legal Acceptance Check
            val legalState = checkLegalAcceptanceState()
            if (legalState != null) {
                logController.i(TAG) { legalState.logMessage }
                return legalState
            }

            // Level 4: Onboarding Check (Profile + WUA)
            val onboardingState = checkOnboardingState()
            if (onboardingState != null) {
                logController.i(TAG) { onboardingState.logMessage }
                return onboardingState
            }

            // Level 5: Local Unlock Check (PIN/Biometric)
            val unlockState = checkLocalUnlockState()
            logController.i(TAG) { unlockState.logMessage }
            return unlockState

        } catch (e: SecurityException) {
            logController.e(TAG, e)
            val state = StartupState.SecurityError(e.message ?: "Security error")
            logController.w(TAG) { state.logMessage }
            return state
        } catch (e: Exception) {
            logController.e(TAG, e)
            val state = StartupState.SecurityError("Unexpected error: ${e.message}")
            logController.w(TAG) { state.logMessage }
            return state
        }
    }

    /**
     * Level 1: Check authentication status.
     *
     * Verifies both:
     * 1. SessionStatus is Authenticated (auth state flow)
     * 2. Session data is actually accessible (currentSessionOrNull returns data)
     *
     * This prevents race conditions where the auth state flow emits Authenticated
     * before the session data is fully available for user-scoped storage access.
     *
     * @return StartupState if not authenticated, null if authenticated and session data ready
     */
    private suspend fun checkAuthenticationState(): StartupState? {
        logController.d(TAG, "Level 1: Checking authentication status...")

        val sessionStatus = waitForSessionInitialization()
        logController.d(TAG, "Session status: ${sessionStatus::class.simpleName}")

        if (sessionStatus !is SessionStatus.Authenticated) {
            return StartupState.NotAuthenticated
        }

        // Wait for session data to be accessible (fixes race condition with user-scoped storage)
        val sessionDataReady = waitForSessionDataReady()
        if (!sessionDataReady) {
            logController.w(TAG) { "Session authenticated but data not accessible, treating as not authenticated" }
            return StartupState.NotAuthenticated
        }

        logController.d(TAG, "User is authenticated and session data ready, proceeding to onboarding check")
        return null // Continue to next level
    }

    /**
     * Wait for session data to be accessible via currentSessionOrNull().
     *
     * There can be a brief delay between SessionStatus.Authenticated being emitted
     * and the session data being available through currentSessionOrNull(). This
     * causes issues with user-scoped storage (PrefsControllerV2) which relies on
     * the user ID from the session.
     *
     * @return true if session data became accessible within timeout, false otherwise
     */
    private suspend fun waitForSessionDataReady(): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < SESSION_DATA_READY_TIMEOUT_MS) {
            if (prefsController.hasAuthenticatedUser()) {
                logController.d(TAG, "Session data ready after ${System.currentTimeMillis() - startTime}ms")
                return true
            }
            delay(SESSION_DATA_POLL_INTERVAL_MS)
        }

        logController.w(TAG) { "Timeout waiting for session data to be accessible" }
        return false
    }

    private suspend fun checkLegalAcceptanceState(): StartupState? {
        logController.d(TAG, "Level 3: Checking legal acceptance status...")
        val snapshot = getLegalAcceptanceStateUseCase().getOrNull()
            ?: return StartupState.LegalAcceptanceRequired
        return if (snapshot.isAccepted) {
            null
        } else {
            StartupState.LegalAcceptanceRequired
        }
    }

    private suspend fun checkAccountDeletionState(): StartupState? {
        logController.d(TAG, "Level 2: Checking account deletion status...")
        val profile: Profile = getMyProfileUseCase().getOrNull() ?: return null
        return if (profile.accountDeletion?.isBlocked == true) {
            StartupState.AccountDeletionScheduled
        } else {
            null
        }
    }

    /**
     * Level 3: Check onboarding status (profile + wallet activation).
     *
     * @return StartupState if onboarding incomplete, null if complete
     */
    private suspend fun checkOnboardingState(): StartupState? {
        logController.d(TAG, "Level 3: Checking onboarding status...")
        val profileCompleted = isProfileCompletedUseCase()
        logController.d(TAG, "Profile completed: $profileCompleted")
        if (!profileCompleted) {
            return StartupState.ProfileIncomplete
        }
        val securityState = deviceController.getDeviceSecurityState()
        logController.d(TAG, "Device security ready: ${securityState.isReady}")
        if (!securityState.isReady) {
            val walletStatus = isWalletActivatedUseCase()
            handleMissingDeviceSecurity(walletStatus)
            return StartupState.DeviceSecurityRequired
        }
        val recoveryCheckpoint: RecoveryCheckpoint = recoveryCheckpointController.getCheckpoint()
        if (recoveryCheckpoint != RecoveryCheckpoint.NONE) {
            logController.w(TAG) { "Recovery checkpoint pending: $recoveryCheckpoint" }
            return StartupState.WalletNotActivated("wallet recovery pending")
        }
        val walletStatus = isWalletActivatedUseCase()
        logController.d(TAG, "Wallet status: ${walletStatus::class.simpleName}")
        when (walletStatus) {
            WalletActivationStatus.Activated -> {
                logController.d(TAG, "Wallet is activated, proceeding to local unlock check")
                return null
            }
            is WalletActivationStatus.NotActivated -> {
                logController.d(TAG, "Wallet not activated: ${walletStatus.reason}")
                if (walletStatus.localFlagSet && !walletStatus.privateKeyExists) {
                    logController.w(TAG) { "Inconsistent state: Clearing stale activation flag" }
                    try {
                        prefKeys.setWalletActivated(false)
                    } catch (e: Exception) {
                        logController.e(TAG, e)
                    }
                }
                return StartupState.WalletNotActivated(walletStatus.reason)
            }
        }
    }

    private suspend fun handleMissingDeviceSecurity(walletStatus: WalletActivationStatus) {
        if (walletStatus is WalletActivationStatus.Activated) {
            logController.w(TAG) { "Device security missing while wallet active, signing out" }
            try {
                prefKeys.setWalletActivated(false)
            } catch (e: Exception) {
                logController.e(TAG, e)
            }
            try {
                signOutUseCase(SignOutMode.Soft)
            } catch (e: Exception) {
                logController.e(TAG, e)
            }
        } else {
            logController.w(TAG) { "Device security missing before wallet activation" }
        }
    }

    /**
     * Level 4: Check local unlock status (PIN/biometric).
     *
     * This determines whether the user needs to verify their PIN or can go directly to dashboard.
     *
     * @return StartupState for the appropriate screen
     */
    private suspend fun checkLocalUnlockState(): StartupState {
        logController.d(TAG, "Level 4: Checking local unlock status...")

        val localUnlockStatus: LocalUnlockStatus = quickPinInteractor.getLocalUnlockStatus()
        logController.d(TAG, "Local unlock status: $localUnlockStatus")
        val configuredEnrollmentRequired: Boolean = prefsController.safeBool(
            LocalAuthKeys.ENROLLMENT_REQUIRED,
            false
        )
        // No Supabase signup runs in E2E mode to set the enrollment flag, so a fresh install
        // must still be sent to PIN creation rather than the recovery/tamper branch.
        val enrollmentRequired: Boolean = configuredEnrollmentRequired || E2eRuntimeConfig.isEnabled
        val isUnlocked: Boolean = localUnlockTracker.isUnlocked()
        logController.d(TAG, "Is unlocked (within TTL): $isUnlocked")
        val routeDecision: LocalAuthRouteDecision = resolveLocalAuthRouteUseCase(
            localUnlockStatus = localUnlockStatus,
            enrollmentRequired = enrollmentRequired,
            recoveryCheckpoint = RecoveryCheckpoint.NONE,
            isUnlocked = isUnlocked
        )
        return when (routeDecision) {
            LocalAuthRouteDecision.Ready -> StartupState.Ready
            LocalAuthRouteDecision.PinCreate -> StartupState.PinNotSet
            LocalAuthRouteDecision.PinVerificationRequired -> StartupState.PinVerificationRequired
            LocalAuthRouteDecision.PinRecoveryRequired -> {
                if (localUnlockStatus == LocalUnlockStatus.NotProvisioned && !enrollmentRequired) {
                    reportLocalAuthTamperDetected(
                        signals = listOf("unexpected_missing_auth_material")
                    )
                }
                StartupState.PinRecoveryRequired
            }
            is LocalAuthRouteDecision.SecurityError -> {
                if (localUnlockStatus == LocalUnlockStatus.TamperDetected) {
                    reportLocalAuthTamperDetected(
                        signals = listOf("local_auth_integrity_failure")
                    )
                }
                StartupState.SecurityError(routeDecision.message)
            }
        }
    }

    private suspend fun reportLocalAuthTamperDetected(signals: List<String>) {
        runCatching {
            quickPinInteractor.reportTamperDetected(signals)
        }.onFailure { error ->
            logController.w(TAG) {
                "Failed to report local auth tamper incident: ${error.message}"
            }
        }
    }

    /**
     * One-time migration: binds existing SDK documents and Room records
     * to the current user if they haven't been assigned yet.
     * Non-fatal — user proceeds even if migration fails.
     */
    private suspend fun migrateOrphanedDocumentsIfNeeded() {
        try {
            if (!ownershipController.isMigrationCompleted()) {
                val count = ownershipController.migrateOrphanedDocumentsToCurrentUser()
                ownershipController.setMigrationCompleted()
                if (count > 0) {
                    logController.i(TAG) { "Migrated $count orphaned documents to current user" }
                }
            }
        } catch (e: Exception) {
            logController.w(TAG) { "Document ownership migration failed (non-fatal): ${e.message}" }
        }
    }

    /**
     * Wait for Supabase session to finish initializing.
     *
     * This prevents race conditions by ensuring session state
     * is fully determined before we make routing decisions.
     *
     * Supabase auto-restores sessions on app start, but this happens
     * asynchronously. We must wait for it to complete.
     */
    private suspend fun waitForSessionInitialization(): SessionStatus {
        return try {
            logController.d(TAG, "Waiting for session initialization...")
            val initialStatus = supabaseAuthRepository.observeAuthState()
                .first { status -> status !is SessionStatus.Initializing }
            if (initialStatus is SessionStatus.NotAuthenticated && !initialStatus.isSignOut) {
                logController.d(TAG, "Session not authenticated after init, waiting for restore grace...")
                val restoredStatus = withTimeoutOrNull(SESSION_RESTORE_GRACE_MS) {
                    supabaseAuthRepository.observeAuthState().first { status ->
                        status is SessionStatus.Authenticated ||
                            (status is SessionStatus.NotAuthenticated && status.isSignOut)
                    }
                }
                restoredStatus ?: initialStatus
            } else {
                initialStatus
            }
        } catch (e: Exception) {
            logController.e(TAG, e)
            logController.w(TAG) { "Session initialization failed, defaulting to not authenticated" }
            // Default to not authenticated on error (not an explicit sign-out)
            SessionStatus.NotAuthenticated(isSignOut = false)
        }
    }
}
