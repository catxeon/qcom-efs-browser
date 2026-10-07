package dev.qcom.efs

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import dev.qcom.efs.bulk.ui.BulkImportDialog
import dev.qcom.efs.features.ui.FeaturesDialog

private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

/** The helper answers inline reads only up to this size; larger files are save-only. */

internal fun time(v: Int): String =
    if (v <= 0) "-" else stamp.format(Date(v.toLong() * 1000L))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsState()

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

    val bulkImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.startBulkImport(uri) }

    val ctx = LocalContext.current
    LaunchedEffect(state.exitAfterDisconnect) {
        if (state.exitAfterDisconnect) (ctx as? Activity)?.finish()
    }
    LaunchedEffect(state.pendingExport) {
        state.pendingExport?.let { exporter.launch(it.suggestedName) }
    }
    // A platform toast rather than a Scaffold snackbar on purpose: a snackbar
    // lives in the activity's own window, so an open dialog draws over it and
    // dims it with its scrim, and the keyboard covers it outright.  The system
    // draws a toast above both, which is the whole point of these messages.
    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.dismissToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (state.searchActive) {
                        SearchField(query = state.searchQuery, onQuery = vm::setSearchQuery)
                    } else {
                        Column {
                            Text("Qualcomm EFS", maxLines = 1)
                            state.info?.let {
                                Text(
                                    "helper ${it.version} · subsys 0x${it.subsys.toString(16)}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (state.searchActive) {
                        IconButton(onClick = { vm.closeSearch() }) {
                            Icon(Icons.Filled.Close, "Close search")
                        }
                    } else if (state.phase == Phase.READY && state.path != "/") {
                        IconButton(onClick = { vm.up() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                        }
                    }
                },
                actions = {
                    // While the search field owns the bar, every other action
                    // hides; the Close button in the navigation slot exits it.
                    if (!state.searchActive) {
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
                            IconButton(onClick = { vm.openSearch() }) { Icon(Icons.Filled.Search, "Search") }
                            var sortMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortKey.entries.forEach { key ->
                                    val active = state.sortKey == key
                                    DropdownMenuItem(
                                        text = {
                                            Text(sortLabel(key, if (active) state.sortDescending else key.defaultDescending))
                                        },
                                        leadingIcon = {
                                            if (active) Icon(Icons.Filled.Check, null) else null
                                        },
                                        onClick = { sortMenu = false; vm.chooseSort(key) },
                                    )
                                }
                            }
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
                                    text = { Text("Bulk import") },
                                    leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                                    onClick = {
                                        menu = false
                                        bulkImporter.launch(arrayOf("text/plain", "application/json"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Disable features") },
                                    leadingIcon = { Icon(Icons.Filled.Tune, null) },
                                    onClick = { menu = false; vm.openFeatures() },
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
                                // Greyed out under the read-only lock rather than
                                // hidden: the helper would refuse it anyway, and
                                // its own refusal reads as protocol, not English.
                                DropdownMenuItem(
                                    text = { Text("Restart modem") },
                                    leadingIcon = { Icon(Icons.Filled.RestartAlt, null) },
                                    enabled = !state.readOnly,
                                    onClick = { menu = false; vm.modemSsr() },
                                )
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = { Text("Diagnostics") },
                                leadingIcon = { Icon(Icons.Filled.BugReport, null) },
                                onClick = { menu = false; vm.refreshLog(); showLog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Check for updates") },
                                leadingIcon = { Icon(Icons.Filled.SystemUpdate, null) },
                                onClick = { menu = false; vm.checkForUpdates(manual = true) },
                            )
                            if (state.phase == Phase.READY) {
                                // Always offered: the app cannot reliably tell a
                                // dead session from a live one, and a restart of
                                // a healthy helper costs next to nothing.
                                DropdownMenuItem(
                                    text = { Text("Reconnect") },
                                    leadingIcon = { Icon(Icons.Filled.Link, null) },
                                    onClick = { menu = false; vm.reconnect() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Disconnect") },
                                    leadingIcon = { Icon(Icons.Filled.PowerSettingsNew, null) },
                                    onClick = { menu = false; vm.disconnect() },
                                )
                            }
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
            maxInline = state.info?.maxInline ?: DEFAULT_MAX_INLINE,
            preview = state.preview,
            previewLoading = state.previewLoading,
            previewError = state.previewError,
            readOnly = state.readOnly,
            onDismiss = { vm.closeDetail() },
            onLoadContent = { vm.preview(detail.path) },
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

    state.editor?.let { ed ->
        EditorDialog(ed, state.busy, onSave = { vm.saveEditor(it) }, onDismiss = { vm.closeEditor() })
    }

    if (showLog) LogDialog(state, onRefresh = { vm.refreshLog() }) { showLog = false }
    if (showNv) NvDialog(state, vm) { showNv = false }
    if (showRaw) RawDialog(vm) { showRaw = false }

    state.update?.let { release ->
        UpdateDialog(
            release = release,
            onDownload = {
                vm.dismissUpdate()
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(release.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onSkip = { vm.skipUpdate() },
            onDismiss = { vm.dismissUpdate() },
        )
    }

    state.bulk?.let { bulk ->
        BulkImportDialog(
            state = bulk,
            readOnly = state.readOnly,
            busy = state.busy,
            ssrDone = state.ssrDone,
            onSpcUnlock = { spc -> vm.spcUnlock(spc) },
            onEnableWrites = { vm.toggleReadOnly() },
            onStart = { spc -> vm.runBulkImport(spc) },
            onSsr = { vm.modemSsr() },
            onDismiss = { vm.closeBulkImport() },
        )
    }

    state.features?.let { features ->
        FeaturesDialog(
            state = features,
            readOnly = state.readOnly,
            busy = state.busy,
            ssrDone = state.ssrDone,
            onSimSlot = { slot -> vm.setFeatureSimSlot(slot) },
            onEnableWrites = { vm.toggleReadOnly() },
            onDisable = { id, spc -> vm.disableFeature(id, spc) },
            onSuppressDisableWarning = { vm.suppressDisableWarning() },
            onSsr = { vm.modemSsr() },
            onDismiss = { vm.closeFeatures() },
        )
    }

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
            initialName = remember(uri) { vm.displayName(uri) },
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

/**
 * The top-bar search field.  Transparent container and indicators so it
 * reads as part of the app bar; focused on entry so typing can start at once.
 */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        singleLine = true,
        placeholder = { Text("Search this folder") },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = {
                    onQuery("")
                    focus.requestFocus()
                }) { Icon(Icons.Filled.Clear, "Clear") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

private fun sortLabel(key: SortKey, descending: Boolean): String = when (key) {
    SortKey.NAME -> if (descending) "Name Z→A" else "Name A→Z"
    SortKey.SIZE -> if (descending) "Size largest first" else "Size smallest first"
    SortKey.DATE -> if (descending) "Date newest first" else "Date oldest first"
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

    // The system back gesture closes an active search first, then walks up
    // the tree exactly like the arrow in the toolbar.  At the root there is
    // nowhere left to go, so it offers the exit -- which closes the session
    // rather than leaving the helper behind.
    BackHandler {
        when {
            state.searchActive -> vm.closeSearch()
            state.path != "/" -> vm.up()
            else -> confirmExit = true
        }
    }

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

    // The visible list derives from the repository's listing: name filter,
    // then the chosen sort.  The repository's own dirs-first order stays the
    // data source; this only reorders (and hides) entries.
    val visible = remember(state.entries, state.searchQuery, state.sortKey, state.sortDescending) {
        state.entries.filterByName(state.searchQuery).sortedBy(state.sortKey, state.sortDescending)
    }

    // Typing shrinks the list and a new sort reshuffles it; either way the
    // answer the user is looking for is at the top, so start there rather
    // than leaving them wherever the old order had them scrolled to.
    LaunchedEffect(state.searchQuery, state.sortKey, state.sortDescending) {
        listState.scrollToItem(0)
    }

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

        // A filter can hide every entry of a non-empty directory -- that is a
        // different situation from the modem reporting an empty directory,
        // so it gets its own message.
        if (visible.isEmpty() && !state.busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matches for “${state.searchQuery}”", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(visible, key = { it.name }) { entry ->
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
