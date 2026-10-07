package dev.qcom.efs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import dev.qcom.efs.update.Release

/* The dialogs the browser puts on top of itself. */

/** Offers the newer release found on GitHub; "Skip" silences it for that version only. */
@Composable
internal fun UpdateDialog(
    release: Release,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.SystemUpdate, null) },
        title = { Text("Version ${release.version} is out") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A newer release is available on GitHub.")
                if (release.notes.isNotBlank()) {
                    HorizontalDivider()
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        Text(release.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("Open release") } },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) { Text("Skip") }
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        },
    )
}

/**
 * The built-in editor: text for configuration files, hex for item files and for
 * anything else that does not read as text.  The two views are two ways of
 * typing the same bytes, so switching between them carries the content across.
 *
 * The file is written back as the kind of object it already was -- an item file
 * stays an item file, and the mode is the one it had.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorDialog(
    data: EditorData,
    busy: Boolean,
    onSave: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val charset = remember(data) { textCharset(data.original) }
    var asText by remember(data) { mutableStateOf(data.startAsText) }
    var text by remember(data) { mutableStateOf(String(data.original, charset)) }
    var hex by remember(data) { mutableStateOf(hexText(data.original)) }
    var error by remember(data) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember(data) { mutableStateOf(false) }

    // What the current view would write, or why it cannot be written at all.
    val parsed = remember(asText, text, hex, charset) {
        if (asText) Result.success(text.toByteArray(charset)) else runCatching { parseHexText(hex) }
    }
    val bytes = parsed.getOrNull()
    val why = parsed.exceptionOrNull()?.message ?: "this is not valid hex"
    val dirty = bytes == null || !bytes.contentEquals(data.original)
    val resized = bytes != null && bytes.size != data.original.size

    fun leave() { if (dirty) confirmDiscard = true else onDismiss() }

    fun switchTo(wantText: Boolean) {
        if (wantText == asText) return
        val b = bytes
        if (b == null) { error = why; return }
        if (wantText) text = String(b, charset) else hex = hexText(b)
        asText = wantText
        error = null
    }

    Dialog(
        onDismissRequest = { leave() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                data.path.substringAfterLast('/'),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                data.path,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { leave() }) { Icon(Icons.Filled.Close, "Close") }
                    },
                    actions = {
                        TextButton(
                            onClick = { bytes?.let(onSave) },
                            enabled = bytes != null && dirty && !busy,
                        ) { Text("Save") }
                    },
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(selected = asText, onClick = { switchTo(true) }, label = { Text("Text") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !asText, onClick = { switchTo(false) }, label = { Text("Hex") })
                    Spacer(Modifier.weight(1f))
                    Text(
                        when {
                            bytes == null -> why
                            resized -> "${data.original.size} → ${bytes.size} B"
                            asText -> "${bytes.size} B · ${charset.name()}"
                            else -> "${bytes.size} B"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bytes == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // An item file is read by the modem at fixed offsets, so a
                // changed length is worth saying out loud before it is written.
                if (data.isItem && resized) {
                    Text(
                        "This item file was ${data.original.size} bytes and the modem may " +
                                "expect exactly that many.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedTextField(
                    value = if (asText) text else hex,
                    onValueChange = { v ->
                        if (asText) text = v else hex = v
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(12.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = if (asText) KeyboardType.Text else KeyboardType.Ascii,
                    ),
                )
            }
        }

        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("Discard changes?") },
                text = { Text("${data.path} has not been written to the modem.") },
                confirmButton = {
                    TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("Discard") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
                },
            )
        }
    }
}

@Composable
internal fun LogDialog(state: UiState, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val log = state.log
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Text(modeLine(state), style = MaterialTheme.typography.labelLarge)
                state.info?.let {
                    Text(
                        "transport ${it.transport}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        if (log.isEmpty()) "No output yet." else log.takeLast(300).joinToString("\n"),
                        Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onRefresh) { Text("Reload") } },
    )
}

/** Reported, never changed: the helper leaves the policy exactly as it found it. */
private fun modeLine(state: UiState): String =
    "SELinux: " + RootDaemon.modeName(state.localEnforce) + " (untouched)"

@Composable
internal fun NvDialog(state: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    var item by remember { mutableStateOf("") }
    var index by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }
    var spc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NV items") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = item,
                        onValueChange = { item = it.filter(Char::isDigit) },
                        label = { Text("Item") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = index,
                        onValueChange = { index = it.filter(Char::isDigit) },
                        label = { Text("Index") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = { item.toIntOrNull()?.let { vm.nvRead(it, index.toIntOrNull()) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Read") }

                state.nvError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.nv?.let { nv ->
                    Text("status ${nv.status} (${nv.statusText})", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(
                            nv.hex.chunked(32).joinToString("\n"),
                            Modifier
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    TextButton(onClick = { payload = nv.hex }) { Text("Copy into the write field") }
                }

                if (!state.readOnly) {
                    HorizontalDivider()
                    // Modems refuse NV writes until the Service Programming Code
                    // is accepted; it raises the DIAG access level for the
                    // session and does not itself change anything.
                    Text(
                        "The modem rejects NV writes until it is unlocked with the " +
                                "Service Programming Code (often 000000).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = spc,
                            onValueChange = { spc = it.filter(Char::isDigit).take(6) },
                            label = { Text("SPC") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { vm.spcUnlock(spc) },
                            enabled = spc.length == 6 && !state.busy,
                        ) { Text("Unlock") }
                    }

                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it },
                        label = { Text("Data to write (hex, up to 128 bytes)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            item.toIntOrNull()?.let { vm.nvWrite(it, payload, index.toIntOrNull()) }
                        },
                        enabled = payload.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Write NV item") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun RawDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var hex by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    // Dismissing the keyboard with Back hides it but leaves the field focused,
    // so sending would recompose and the still-focused field would ask for the
    // keyboard again.  Dropping focus on send keeps it down.
    val focus = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raw DIAG packet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Bytes are sent unframed; the helper adds HDLC and the CRC.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text("Request (hex)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { focus.clearFocus(); vm.rawSend(hex) { answer = it } },
                    enabled = hex.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send") }
                if (answer.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            answer.chunked(48).joinToString("\n"),
                            Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Asks for the name and, just as importantly, the type.  An EFS item file is
 * a different kind of object from an ordinary file -- it is stored through the
 * item interface and carries mode 0160xxx -- and the modem cares which one it
 * gets.  The path only hints at the answer, so the choice is explicit.
 */
@Composable
internal fun ImportDialog(
    dir: String,
    initialName: String,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    // Pre-select what the destination path suggests, but let it be overridden.
    var asItem by remember(name) { mutableStateOf(Paths.looksLikeItemPath(Paths.child(dir, name))) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write into $dir") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("File name") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )

                Text("Store as", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !asItem,
                        onClick = { asItem = false },
                        label = { Text("Regular file") },
                    )
                    FilterChip(
                        selected = asItem,
                        onClick = { asItem = true },
                        label = { Text("Item file") },
                    )
                }
                Text(
                    if (asItem)
                        "Written through the item interface, the way the modem expects " +
                                "entries under /nv/item_files to be stored."
                    else
                        "An ordinary file, written with open/write/close and the mode you asked for.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, asItem) }) { Text("Write") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
