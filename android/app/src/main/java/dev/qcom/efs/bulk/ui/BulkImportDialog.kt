package dev.qcom.efs.bulk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.qcom.efs.BulkResult
import dev.qcom.efs.BulkState
import dev.qcom.efs.bulk.BulkOp

/**
 * The bulk-import flow: preview (with the read-only and SPC guards), running
 * progress with a per-command result list, and the done summary with the
 * modem-restart button.  A dumb view of [BulkState]; the view model owns the
 * transitions.
 */
@Composable
fun BulkImportDialog(
    state: BulkState,
    readOnly: Boolean,
    onSpcUnlock: (String) -> Unit,
    onStart: (String) -> Unit,
    onSsr: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (state !is BulkState.Running) onDismiss() },
        title = { Text("Bulk import") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    is BulkState.Preview -> PreviewBody(state, readOnly, onStart, onSpcUnlock)
                    is BulkState.Running -> RunningBody(state)
                    is BulkState.Done -> DoneBody(state, onSsr)
                }
            }
        },
        confirmButton = {
            if (state !is BulkState.Running) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun PreviewBody(
    state: BulkState.Preview,
    readOnly: Boolean,
    onStart: (String) -> Unit,
    onSpcUnlock: (String) -> Unit,
) {
    var spc by remember { mutableStateOf("000000") }

    Text(state.fileName, style = MaterialTheme.typography.titleSmall)

    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
    } ?: run {
        Text("${state.commands.size} commands parsed")
        val writes = state.commands.count { it.op == BulkOp.WRITE }
        Text(
            "$writes writes, ${state.commands.size - writes} deletes",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (readOnly) {
        HorizontalDivider()
        Text(
            "The daemon is in read-only mode and refuses every write.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { onSpcUnlock("") }) { Text("I understand - enable writes") }
    }

    if (!readOnly && state.commands.isNotEmpty()) {
        HorizontalDivider()
        Text(
            "The modem rejects writes until it is unlocked with the Service " +
                    "Programming Code (often 000000).",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = spc,
            onValueChange = { spc = it.filter(Char::isDigit).take(6) },
            label = { Text("SPC") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        TextButton(onClick = { onSpcUnlock(spc) }, enabled = spc.length == 6) {
            Text("Test the SPC")
        }
        Button(
            onClick = { onStart(spc) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start import") }
    }
}

@Composable
private fun RunningBody(state: BulkState.Running) {
    Text("Importing… ${state.done} / ${state.commands.size}")
    LinearProgressIndicator(
        progress = { if (state.commands.isEmpty()) 0f else state.done.toFloat() / state.commands.size },
        modifier = Modifier.fillMaxWidth(),
    )
    ResultList(state.results)
}

@Composable
private fun DoneBody(state: BulkState.Done, onSsr: () -> Unit) {
    val ok = state.results.count { it.ok }
    val fail = state.results.size - ok
    Text(
        "Done — $ok OK, $fail FAIL",
        color = if (fail == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    state.summary?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (state.results.any { it.ok }) {
        Text(
            "Changes reach the running modem only after a restart.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (fail == 0) {
        Button(onClick = onSsr, modifier = Modifier.fillMaxWidth()) {
            Text("Restart modem (SSR)")
        }
    }
    ResultList(state.results)
}

@Composable
private fun ResultList(results: List<BulkResult>) {
    if (results.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(results.size) {
        if (results.isNotEmpty()) listState.scrollToItem(results.size - 1)
    }
    LazyColumn(Modifier.heightIn(max = 240.dp)) {
        items(results) { r ->
            val label = buildString {
                append(if (r.op == BulkOp.WRITE) "W" else "D")
                append(" ")
                append(r.simTag)
                append(" ")
                append(r.path.substringAfterLast('/'))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                )
                Text(
                    if (r.ok) "OK" else "FAIL",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            r.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
        }
    }
}
