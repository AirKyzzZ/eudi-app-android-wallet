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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.europa.ec.resourceslogic.R

enum class VeranaVerdictUi(val label: String) {
    RESOLVING("CHECKING…"),
    TRUSTED("TRUSTED"),
    UNTRUSTED("UNTRUSTED"),
    UNVERIFIED("COULD NOT VERIFY"),
}

enum class VeranaStepToneUi {
    OK,
    BAD,
    NONE,
}

data class VeranaIdentityUi(
    val label: String,
    val tone: VeranaStepToneUi,
    val headline: String,
    val verified: Boolean,
    val lines: List<String> = emptyList(),
    val countryCode: String? = null,
    val registryId: String? = null,
)

data class VeranaAskUi(
    val label: String,
    val credentialName: String,
    val granted: Boolean?,
    val sentence: String,
)

data class VeranaLinkUi(
    val label: String,
    val url: String,
    val intact: Boolean,
)

data class VeranaConditionsUi(
    val minimumAge: Int?,
    val links: List<VeranaLinkUi>,
)

data class VeranaTrustCardUi(
    val verdict: VeranaVerdictUi,
    val note: String,
    val did: String? = null,
    val networkLabel: String? = null,
    val identities: List<VeranaIdentityUi> = emptyList(),
    val checkedAt: String? = null,
    val ask: VeranaAskUi? = null,
    val conditions: VeranaConditionsUi? = null,
    val canRetry: Boolean = false,
)

@Composable
fun VeranaTrustCard(
    data: VeranaTrustCardUi,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardPalette.surface, RoundedCornerShape(16.dp))
            .border(1.dp, CardPalette.grey200, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (data.did != null) {
            DidRow(did = data.did, verdict = data.verdict, networkLabel = data.networkLabel)
        } else if (data.verdict == VeranaVerdictUi.RESOLVING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = CardPalette.grey500,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.weight(1f))
                VeranaMark(size = 19.dp)
            }
        }

        if (data.identities.isNotEmpty()) {
            Column {
                data.identities.forEachIndexed { index, identity ->
                    ChainStep(identity = identity, isLast = index == data.identities.lastIndex)
                }
            }
        }

        VerdictPill(verdict = data.verdict, note = data.note)

        data.checkedAt?.let { Footnote(it) }

        data.ask?.let { AskBlock(it) }

        data.conditions?.let { ConditionsBlock(it) }

        if (data.canRetry) {
            Text(
                text = "Try again",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = CardPalette.link,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onRetry)
                    .padding(vertical = 4.dp),
            )
        }

        if (data.networkLabel != null) {
            Footnote("Demo network - do not share real data")
        }
    }
}

@Composable
private fun DidRow(
    did: String,
    verdict: VeranaVerdictUi,
    networkLabel: String?,
) = Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(verdict.color, CircleShape),
    )
    Text(
        text = did.middleTruncated(),
        style = MaterialTheme.typography.bodySmall,
        color = CardPalette.grey500,
        maxLines = 1,
        modifier = Modifier.weight(1f),
    )
    networkLabel?.let { label ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = CardPalette.warning,
            modifier = Modifier
                .background(CardPalette.warningContainer, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
    VeranaMark(size = 19.dp)
}

@Composable
private fun ChainStep(
    identity: VeranaIdentityUi,
    isLast: Boolean,
) = Row(
    modifier = Modifier.height(IntrinsicSize.Min),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    Column(
        modifier = Modifier
            .width(28.dp)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StepTick(identity.tone)
        if (!isLast) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .width(2.dp)
                    .background(identity.tone.railColor),
            )
        }
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(bottom = if (isLast) 0.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionLabel(identity.label)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = identity.headline,
                style = if (identity.verified) {
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                },
                color = when {
                    identity.verified -> CardPalette.grey900
                    identity.tone == VeranaStepToneUi.NONE -> CardPalette.grey600
                    else -> CardPalette.danger
                },
                modifier = Modifier.weight(1f, fill = false),
            )
            identity.countryCode?.let { Chip(text = it) }
        }
        identity.lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = if (identity.verified) CardPalette.grey700 else CardPalette.grey500,
            )
        }
        identity.registryId?.let { Chip(text = "REG $it") }
    }
}

@Composable
private fun StepTick(tone: VeranaStepToneUi) = Box(
    modifier = Modifier
        .size(28.dp)
        .background(tone.badgeColor, CircleShape),
    contentAlignment = Alignment.Center,
) {
    Text(
        text = when (tone) {
            VeranaStepToneUi.OK -> "✓"
            VeranaStepToneUi.BAD -> "✕"
            VeranaStepToneUi.NONE -> "?"
        },
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
    )
}

