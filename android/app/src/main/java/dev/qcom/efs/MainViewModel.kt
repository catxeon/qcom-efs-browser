package dev.qcom.efs

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class Phase { DISCONNECTED, CONNECTING, READY, FAILED }

data class Detail(val entry: EfsEntry, val path: String, val stat: EfsStat? = null)

data class PreviewData(val path: String, val bytes: ByteArray, val asText: Boolean) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** A file staged in the cache, waiting for the user to pick a destination. */
data class PendingExport(val file: File, val suggestedName: String, val mime: String)

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
    val pendingExport: PendingExport? = null,
    val nv: NvResult? = null,
    val nvError: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = EfsRepository(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

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

    fun preview(path: String) = work("reading $path") {
        val bytes = repo.readInline(path)
        val printable = bytes.count { val c = it.toInt() and 0xFF; c in 32..126 || c in listOf(9, 10, 13) }
        val asText = bytes.isNotEmpty() && printable > bytes.size * 8 / 10
        _state.update { it.copy(preview = PreviewData(path, bytes, asText)) }
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
        val kind = if (asItem == true) "item file" else "file"
        _state.update { it.copy(toast = "Wrote ${bytes.size} bytes as a $kind") }
        val entries = repo.list(_state.value.path)
        _state.update { it.copy(entries = entries) }
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
