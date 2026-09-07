package dev.qcom.efs.features.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.qcom.efs.FeaturesState
import dev.qcom.efs.features.FeatureStatus
import dev.qcom.efs.features.ALL_FEATURES

private const val DEFAULT_SPC = "000000"

@Composable
fun FeaturesDialog(
    state: FeaturesState,
    readOnly: Boolean,
    busy: Boolean,
    onSimSlot: (Int) -> Unit,
    onEnableWrites: () -> Unit,
    onDisable: (id: String, spc: String) -> Unit,
    onSuppressDisableWarning: () -> Unit,
    onSsr: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is FeaturesState.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Disable features") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(4.dp),
                        strokeWidth = 3.dp,
                    )
                    Text("Reading feature state from the modem…")
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Close") }
            },
        )

        is FeaturesState.Ready -> ReadyBody(
            state = state,
            busy = busy,
            readOnly = readOnly,
            onSimSlot = onSimSlot,
            onEnableWrites = onEnableWrites,
            onDisable = onDisable,
            onSuppressDisableWarning = onSuppressDisableWarning,
            onSsr = onSsr,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ReadyBody(
    state: FeaturesState.Ready,
    busy: Boolean,
    readOnly: Boolean,
    onSimSlot: (Int) -> Unit,
    onEnableWrites: () -> Unit,
    onDisable: (String, String) -> Unit,
    onSuppressDisableWarning: () -> Unit,
    onSsr: () -> Unit,
    onDismiss: () -> Unit,
) {
    val acting = state.statuses.values.any { it is FeatureStatus.Writing }
    var pendingDisable by remember { mutableStateOf<Pair<String, String>?>(null) }
    var suppressAfterDisable by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!acting) onDismiss() },
        title = { Text("Disable features") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "SIM 0", 1 to "SIM 1").forEach { (slot, label) ->
                        FilterChip(
                            selected = state.simSlot == slot,
                            enabled = !acting,
                            onClick = { onSimSlot(slot) },
                            label = { Text(label) },
                        )
                    }
                }
                if (readOnly) {
                    HorizontalDivider()
                    Text(
                        text = "Read-only is on. Disable it to modify feature items.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onEnableWrites) {
                        Text("I understand - enable writes")
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(ALL_FEATURES, key = { it.id }) { feature ->
                        val status = state.statuses[feature.id]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = feature.label.removePrefix("Disable "),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (status is FeatureStatus.WriteError || status is FeatureStatus.ReadError) {
                                    Text(
                                        text = when (status) {
                                            is FeatureStatus.WriteError -> status.message
                                            is FeatureStatus.ReadError -> status.message
                                            else -> ""
                                        },
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            val canDisable =
                                (status is FeatureStatus.CanDisable || status is FeatureStatus.WriteError) &&
                                    !readOnly && !acting
                            if (status is FeatureStatus.Writing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(4.dp),
                                    strokeWidth = 3.dp,
                                )
                            } else {
                                Switch(
                                    checked = status is FeatureStatus.AlreadyDisabled,
                                    onCheckedChange = { on ->
                                        if (on) {
                                            if (state.warnBeforeDisable) {
                                                pendingDisable = feature.id to DEFAULT_SPC
                                            } else {
                                                onDisable(feature.id, DEFAULT_SPC)
                                            }
                                        }
                                    },
                                    enabled = canDisable,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                state.note?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Changes reach the modem after a restart. On Xiaomi devices you can trigger it below; otherwise toggle airplane mode or reboot.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { onSsr() },
                    enabled = !busy && !acting && !readOnly,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restart modem (SSR)") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !acting) { Text("Close") }
        },
    )

    pendingDisable?.let { (id, spc) ->
        AlertDialog(
            onDismissRequest = {
                suppressAfterDisable = false
                pendingDisable = null
            },
            title = { Text("Disable this feature?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This writes directly to the modem's EFS and can alter modem " +
                                "behavior. Take an EFS backup first (overflow menu → backups).",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = suppressAfterDisable,
                            onCheckedChange = { suppressAfterDisable = it },
                        )
                        Text("Don't warn me again")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val suppress = suppressAfterDisable
                    suppressAfterDisable = false
                    pendingDisable = null
                    if (suppress) onSuppressDisableWarning()
                    onDisable(id, spc)
                }) { Text("Turn off") }
            },
            dismissButton = {
                TextButton(onClick = {
                    suppressAfterDisable = false
                    pendingDisable = null
                }) { Text("Cancel") }
            },
        )
    }
}
