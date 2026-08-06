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

package eu.europa.ec.uilogic.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable

// Wording and states follow the versioned card at playground/public/trust-card/index.html.
// The strings are deliberately not localized: the same evaluation must read identically in
// every wallet that renders it.

enum class VeranaVerdictUi { RESOLVING, TRUSTED, PARTIAL, UNTRUSTED, UNVERIFIED }

@Serializable
data class VeranaTrustCardUi(
    val verdict: VeranaVerdictUi = VeranaVerdictUi.RESOLVING,
    val sentence: String = "Checking the Verana public registry…",
    val did: String? = null,
    val serviceName: String? = null,
    val operatorName: String? = null,
    val serviceCredentialPresented: Boolean = false,
    val operatorCredentialPresented: Boolean = false,
    val serviceSelfIssued: Boolean = false,
    val operatorSelfIssued: Boolean = false,
    val accreditationReason: String? = null,
)

private const val SELF_ISSUED = "Issued by this service to itself, so nothing independent verifies it."

private val Green = Color(0xFF16A34A)
private val Amber = Color(0xFFD97706)
private val Red = Color(0xFFDC2626)
private val Grey = Color(0xFF6B7280)

private val VeranaVerdictUi.color: Color
    get() = when (this) {
        VeranaVerdictUi.TRUSTED -> Green
        VeranaVerdictUi.PARTIAL -> Amber
        VeranaVerdictUi.UNTRUSTED -> Red
        VeranaVerdictUi.RESOLVING, VeranaVerdictUi.UNVERIFIED -> Grey
    }

private val VeranaVerdictUi.pillLabel: String
    get() = when (this) {
        VeranaVerdictUi.TRUSTED -> "TRUSTED"
        VeranaVerdictUi.PARTIAL -> "PARTIAL"
        VeranaVerdictUi.UNTRUSTED -> "UNTRUSTED"
        VeranaVerdictUi.UNVERIFIED -> "COULD NOT VERIFY"
        VeranaVerdictUi.RESOLVING -> "RESOLVING"
    }

@Composable
fun VeranaTrustCard(
    modifier: Modifier = Modifier,
    data: VeranaTrustCardUi,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, outline, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        data.did?.let { did ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(data.verdict.color)
                    ) {}
                    Text(
                        text = truncateDid(did),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Pill(text = "TESTNET", color = Grey)
            }
        }

        IdentityBlock(
            label = "SERVICE",
            value = data.serviceName
                ?: if (data.serviceCredentialPresented) "Service claims not verified"
                else "No ECS-Service credential presented",
            verified = data.serviceName != null,
            subLine = SELF_ISSUED.takeIf {
                data.serviceName == null && data.serviceCredentialPresented && data.serviceSelfIssued
            },
        )
        IdentityBlock(
            label = "OPERATED BY",
            value = data.operatorName
                ?: if (data.operatorCredentialPresented) "Operator claims not verified"
                else "No ECS-Organization credential presented",
            verified = data.operatorName != null,
            subLine = when {
                data.operatorName == null && !data.operatorCredentialPresented ->
                    "Nothing verifies who operates this service"

                data.operatorName == null && data.operatorSelfIssued -> SELF_ISSUED
                else -> null
            },
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill(text = data.verdict.pillLabel, color = data.verdict.color)
            Text(
                text = data.sentence,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            data.accreditationReason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Amber,
                )
            }
        }

        Text(
            text = "Demo network - do not share real data",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IdentityBlock(
    label: String,
    value: String,
    verified: Boolean,
    subLine: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (verified) FontWeight.SemiBold else FontWeight.Normal,
            color = if (verified) MaterialTheme.colorScheme.onSurface else Grey,
        )
        subLine?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = Grey,
            )
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

private fun truncateDid(did: String): String =
    if (did.length <= 40) did else "${did.take(24)}…${did.takeLast(12)}"
