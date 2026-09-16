package com.elham.priorityringer.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elham.priorityringer.R
import com.elham.priorityringer.domain.model.AuditSeverity
import com.elham.priorityringer.domain.model.CapabilityStatus
import com.elham.priorityringer.presentation.theme.PriorityRingerTheme

/**
 * Persistent, dismissible banner for "restore failed" / "DND ineffective"
 * (Architecture.md § 4 rule 5, § 6.3).
 *
 * Deliberately not a snackbar: § 6.3 requires it to stay until the user
 * dismisses it or the condition clears, and a transient toast for "your phone
 * may still be off Do Not Disturb at full volume" would be negligent.
 */
@Composable
fun PersistentBanner(
    title: String,
    message: String,
    severity: AuditSeverity,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val container = severity.containerColor()
    val onContainer = severity.onContainerColor()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(severity.icon(), contentDescription = null, tint = onContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_dismiss),
                        tint = onContainer,
                    )
                }
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * One capability row (Architecture.md § 12).
 *
 * The [CapabilityStatus.consequence] is rendered for every non-granted state.
 * § 12 forbids a bare ✗ — a cross tells the user nothing about what they have
 * lost, and the whole point of this screen is that the app is honest about what
 * it cannot do.
 */
@Composable
fun CapabilityRow(
    status: CapabilityStatus,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val granted = status.state == CapabilityStatus.State.GRANTED
    val notRequired = status.state == CapabilityStatus.State.NOT_REQUIRED

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = status.state.icon(),
                    contentDescription = null,
                    tint = status.state.tint(),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(status.capability.labelRes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(status.state.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = status.state.tint(),
                    )
                }
                if (status.capability.isRequired && !granted) {
                    RequiredTag()
                }
            }

            Text(
                text = stringResource(status.capability.purposeRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!granted && !notRequired) {
                ConsequenceBlock(status.consequence)
            }

            status.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // isActionable is DENIED-only by construction, so RESTRICTED rows
            // (fixed volume, API too old) never offer a button that cannot work.
            if (status.isActionable) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onAction) {
                    Text(stringResource(status.capability.actionLabelRes))
                }
            }
        }
    }
}

@Composable
private fun ConsequenceBlock(consequence: String) {
    val palette = PriorityRingerTheme.status
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.degradedContainer, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = stringResource(R.string.cd_consequence),
            tint = palette.onDegradedContainer,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = consequence,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onDegradedContainer,
        )
    }
}

@Composable
private fun RequiredTag() {
    val palette = PriorityRingerTheme.status
    Text(
        text = stringResource(R.string.capability_required_tag),
        style = MaterialTheme.typography.labelLarge,
        color = palette.onInertContainer,
        modifier = Modifier
            .background(palette.inertContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Label on the left, value on the right. Used by Test Mode's live readouts. */
@Composable
fun LabeledValueRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "$label: $value"
        },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

// ---------------------------------------------------------------------------
// Severity / state → icon + colour
// ---------------------------------------------------------------------------

@Composable
fun AuditSeverity.icon(): ImageVector = when (this) {
    AuditSeverity.INFO -> Icons.Filled.Info
    AuditSeverity.WARNING -> Icons.Filled.Warning
    AuditSeverity.ERROR -> Icons.Filled.Error
}

@Composable
fun AuditSeverity.tint(): Color = with(PriorityRingerTheme.status) {
    when (this@tint) {
        AuditSeverity.INFO -> MaterialTheme.colorScheme.primary
        AuditSeverity.WARNING -> degraded
        AuditSeverity.ERROR -> inert
    }
}

@Composable
fun AuditSeverity.containerColor(): Color = with(PriorityRingerTheme.status) {
    when (this@containerColor) {
        AuditSeverity.INFO -> MaterialTheme.colorScheme.secondaryContainer
        AuditSeverity.WARNING -> degradedContainer
        AuditSeverity.ERROR -> inertContainer
    }
}

@Composable
fun AuditSeverity.onContainerColor(): Color = with(PriorityRingerTheme.status) {
    when (this@onContainerColor) {
        AuditSeverity.INFO -> MaterialTheme.colorScheme.onSecondaryContainer
        AuditSeverity.WARNING -> onDegradedContainer
        AuditSeverity.ERROR -> onInertContainer
    }
}

@Composable
fun CapabilityStatus.State.icon(): ImageVector = when (this) {
    CapabilityStatus.State.GRANTED -> Icons.Filled.CheckCircle
    CapabilityStatus.State.DENIED -> Icons.Filled.Error
    CapabilityStatus.State.RESTRICTED -> Icons.Filled.Block
    CapabilityStatus.State.NOT_REQUIRED -> Icons.Filled.RemoveCircleOutline
}

@Composable
fun CapabilityStatus.State.tint(): Color = with(PriorityRingerTheme.status) {
    when (this@tint) {
        CapabilityStatus.State.GRANTED -> armed
        CapabilityStatus.State.DENIED -> inert
        CapabilityStatus.State.RESTRICTED -> degraded
        CapabilityStatus.State.NOT_REQUIRED -> MaterialTheme.colorScheme.outline
    }
}
