package dev.qcom.efs

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.qcom.efs.bulk.BulkCommand
import dev.qcom.efs.bulk.BulkOp
import dev.qcom.efs.bulk.NvImportParseException
import dev.qcom.efs.bulk.NvImportParser
import dev.qcom.efs.features.EfsFeatureAccess
import dev.qcom.efs.features.FeatureChecker
import dev.qcom.efs.features.FeatureDef
import dev.qcom.efs.features.FeatureStatus
import dev.qcom.efs.features.ALL_FEATURES
import dev.qcom.efs.update.Release
import dev.qcom.efs.update.UpdateChecker
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Phase { DISCONNECTED, CONNECTING, READY, FAILED }

data class Detail(val entry: EfsEntry, val path: String, val stat: EfsStat? = null)

data class PreviewData(val path: String, val bytes: ByteArray, val asText: Boolean) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** A file staged in the cache, waiting for the user to pick a destination. */
data class PendingExport(val file: File, val suggestedName: String, val mime: String)

/**
 * A file open in the editor.  [original] is what was read from the modem: the
 * editor compares against it to know whether anything was actually changed, and
 * [mode] and [isItem] are carried along so that saving writes the file back as
 * the same kind of object it already was.
 */
data class EditorData(
    val path: String,
    val original: ByteArray,
    val mode: Int,
    val isItem: Boolean,
    val startAsText: Boolean,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** Anything larger is better edited on a computer than in a text field. */
private const val MAX_EDIT = 64 * 1024

/** Preference key holding the one release the user chose not to be reminded about. */
private const val KEY_SKIPPED_VERSION = "skipped_version"

/** SIM slot the Disable-features dialog opens on. */
private const val DEFAULT_FEATURE_SLOT = 0

/** Preference key recording that the feature-disable warning is off for good. */
private const val KEY_DISABLE_WARNING_SUPPRESSED = "disable_warning_suppressed"

/** One executed bulk command, shown in the dialog's result list. */
data class BulkResult(
    val op: BulkOp,
    val path: String,
    val simTag: String,
    val ok: Boolean,
    val error: String? = null,
)

sealed interface BulkState {
    /** File picked and parsed; the import has not started yet. */
    data class Preview(
        val fileName: String,
        val commands: List<BulkCommand>,
        val error: String? = null,
        /** Feedback that would otherwise go to the snackbar behind the dialog. */
        val note: String? = null,
    ) : BulkState

    /** Sequential execution in progress; [results] has [done] entries. */
    data class Running(
        val fileName: String,
        val commands: List<BulkCommand>,
        val done: Int,
        val results: List<BulkResult>,
    ) : BulkState

    /** Finished. [summary] is a non-fatal note (e.g. a failed journal flush). */
    data class Done(
        val results: List<BulkResult>,
        val summary: String? = null,
        /** Feedback that would otherwise go to the snackbar behind the dialog. */
        val note: String? = null,
    ) : BulkState
}

/** State of the Disable-features dialog. */
sealed interface FeaturesState {
    data class Checking(val simSlot: Int) : FeaturesState
    data class Ready(
        val simSlot: Int,
        val statuses: Map<String, FeatureStatus>,
        /** True until the user ticks "Don't warn me again" (one-way, persisted). */
        val warnBeforeDisable: Boolean,
        val note: String? = null,
    ) : FeaturesState
}

data class UiState(
    val phase: Phase = Phase.DISCONNECTED,
    val error: String? = null,
    val info: DaemonInfo? = null,
    val path: String = "/",
    val entries: List<EfsEntry> = emptyList(),
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val readOnly: Boolean = true,
    val verbose: Boolean = false,
    val localEnforce: Int? = null,
    val log: List<String> = emptyList(),
    val toast: String? = null,
    val detail: Detail? = null,
    val preview: PreviewData? = null,
    val editor: EditorData? = null,
    val pendingExport: PendingExport? = null,
    val nv: NvResult? = null,
    val nvError: String? = null,
    /** Non-null while the bulk-import dialog is open. */
    val bulk: BulkState? = null,
    val features: FeaturesState? = null,
    /** Non-null while the "new version available" dialog is open. */
    val update: Release? = null,
    /** Set once the session is closed and the activity should finish. */
    val exitAfterDisconnect: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = EfsRepository(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("updates", Context.MODE_PRIVATE)
    }

    /** The installed versionName, read from the package rather than BuildConfig. */
    private val installedVersion: String by lazy {
        val app = getApplication<Application>()
        runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "0"
    }

    // Must stay below every property it touches: viewModelScope dispatches on
    // Main.immediate, so the check runs synchronously during construction and
    // a lazy declared further down would still be null.
    init {
        checkForUpdates(manual = false)
    }

    // ---- plumbing ------------------------------------------------------

    private fun work(label: String? = null, body: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = label) }
            try {
                body()
            } catch (t: Throwable) {
                _state.update { it.copy(toast = describe(t)) }
            } finally {
                _state.update { it.copy(busy = false, busyLabel = null) }
            }
        }
    }

    private fun describe(t: Throwable): String {
        val base = t.message ?: t.javaClass.simpleName
        val errno = (t as? EfsException)?.efsErrno
        return if (errno != null && errno != 0) "$base (${errnoName(errno)})" else base
    }

    fun dismissToast() = _state.update { it.copy(toast = null) }
    fun setVerbose(on: Boolean) = _state.update { it.copy(verbose = on) }

    /** The picker's own name for a chosen file, for pre-filling the import dialog. */
    fun displayName(uri: Uri): String = repo.displayName(uri)

    // ---- session -------------------------------------------------------

    fun connect() {
        if (_state.value.phase == Phase.CONNECTING) return
        _state.update { it.copy(phase = Phase.CONNECTING, error = null) }
        viewModelScope.launch {
            try {
                val (info, log) = repo.connect(_state.value.verbose)
                _state.update {
                    it.copy(
                        phase = Phase.READY,
                        info = info,
                        readOnly = info.readOnly,
                        localEnforce = repo.enforceState(),
                        log = log,
                    )
                }
                open("/")
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        phase = Phase.FAILED,
                        error = describe(t),
                        localEnforce = repo.enforceState(),
                        log = it.log + repo.lastStartLog + repo.daemonLog(),
                    )
                }
            }
        }
    }

    fun disconnect() = work("disconnecting") {
        repo.disconnect()
        _state.update {
            UiState(
                verbose = it.verbose,
                localEnforce = it.localEnforce,
                log = it.log,
            )
        }
    }

    /**
     * Leaving by the back gesture: close the session first, then let the
     * activity finish.  Without this the helper would keep running as root
     * after the app is gone, until the next launch kills it.
     */
    fun disconnectAndExit() = work("disconnecting") {
        repo.disconnect()
        _state.update {
            UiState(
                verbose = it.verbose,
                localEnforce = it.localEnforce,
                log = it.log,
                exitAfterDisconnect = true,
            )
        }
    }

    fun refreshLog() = _state.update { it.copy(log = repo.daemonLog()) }

    fun toggleReadOnly() = work {
        val next = !_state.value.readOnly
        repo.setReadOnly(next)
        _state.update {
            it.copy(
                readOnly = next,
                toast = if (next) "Read-only mode is back on"
                else "Writes are enabled - EFS changes can brick the modem",
            )
        }
    }

    // ---- browsing ------------------------------------------------------

    fun open(path: String) = work("reading $path") {
        val entries = repo.list(path)
        _state.update { it.copy(path = path, entries = entries, detail = null, preview = null) }
    }

    fun refresh() = open(_state.value.path)

    fun up() {
        val p = _state.value.path
        if (p != "/") open(Paths.parent(p))
    }

    fun onEntryClicked(entry: EfsEntry) {
        val full = Paths.child(_state.value.path, entry.name)
        if (entry.isDir) {
            open(full)
        } else {
            work("reading metadata") {
                val st = runCatching { repo.stat(full) }.getOrNull()
                _state.update { it.copy(detail = Detail(entry, full, st)) }
            }
        }
    }

    fun openDetailForDir(entry: EfsEntry) {
        val full = Paths.child(_state.value.path, entry.name)
        work("reading metadata") {
            val st = runCatching { repo.stat(full) }.getOrNull()
            _state.update { it.copy(detail = Detail(entry, full, st)) }
        }
    }

    fun closeDetail() = _state.update { it.copy(detail = null) }
    fun closePreview() = _state.update { it.copy(preview = null) }
    fun closeEditor() = _state.update { it.copy(editor = null) }

    fun preview(path: String) = work("reading $path") {
        val bytes = repo.readInline(path)
        _state.update { it.copy(preview = PreviewData(path, bytes, looksLikeText(bytes))) }
    }

    // ---- editing -------------------------------------------------------

    fun edit(detail: Detail) = work("reading ${detail.path}") {
        val bytes = repo.readInline(detail.path)
        if (bytes.size > MAX_EDIT) {
            _state.update {
                it.copy(toast = "${humanSize(bytes.size.toLong())} is too much for the editor - " +
                        "save it, change it on a computer and put it back with Replace")
            }
            return@work
        }
        val mode = ((detail.stat?.mode ?: detail.entry.mode) and 0xFFF).let { if (it == 0) 420 else it }
        _state.update {
            it.copy(
                detail = null,
                editor = EditorData(
                    path = detail.path,
                    original = bytes,
                    mode = mode,
                    isItem = detail.entry.isItem,
                    // An item file is a blob with a layout the modem cares
                    // about, so it opens in hex even when it reads as text.
                    startAsText = !detail.entry.isItem && looksLikeText(bytes),
                ),
            )
        }
    }

    /**
     * Commits the EFS journal after a write.  Until this runs the change only
     * exists in the modem's journal, and a modem restart rolls it straight
     * back — so a save that is not committed is not really saved.  Returns
     * text to append to the toast when the commit did not go through, since
     * the write itself did happen and saying nothing would be a lie.
     */
    private suspend fun commitWrite(): String =
        runCatching { repo.sync() }.fold(
            { "" },
            { " (journal flush failed - it may not survive a modem restart)" },
        )

    fun saveEditor(bytes: ByteArray) {
        val ed = _state.value.editor ?: return
        work("writing ${ed.path}") {
            repo.writeFile(ed.path, bytes, ed.mode, ed.isItem)
            val warn = commitWrite()
            val entries = repo.list(_state.value.path)
            _state.update {
                it.copy(
                    editor = null,
                    entries = entries,
                    toast = "Wrote ${bytes.size} bytes to ${ed.path.substringAfterLast('/')}$warn",
                )
            }
        }
    }

    // ---- transfers -----------------------------------------------------

    fun requestExport(path: String) = work("pulling $path") {
        val file = repo.pullToCache(path)
        _state.update {
            it.copy(pendingExport = PendingExport(file, path.trimStart('/').replace('/', '_'), "application/octet-stream"))
        }
    }

    fun requestImageBackup(path: String) = work("asking the modem for a tar image of $path") {
        val file = repo.imageBackup(path)
        _state.update {
            it.copy(
                pendingExport = PendingExport(file, "efs-image.tar", "application/x-tar"),
                toast = "Image is ${humanSize(file.length())}",
            )
        }
    }

    fun requestTreeBackup(path: String) = work("copying $path file by file") {
        val (zip, summary) = repo.treeBackup(path)
        _state.update {
            it.copy(
                pendingExport = PendingExport(zip, "efs-backup.zip", "application/zip"),
                toast = "${summary.files} files, ${humanSize(summary.bytes)}" +
                        if (summary.errors > 0) ", ${summary.errors} unreadable" else "",
                log = it.log + summary.errorList,
            )
        }
    }

    fun completeExport(uri: Uri?) {
        val pending = _state.value.pendingExport ?: return
        _state.update { it.copy(pendingExport = null) }
        if (uri == null) return
        work("saving") {
            repo.copyToUri(pending.file, uri)
            _state.update { it.copy(toast = "Saved ${pending.suggestedName}") }
        }
    }

    fun importInto(uri: Uri, targetPath: String, asItem: Boolean? = null) =
        work("writing $targetPath") {
        val bytes = repo.readUri(uri)
        repo.writeFile(targetPath, bytes, item = asItem)
        val warn = commitWrite()
        val kind = if (asItem == true) "item file" else "file"
        _state.update { it.copy(toast = "Wrote ${bytes.size} bytes as a $kind$warn") }
        val entries = repo.list(_state.value.path)
        _state.update { it.copy(entries = entries) }
    }

    // ---- bulk import ----------------------------------------------------

    private var bulkRun: Job? = null

    private val featureChecker by lazy { FeatureChecker(EfsFeatureAccess(repo)) }
    private var featuresJob: Job? = null

    // Bulk import and the feature toggles both write NV items, so the guards
    // on their entry points keep them mutually exclusive: only one
    // NV-writing actor may run at a time.

    fun startBulkImport(uri: Uri) = viewModelScope.launch {
        val name = repo.displayName(uri)
        var readError: String? = null
        val text = withContext(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
            } catch (t: Throwable) {
                readError = describe(t)
                null
            }
        }
        if (text == null) {
            _state.update {
                it.copy(bulk = BulkState.Preview(
                    name, emptyList(),
                    error = "The selected file could not be read" +
                            (readError?.let { c -> ": $c" } ?: "")))
            }
            return@launch
        }
        val commands = try {
            NvImportParser.parse(text)
        } catch (e: NvImportParseException) {
            _state.update {
                it.copy(bulk = BulkState.Preview(name, emptyList(), error = e.message))
            }
            return@launch
        }
        _state.update { it.copy(bulk = BulkState.Preview(name, commands)) }
    }

    fun closeBulkImport() = _state.update { it.copy(bulk = null) }

    /**
     * Runs the SPC pre-flight, then executes the commands one by one.  A
     * failure never stops the run - it is recorded and the next command is
     * tried, the way mtb reports per-command results.  Losing the connection
     * aborts the run and marks the remaining commands as skipped.
     */
    fun runBulkImport(spc: String) {
        if (bulkRun?.isActive == true) return
        if (featuresJob?.isActive == true) return
        val preview = _state.value.bulk as? BulkState.Preview ?: return
        if (preview.commands.isEmpty()) return
        bulkRun = viewModelScope.launch {
            val cmds = preview.commands

            // Every bulk-state write below is guarded: if the dialog was
            // closed mid-run, the update must not resurrect it.
            if (cmds.any { it.op == BulkOp.WRITE }) {
                val unlocked = try {
                    repo.spcUnlock(spc)
                } catch (t: Throwable) {
                    _state.update { s ->
                        if (s.bulk == null) s
                        else s.copy(bulk = preview.copy(error = "SPC pre-flight failed: ${describe(t)}"))
                    }
                    return@launch
                }
                if (!unlocked) {
                    _state.update { s ->
                        if (s.bulk == null) s
                        else s.copy(bulk = preview.copy(
                            error = "The modem rejected the SPC - import aborted"))
                    }
                    return@launch
                }
            }

            _state.update { s ->
                if (s.bulk == null) s
                else s.copy(bulk = BulkState.Running(preview.fileName, cmds, 0, emptyList()))
            }

            val results = mutableListOf<BulkResult>()
            var aborted = false
            for (cmd in cmds) {
                if (aborted) {
                    results.add(BulkResult(cmd.op, cmd.efsPath, cmd.simTag, ok = false, error = "skipped - connection lost"))
                    continue
                }
                val result = try {
                    when (cmd.op) {
                        BulkOp.WRITE -> {
                            repo.writeFile(cmd.efsPath, parseHexText(cmd.dataHex!!), item = null)
                            BulkResult(cmd.op, cmd.efsPath, cmd.simTag, ok = true)
                        }
                        BulkOp.DELETE -> {
                            repo.unlink(cmd.efsPath)
                            BulkResult(cmd.op, cmd.efsPath, cmd.simTag, ok = true)
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (t is EfsException &&
                        (!repo.connected || t.message?.contains("closed the connection") == true)
                    ) aborted = true
                    BulkResult(cmd.op, cmd.efsPath, cmd.simTag, ok = false, error = describe(t))
                }
                results.add(result)
                _state.update { s ->
                    if (s.bulk == null) s
                    else s.copy(bulk = BulkState.Running(preview.fileName, cmds, results.size, results.toList()))
                }
            }

            var summary: String? = null
            if (!aborted && results.any { it.ok }) {
                try {
                    repo.sync()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    summary = "journal flush failed: ${describe(t)}"
                }
            }
            _state.update { s ->
                if (s.bulk == null) s else s.copy(bulk = BulkState.Done(results.toList(), summary))
            }
        }
    }

    fun modemSsr() = work("restarting the modem") {
        var reconnected = false
        val msg = try {
            reconnected = repo.modemSsr()
            if (reconnected) "Modem restarted - EFS changes are committed and live"
            else "Modem restarted, but the session did not come back - reconnect"
        } catch (t: Throwable) {
            describe(t)
        }
        // The listing was read from the modem that just went away, so pull it
        // again over the fresh session rather than leaving stale entries up.
        // Inline rather than open(): that is its own work{} and would fight
        // this one over the busy flag.
        if (reconnected) runCatching {
            val entries = repo.list(_state.value.path)
            _state.update { it.copy(entries = entries) }
        }
        // A dialog would cover the toast, so the SSR button's feedback goes
        // into the dialog itself when one is open.
        routeDialogNote(msg)
    }

    private fun routeDialogNote(message: String) {
        _state.update { s ->
            // When both dialogs are somehow open, the bulk dialog wins.
            when (val bulk = s.bulk) {
                is BulkState.Preview -> s.copy(bulk = bulk.copy(note = message))
                is BulkState.Done -> s.copy(bulk = bulk.copy(note = message))
                else -> when (val f = s.features) {
                    is FeaturesState.Ready -> s.copy(features = f.copy(note = message))
                    else -> s.copy(toast = message)
                }
            }
        }
    }

    // ---- feature toggles ----

    fun openFeatures() {
        if (featuresJob?.isActive == true) return
        if (bulkRun?.isActive == true) return
        featuresJob = viewModelScope.launch {
            _state.update { it.copy(features = FeaturesState.Checking(DEFAULT_FEATURE_SLOT)) }
            checkFeatures(DEFAULT_FEATURE_SLOT)
        }
    }

    fun closeFeatures() {
        // Cancelling mid-check is safe: the check only reads, so stopping it
        // between exchanges is harmless.  Never cancel during Writing - a
        // modification must run to completion so the modem is not left
        // half-written.
        if (_state.value.features is FeaturesState.Checking) featuresJob?.cancel()
        _state.update { it.copy(features = null) }
    }

    fun setFeatureSimSlot(slot: Int) {
        if (featuresJob?.isActive == true) return
        if (bulkRun?.isActive == true) return
        featuresJob = viewModelScope.launch {
            var transitioned = false
            _state.update { s ->
                if (s.features !is FeaturesState.Ready) return@update s
                transitioned = true
                s.copy(features = FeaturesState.Checking(slot))
            }
            // Only re-check when the dialog actually transitioned; a closed
            // dialog must not trigger I/O.
            if (transitioned) checkFeatures(slot)
        }
    }

    private suspend fun checkFeatures(slot: Int) {
        try {
            val result = withContext(Dispatchers.IO) { featureChecker.check(ALL_FEATURES, slot) }
            _state.update { s ->
                if (s.features == null) s
                else s.copy(
                    features = FeaturesState.Ready(
                        slot,
                        result.statuses,
                        warnBeforeDisable = !disableWarningSuppressed(),
                    ),
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.update { it.copy(features = null, toast = describe(t)) }
        }
    }

    fun disableFeature(id: String, spc: String) {
        if (featuresJob?.isActive == true) return
        if (bulkRun?.isActive == true) return
        featuresJob = viewModelScope.launch {
            val ready = _state.value.features as? FeaturesState.Ready ?: return@launch
            val feature = ALL_FEATURES.first { it.id == id }
            // A failed write is retryable, so WriteError is accepted here too.
            val status = ready.statuses[id]
            if (status !is FeatureStatus.CanDisable && status !is FeatureStatus.WriteError) return@launch
            if (!ensureSpc(spc, "The modem rejected the SPC - feature not changed.", ::noteFeatures)) return@launch
            // The last check only says what to write; what the write really
            // left behind is read back below.
            updateStatus(feature.id, FeatureStatus.Writing)
            val error = try {
                withContext(Dispatchers.IO) { featureChecker.disable(feature, ready.simSlot) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                updateStatus(feature.id, FeatureStatus.WriteError(describe(t)))
                return@launch
            }
            if (error != null) {
                updateStatus(feature.id, FeatureStatus.WriteError(error))
                noteFeatures(error)
                return@launch
            }
            // A successful write is not proof the feature is off: ul_mimo and
            // lowband_4rx share cap_limit_rf_mimo with mutually exclusive
            // payloads, so disabling one re-enables the other.  Re-read every
            // feature instead of assuming.  The row keeps its Writing spinner
            // until the fresh statuses land, which also keeps the dialog
            // locked for the duration.
            checkFeatures(ready.simSlot)
        }
    }

    /** One-way: once suppressed, the warning never comes back (no reset UI). */
    fun suppressDisableWarning() {
        prefs.edit().putBoolean(KEY_DISABLE_WARNING_SUPPRESSED, true).apply()
        _state.update { s ->
            val f = s.features as? FeaturesState.Ready ?: return@update s
            s.copy(features = f.copy(warnBeforeDisable = false))
        }
    }

    private fun disableWarningSuppressed(): Boolean =
        prefs.getBoolean(KEY_DISABLE_WARNING_SUPPRESSED, false)

    /**
     * SPC pre-flight before any modification.  Reports failure through
     * [onReject] - the unlock's exception, or [rejectionMessage] when the
     * modem rejected the code (each caller keeps its own wording) - and
     * returns false so the caller can bail out.
     */
    private suspend fun ensureSpc(
        spc: String,
        rejectionMessage: String,
        onReject: (String) -> Unit,
    ): Boolean {
        val unlocked = try {
            withContext(Dispatchers.IO) { repo.spcUnlock(spc) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            onReject("SPC pre-flight failed: ${describe(t)}")
            return false
        }
        if (!unlocked) {
            onReject(rejectionMessage)
            return false
        }
        return true
    }

    private fun updateStatus(id: String, status: FeatureStatus) {
        _state.update { s ->
            val f = s.features as? FeaturesState.Ready ?: return@update s
            s.copy(features = f.copy(statuses = f.statuses + (id to status)))
        }
    }

    private fun noteFeatures(message: String) {
        _state.update { s ->
            val f = s.features as? FeaturesState.Ready
                ?: return@update s.copy(toast = message)
            s.copy(features = f.copy(note = message))
        }
    }

    // ---- update check ---------------------------------------------------

    /**
     * Asks GitHub for the newest release. The automatic check on startup stays
     * quiet unless there is something newer -- being offline is not worth a
     * toast -- while a check the user asked for reports every outcome.
     */
    fun checkForUpdates(manual: Boolean) {
        viewModelScope.launch {
            val current = installedVersion
            val release = try {
                UpdateChecker.latestNewerThan(current)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (manual) _state.update { it.copy(toast = describe(t)) }
                return@launch
            }
            if (release == null) {
                if (manual) {
                    _state.update { it.copy(toast = "Version $current is the latest release") }
                }
                return@launch
            }
            // A version the user waved away stays away, but only automatically.
            if (!manual && prefs.getString(KEY_SKIPPED_VERSION, null) == release.version) {
                return@launch
            }
            _state.update { it.copy(update = release) }
        }
    }

    fun dismissUpdate() = _state.update { it.copy(update = null) }

    /** Closes the dialog and stops the automatic check from offering this version again. */
    fun skipUpdate() {
        _state.value.update?.let {
            prefs.edit().putString(KEY_SKIPPED_VERSION, it.version).apply()
        }
        dismissUpdate()
    }

    // ---- mutations -----------------------------------------------------

    fun createDir(name: String) = work("creating $name") {
        repo.mkdir(Paths.child(_state.value.path, name))
        val entries = repo.list(_state.value.path)
        _state.update { it.copy(entries = entries, toast = "Created $name") }
    }

    fun chmod(path: String, mode: Int) = work("chmod") {
        repo.chmod(path, mode)
        val entries = repo.list(_state.value.path)
        _state.update { it.copy(entries = entries, toast = "Mode changed") }
    }

    fun delete(detail: Detail) = work("deleting ${detail.path}") {
        repo.delete(detail.entry, detail.path)
        val entries = repo.list(_state.value.path)
        _state.update {
            it.copy(detail = null, entries = entries, toast = "Deleted ${detail.entry.name}")
        }
    }

    fun sync() = work("flushing the EFS journal") {
        repo.sync()
        _state.update { it.copy(toast = "Journal flushed") }
    }

    // ---- NV ------------------------------------------------------------

    fun nvRead(item: Int, index: Int?) = work("reading NV $item") {
        try {
            val res = repo.nvRead(item, index)
            _state.update { it.copy(nv = res, nvError = null) }
        } catch (t: Throwable) {
            _state.update { it.copy(nv = null, nvError = describe(t)) }
        }
    }

    fun nvWrite(item: Int, hex: String, index: Int?) = work("writing NV $item") {
        repo.nvWrite(item, hex, index)
        _state.update { it.copy(toast = "NV item $item written") }
    }

    fun spcUnlock(spc: String) = work("sending the SPC") {
        val msg = try {
            if (repo.spcUnlock(spc)) "SPC accepted - NV writes are unlocked"
            else "SPC rejected - the modem did not accept that code"
        } catch (t: Throwable) {
            describe(t)
        }
        // The dialog covers the snackbar, so the SPC test's feedback goes into
        // the dialog itself while it is open.
        routeDialogNote(msg)
    }

    fun rawSend(hex: String, onResult: (String) -> Unit) = work("sending a raw DIAG packet") {
        onResult(repo.rawExchange(hex))
    }
}

fun errnoName(e: Int): String = when (e) {
    1 -> "EPERM"
    2 -> "ENOENT"
    5 -> "EIO"
    6 -> "ENXIO"
    9 -> "EBADF"
    12 -> "ENOMEM"
    13 -> "EACCES"
    17 -> "EEXIST"
    20 -> "ENOTDIR"
    21 -> "EISDIR"
    22 -> "EINVAL"
    23 -> "ENFILE"
    24 -> "EMFILE"
    27 -> "EFBIG"
    28 -> "ENOSPC"
    30 -> "EROFS"
    36 -> "ENAMETOOLONG"
    39 -> "ENOTEMPTY"
    else -> "efs errno $e"
}
