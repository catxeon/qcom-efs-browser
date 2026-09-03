package dev.qcom.efs

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

private fun time(v: Int): String =
    if (v <= 0) "-" else stamp.format(Date(v.toLong() * 1000L))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var showLog by remember { mutableStateOf(false) }
    var showNv by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var showMkdir by remember { mutableStateOf(false) }
    var chmodTarget by remember { mutableStateOf<Detail?>(null) }
    var confirmDelete by remember { mutableStateOf<Detail?>(null) }
    var importTarget by remember { mutableStateOf<String?>(null) }
    var importAsItem by remember { mutableStateOf<Boolean?>(null) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> vm.completeExport(uri) }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            importTarget = null
        } else {
            val target = importTarget
            if (target != null) {
                importTarget = null
                vm.importInto(uri, target, importAsItem)
            } else {
                pendingImport = uri
            }
        }
    }

    val ctx = LocalContext.current
    LaunchedEffect(state.exitAfterDisconnect) {
        if (state.exitAfterDisconnect) (ctx as? Activity)?.finish()
    }
    LaunchedEffect(state.pendingExport) {
        state.pendingExport?.let { exporter.launch(it.suggestedName) }
    }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.dismissToast()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Qualcomm EFS", maxLines = 1)
                        state.info?.let {
                            Text(
                                "helper ${it.version} · subsys 0x${it.subsys.toString(16)}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (state.phase == Phase.READY && state.path != "/") {
                        IconButton(onClick = { vm.up() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                        }
                    }
                },
                actions = {
                    if (state.phase == Phase.READY) {
                        IconButton(onClick = { vm.toggleReadOnly() }) {
                            Icon(
                                if (state.readOnly) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                contentDescription = "Read-only",
                                tint = if (state.readOnly) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    }
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (state.phase == Phase.READY) {
                            DropdownMenuItem(
                                text = { Text("Backup this folder (modem tar)") },
                                leadingIcon = { Icon(Icons.Filled.Archive, null) },
                                onClick = { menu = false; vm.requestImageBackup(state.path) },
                            )
                            DropdownMenuItem(
                                text = { Text("Backup this folder (file by file)") },
                                leadingIcon = { Icon(Icons.Filled.Archive, null) },
                                onClick = { menu = false; vm.requestTreeBackup(state.path) },
                            )
                            DropdownMenuItem(
                                text = { Text("NV items") },
                                leadingIcon = { Icon(Icons.Filled.Memory, null) },
                                onClick = { menu = false; showNv = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Raw DIAG packet") },
                                leadingIcon = { Icon(Icons.Filled.Code, null) },
                                onClick = { menu = false; showRaw = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Flush EFS journal") },
                                leadingIcon = { Icon(Icons.Filled.Sync, null) },
                                onClick = { menu = false; vm.sync() },
                            )
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text("Diagnostics") },
                            leadingIcon = { Icon(Icons.Filled.BugReport, null) },
                            onClick = { menu = false; vm.refreshLog(); showLog = true },
                        )
                        if (state.phase == Phase.READY) {
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                leadingIcon = { Icon(Icons.Filled.PowerSettingsNew, null) },
                                onClick = { menu = false; vm.disconnect() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.phase == Phase.READY && !state.readOnly) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallFloatingActionButton(onClick = { showMkdir = true }) {
                        Icon(Icons.Filled.CreateNewFolder, "New folder")
                    }
                    FloatingActionButton(onClick = { importTarget = null; importer.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.Upload, "Upload a file")
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.phase) {
                Phase.READY -> BrowserScreen(state, vm)
                else -> ConnectScreen(state, vm)
            }
            if (state.busy) {
                Column(Modifier.align(Alignment.TopCenter)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.busyLabel?.let {
                        Text(
                            it,
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    state.detail?.let { detail ->
        DetailSheet(
            detail = detail,
            readOnly = state.readOnly,
            onDismiss = { vm.closeDetail() },
            onPreview = { vm.preview(detail.path) },
            onEdit = { vm.edit(detail) },
            onExport = { vm.requestExport(detail.path) },
            onReplace = {
                importTarget = detail.path
                importAsItem = detail.entry.isItem
                importer.launch(arrayOf("*/*"))
            },
            onChmod = { chmodTarget = detail },
            onDelete = { confirmDelete = detail },
        )
    }

    state.preview?.let { PreviewDialog(it) { vm.closePreview() } }
    state.editor?.let { ed ->
        EditorDialog(ed, state.busy, onSave = { vm.saveEditor(it) }, onDismiss = { vm.closeEditor() })
    }

    if (showLog) LogDialog(state, onRefresh = { vm.refreshLog() }) { showLog = false }
    if (showNv) NvDialog(state, vm) { showNv = false }
    if (showRaw) RawDialog(vm) { showRaw = false }

    if (showMkdir) {
        TextPromptDialog(
            title = "New folder in ${state.path}",
            label = "Name",
            initial = "",
            onConfirm = { showMkdir = false; if (it.isNotBlank()) vm.createDir(it.trim()) },
            onDismiss = { showMkdir = false },
        )
    }

    chmodTarget?.let { d ->
        TextPromptDialog(
            title = "Permissions for ${d.entry.name}",
            label = "Octal mode",
            initial = (d.entry.mode and 0xFFF).toString(8),
            onConfirm = { text ->
                chmodTarget = null
                text.trim().toIntOrNull(8)?.let { vm.chmod(d.path, it) }
            },
            onDismiss = { chmodTarget = null },
        )
    }

    pendingImport?.let { uri ->
        ImportDialog(
            dir = state.path,
            initialName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?: "file",
            onConfirm = { name, asItem ->
                pendingImport = null
                if (name.isNotBlank()) {
                    vm.importInto(uri, Paths.child(state.path, name.trim()), asItem)
                }
            },
            onDismiss = { pendingImport = null },
        )
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Filled.Warning, null) },
            title = { Text("Delete ${d.entry.name}?") },
            text = {
                Text(
                    if (d.entry.isDir)
                        "The whole subtree under ${d.path} is removed from the modem. " +
                                "This cannot be undone and may make the modem unusable."
                    else
                        "${d.path} is removed from the modem. Calibration and provisioning " +
                                "files are not recoverable without a backup."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; vm.delete(d) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectScreen(state: UiState, vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Modem EFS browser", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The app talks to the Qualcomm modem over DIAG through a small root helper, " +
                    "which reaches it through the DIAG service on the QRTR bus. Root is " +
                    "required; nothing on the phone itself is modified, SELinux included.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state.error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Could not connect", style = MaterialTheme.typography.titleMedium)
                    Text(state.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.verbose, onCheckedChange = { vm.setVerbose(it) })
            Text("Verbose helper log")
        }


        Button(
            onClick = { vm.connect() },
            enabled = state.phase != Phase.CONNECTING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.phase == Phase.CONNECTING) "Connecting…" else "Grant root and connect")
        }

        if (state.log.isNotEmpty()) {
            Text("Helper output", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(
                    state.log.takeLast(40).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun BrowserScreen(state: UiState, vm: MainViewModel) {
    var confirmExit by remember { mutableStateOf(false) }

    // The system back gesture walks up the tree, exactly like the arrow in the
    // toolbar.  At the root there is nowhere left to go, so it offers the exit
    // -- which closes the session rather than leaving the helper behind.
    BackHandler { if (state.path != "/") vm.up() else confirmExit = true }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Leave the app?") },
            text = { Text("The modem session is closed and the root helper is stopped.") },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; vm.disconnectAndExit() }) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("Cancel") }
            },
        )
    }

    // A new directory is shown from its first entry, not at whatever offset the
    // previous one happened to be scrolled to.  Re-reading the same path (the
    // refresh button, a delete) keeps the position, since the key is unchanged.
    val listState = rememberLazyListState()
    LaunchedEffect(state.path) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Paths.crumbs(state.path).forEachIndexed { i, (label, target) ->
                if (i > 0) Text(" / ", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (label == "/") "efs" else label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (target == state.path) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { vm.open(target) }
                        .padding(vertical = 4.dp),
                )
            }
        }
        HorizontalDivider()

        if (state.entries.isEmpty() && !state.busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Empty directory", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(state.entries, key = { it.name }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(modeOctal(entry.mode))
                                if (!entry.isDir) append(" · ${humanSize(entry.size.toLong())}")
                                if (entry.mtime > 0) append(" · ${time(entry.mtime)}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingContent = {
                        Icon(
                            when {
                                entry.isDir -> Icons.Filled.Folder
                                entry.isLink -> Icons.Filled.Link
                                entry.isItem -> Icons.Filled.Memory
                                else -> Icons.Filled.Description
                            },
                            contentDescription = entry.type,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            if (entry.isDir) vm.openDetailForDir(entry) else vm.onEntryClicked(entry)
                        }) { Icon(Icons.Filled.Info, "Details") }
                    },
                    modifier = Modifier.clickable { vm.onEntryClicked(entry) },
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DetailSheet(
    detail: Detail,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onReplace: () -> Unit,
    onChmod: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(detail.entry.name, style = MaterialTheme.typography.titleLarge)
            SelectionContainer {
                Text(detail.path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }

            val st = detail.stat
            KeyValue("Type", detail.entry.type)
            KeyValue("Mode", modeOctal(st?.mode ?: detail.entry.mode))
            KeyValue("Size", humanSize((st?.size ?: detail.entry.size).toLong()))
            KeyValue("Modified", time(st?.mtime ?: detail.entry.mtime))
            KeyValue("Created", time(st?.ctime ?: detail.entry.ctime))
            st?.target?.let { KeyValue("Points at", it) }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            if (!detail.entry.isDir) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onPreview) {
                        Icon(Icons.Filled.Visibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("View")
                    }
                    OutlinedButton(onClick = onExport) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save…")
                    }
                }
            }
            if (!readOnly) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!detail.entry.isDir && !detail.entry.isLink) {
                        OutlinedButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Edit")
                        }
                    }
                    if (!detail.entry.isDir) {
                        OutlinedButton(onClick = onReplace) {
                            Icon(Icons.Filled.Upload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Replace")
                        }
                    }
                    OutlinedButton(onClick = onChmod) { Text("chmod") }
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, Modifier.width(110.dp), style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PreviewDialog(data: PreviewData, onDismiss: () -> Unit) {
    var asText by remember(data) { mutableStateOf(data.asText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(data.path.substringAfterLast('/'), maxLines = 1) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = asText, onClick = { asText = true }, label = { Text("Text") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !asText, onClick = { asText = false }, label = { Text("Hex") })
                    Spacer(Modifier.weight(1f))
                    Text(humanSize(data.bytes.size.toLong()), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    // Hex rows must not wrap, so that column gets its own
                    // horizontal scroll; text is easier to read wrapped.
                    val base = Modifier.verticalScroll(rememberScrollState())
                    Text(
                        if (asText) String(data.bytes, Charsets.ISO_8859_1) else hexDump(data.bytes),
                        if (asText) base else base.horizontalScroll(rememberScrollState()),
                        softWrap = asText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
private fun EditorDialog(
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

private fun hexDump(bytes: ByteArray, limit: Int = 8192): String = buildString {
    val n = minOf(bytes.size, limit)
    var i = 0
    while (i < n) {
        append(String.format("%08x  ", i))
        for (j in 0 until 16) {
            if (i + j < n) append(String.format("%02x ", bytes[i + j])) else append("   ")
            if (j == 7) append(' ')
        }
        append(" |")
        for (j in 0 until 16) {
            if (i + j >= n) break
            val c = bytes[i + j].toInt() and 0xFF
            append(if (c in 32..126) c.toChar() else '.')
        }
        append("|\n")
        i += 16
    }
    if (bytes.size > limit) append("… ${bytes.size - limit} more bytes\n")
}

@Composable
private fun LogDialog(state: UiState, onRefresh: () -> Unit, onDismiss: () -> Unit) {
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
private fun NvDialog(state: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
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
private fun RawDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var hex by remember { mutableStateOf("4b1300000000") }
    var answer by remember { mutableStateOf("") }

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
                    onClick = { vm.rawSend(hex) { answer = it } },
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
private fun ImportDialog(
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
private fun TextPromptDialog(
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
