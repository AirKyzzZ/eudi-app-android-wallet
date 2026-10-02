package eu.europa.ec.corelogic.verana

import eu.europa.ec.businesslogic.controller.log.LogController
import eu.europa.ec.corelogic.model.VeranaAccreditation
import eu.europa.ec.corelogic.model.VeranaDidKeyBinding
import eu.europa.ec.corelogic.model.VeranaTrustEvidence
import eu.europa.ec.networklogic.model.VeranaTrustResolution
import eu.europa.ec.networklogic.model.VeranaTrustResolution.Companion.unresolved
import eu.europa.ec.networklogic.model.VeranaTrustRole
import eu.europa.ec.networklogic.model.VeranaTrustStatus
import eu.europa.ec.networklogic.model.VeranaUntrustedReason
import eu.europa.ec.networklogic.util.VeranaDids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.security.interfaces.ECPublicKey

fun interface EvaluateVeranaTrust {
    suspend operator fun invoke(
        role: VeranaTrustRole,
        did: String,
        vct: String?,
        certificateKey: ECPublicKey?,
    ): VeranaTrustEvidence
}

class EvaluateVeranaTrustImpl(
    private val verifyVeranaDidKeyBinding: VerifyVeranaDidKeyBinding,
    private val resolveVeranaTrust: ResolveVeranaTrust,
    private val checkVeranaAccreditation: CheckVeranaAccreditation,
    private val logController: LogController,
) : EvaluateVeranaTrust {

    override suspend fun invoke(
        role: VeranaTrustRole,
        did: String,
        vct: String?,
        certificateKey: ECPublicKey?,
    ): VeranaTrustEvidence {
        fun evidence(resolution: VeranaTrustResolution, accreditation: VeranaAccreditation?) = VeranaTrustEvidence(
            role = role,
            did = did,
            vct = vct,
            resolution = resolution,
            accreditation = accreditation,
        )

        return try {
            val binding = if (certificateKey != null && VeranaDids.hasDidDocument(did)) {
                verifyVeranaDidKeyBinding(did = did, role = role, certificateKey = certificateKey)
            } else {
                VeranaDidKeyBinding.PROVEN
            }
            when (binding) {
                VeranaDidKeyBinding.NOT_PROVEN -> evidence(
                    resolution = unresolved(did, VeranaTrustStatus.UNTRUSTED, VeranaUntrustedReason.DID_NOT_PROVEN),
                    accreditation = null,
                )

                VeranaDidKeyBinding.UNAVAILABLE -> evidence(
                    resolution = unresolved(did, VeranaTrustStatus.UNVERIFIED),
                    accreditation = null,
                )

                VeranaDidKeyBinding.PROVEN -> coroutineScope {
                    val resolution = async { resolveVeranaTrust(did) }
                    val accreditation = async { checkVeranaAccreditation(did = did, role = role, vct = vct) }
                    evidence(resolution = resolution.await(), accreditation = accreditation.await())
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logController.e(TAG, error)
            evidence(resolution = unresolved(did, VeranaTrustStatus.UNVERIFIED), accreditation = null)
        }
    }

    private companion object {
        const val TAG = "VeranaTrust"
    }
}