@Composable
private fun VerdictPill(
    verdict: VeranaVerdictUi,
    note: String,
) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(width = 2.dp, color = verdict.color, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        VeranaMark(size = 16.dp)
        Text(
            text = verdict.label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
            color = verdict.color,
        )
    }
    Text(
        text = note,
        style = MaterialTheme.typography.bodySmall,
        color = if (verdict == VeranaVerdictUi.UNTRUSTED) CardPalette.dangerDark else CardPalette.grey600,
    )
}

@Composable
private fun AskBlock(ask: VeranaAskUi) {
    val (borderColor, containerColor, mark) = when (ask.granted) {
        true -> Triple(CardPalette.positive, CardPalette.positiveContainer, "✓")
        false -> Triple(CardPalette.danger, CardPalette.dangerContainer, "✕")
        null -> Triple(CardPalette.grey200, CardPalette.grey50, "i")
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor, RoundedCornerShape(12.dp))
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        SectionLabel(ask.label)
        Text(
            text = ask.credentialName,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = CardPalette.grey900,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = mark,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = if (ask.granted == null) CardPalette.grey500 else borderColor,
            )
            Text(
                text = ask.sentence,
                style = MaterialTheme.typography.bodyMedium,
                color = CardPalette.grey800,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ConditionsBlock(conditions: VeranaConditionsUi) = Column(
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier
        .fillMaxWidth()
        .background(CardPalette.grey100, RoundedCornerShape(12.dp))
        .padding(12.dp),
) {
    SectionLabel("CONDITIONS OF CONNECTING")
    Text(
        text = conditions.minimumAge
            ?.let { "This service requires you to be at least $it to connect" }
            ?: "No age restriction",
        style = MaterialTheme.typography.bodyMedium,
        color = CardPalette.grey700,
    )
    val uriHandler = LocalUriHandler.current
    conditions.links.forEach { link ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { runCatching { uriHandler.openUri(link.url) } },
        ) {
            Text(
                text = link.label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = CardPalette.link,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (link.intact) "✓ intact" else "no digest",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (link.intact) FontWeight.Bold else FontWeight.Normal,
                ),
                color = if (link.intact) CardPalette.positive else CardPalette.grey500,
            )
        }
    }
}

@Composable
private fun Chip(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
    color = CardPalette.grey600,
    modifier = Modifier
        .background(CardPalette.grey100, RoundedCornerShape(4.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp),
)

@Composable
private fun SectionLabel(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 0.8.sp,
    ),
    color = CardPalette.grey500,
)

@Composable
private fun Footnote(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = CardPalette.grey500,
)

@Composable
private fun VeranaMark(size: Dp) = Image(
    painter = painterResource(id = R.drawable.ic_verana_mark),
    contentDescription = null,
    modifier = Modifier.size(size),
)

private val VeranaVerdictUi.color: Color
    get() = when (this) {
        VeranaVerdictUi.TRUSTED -> CardPalette.positive
        VeranaVerdictUi.UNTRUSTED -> CardPalette.danger
        VeranaVerdictUi.RESOLVING, VeranaVerdictUi.UNVERIFIED -> CardPalette.grey500
    }

private val VeranaStepToneUi.badgeColor: Color
    get() = when (this) {
        VeranaStepToneUi.OK -> CardPalette.positive
        VeranaStepToneUi.BAD -> CardPalette.danger
        VeranaStepToneUi.NONE -> CardPalette.grey400
    }

private val VeranaStepToneUi.railColor: Color
    get() = when (this) {
        VeranaStepToneUi.OK -> CardPalette.positiveRail
        VeranaStepToneUi.BAD -> CardPalette.dangerRail
        VeranaStepToneUi.NONE -> CardPalette.grey300
    }

private fun String.middleTruncated(maxLength: Int = 40): String =
    if (length <= maxLength) this else "${take(maxLength - 10)}…${takeLast(9)}"

private object CardPalette {
    val surface = Color(0xFFFFFFFF)
    val positive = Color(0xFF059669)
    val positiveContainer = Color(0xFFECFDF5)
    val positiveRail = Color(0xFF6EE7B7)
    val danger = Color(0xFFDC2626)
    val dangerDark = Color(0xFFB91C1C)
    val dangerContainer = Color(0xFFFEF2F2)
    val dangerRail = Color(0xFFFCA5A5)
    val warning = Color(0xFFD97706)
    val warningContainer = Color(0xFFFFFBEB)
    val link = Color(0xFF1D4ED8)
    val grey50 = Color(0xFFF9FAFB)
    val grey100 = Color(0xFFF3F4F6)
    val grey200 = Color(0xFFE5E7EB)
    val grey300 = Color(0xFFD1D5DB)
    val grey400 = Color(0xFF9CA3AF)
    val grey500 = Color(0xFF6B7280)
    val grey600 = Color(0xFF4B5563)
    val grey700 = Color(0xFF374151)
    val grey800 = Color(0xFF1F2937)
    val grey900 = Color(0xFF111827)
}
