package dev.qcom.efs

import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Extracts the bundled aarch64 helper and starts it as root.
 *
 * The daemon is the only part of the app that touches /dev/diag; the app
 * itself never runs with elevated privileges and only speaks to the daemon
 * over an abstract unix socket that is bound to this app's uid.
 *
 * The helper may briefly drop SELinux to permissive when the policy blocks
 * /dev/diag, and puts it back itself.  Everything here is the belt to that
 * pair of braces: we remember what the mode was before we started, and we
 * restore it after killing the helper or after an app crash.
 */
object RootDaemon {

    const val SOCKET_NAME = "qcom_efsd"
    private const val ASSET_NAME = "qcom-efsd"
    private const val PREFS = "qcom-efs"
    private const val KEY_RESTORE_TO = "selinux_restore_to"

    private val ENFORCE_NODES = listOf("/sys/fs/selinux/enforce", "/selinux/enforce")

    class Report(
        val ok: Boolean,
        val lines: List<String>,
        val error: String? = null,
        /** enforce value as read by root, since an app uid usually cannot. */
        val enforce: Int? = null,
    )

    fun logFile(ctx: Context): File = File(ctx.cacheDir, "qcom-efsd.log")

    // ---- SELinux bookkeeping -------------------------------------------

    /**
     * 1 = enforcing, 0 = permissive, null = unknown.
     *
     * Reads the node directly, which usually fails from an app uid -- the
     * policy rarely lets untrusted_app touch selinuxfs.  The authoritative
     * value comes from the root shell in [start] and from the helper.
     */
    fun enforceState(): Int? {
        for (path in ENFORCE_NODES) {
            val f = File(path)
            if (!f.exists()) continue
            val text = runCatching { f.readText().trim() }.getOrNull() ?: continue
            return text.firstOrNull()?.let { if (it == '1') 1 else 0 }
        }
        return null
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Records that something must be put back if we die before doing it. */
    fun noteOutstandingRestore(ctx: Context, restoreTo: Int?) {
        val p = prefs(ctx).edit()
        if (restoreTo == null) p.remove(KEY_RESTORE_TO) else p.putInt(KEY_RESTORE_TO, restoreTo)
        p.apply()
    }

    fun outstandingRestore(ctx: Context): Int? =
        prefs(ctx).getInt(KEY_RESTORE_TO, -1).takeIf { it >= 0 }

    /**
     * Called at app start: if a previous run left SELinux lowered - the app was
     * force-stopped, or the helper was SIGKILLed before its own guard ran -
     * put the mode back now.
     */
    suspend fun recoverSelinux(ctx: Context): String? = withContext(Dispatchers.IO) {
        val want = outstandingRestore(ctx) ?: return@withContext null
        val now = enforceState()
        if (now == want) {
            noteOutstandingRestore(ctx, null)
            return@withContext null
        }
        val ok = runCatching { setEnforce(want) }.getOrDefault(false)
        if (ok) noteOutstandingRestore(ctx, null)
        "SELinux was left at ${modeName(now)} by a previous run; " +
                (if (ok) "restored to ${modeName(want)}." else "could NOT restore it - check root access.")
    }

    /**
     * Writes the enforce node through the root shell.  Probing for the file
     * from the app process is useless -- the policy usually hides selinuxfs
     * from an app uid -- so both known paths are tried inside the shell.
     */
    private fun setEnforce(value: Int): Boolean {
        val script = buildString {
            for (node in ENFORCE_NODES) appendLine("[ -e '$node' ] && echo $value > '$node'")
            appendLine("echo \"SE=$(cat ${ENFORCE_NODES[0]} 2>/dev/null)\"")
            appendLine("exit")
        }
        val out = runSu(script, mutableListOf())
        return out.any { it.startsWith("SE=") && it.removePrefix("SE=").trim() == value.toString() }
    }

    fun modeName(v: Int?): String = when (v) {
        1 -> "enforcing"
        0 -> "permissive"
        else -> "unknown"
    }

    // ---- lifecycle ------------------------------------------------------

    suspend fun start(ctx: Context, verbose: Boolean, allowPermissive: Boolean): Report =
        withContext(Dispatchers.IO) {
            val log = mutableListOf<String>()

            val binary = File(ctx.filesDir, ASSET_NAME)
            try {
                // A helper started earlier may still be executing from this
                // very file, and Linux refuses to reopen a running binary for
                // writing (ETXTBUSY).  Unlinking it first is allowed: the old
                // process keeps the old inode and we get a fresh file.
                binary.delete()
                ctx.assets.open(ASSET_NAME).use { input ->
                    binary.outputStream().use { input.copyTo(it) }
                }
                binary.setExecutable(true, true)
                binary.setReadable(true, true)
                log += "helper unpacked to ${binary.absolutePath} (${binary.length()} bytes)"
            } catch (t: Throwable) {
                return@withContext Report(false, log, "cannot unpack the helper: ${t.message}")
            }

            val logPath = logFile(ctx).absolutePath
            val uid = Process.myUid()
            val script = buildString {
                appendLine("echo \"UID=$(id -u)\"")
                appendLine("echo \"SE=$(cat /sys/fs/selinux/enforce 2>/dev/null)\"")
                appendLine("pkill -9 -f $ASSET_NAME 2>/dev/null")
                appendLine("chmod 700 '${binary.absolutePath}' 2>/dev/null")
                appendLine("rm -f '$logPath' 2>/dev/null")
                append("setsid '${binary.absolutePath}' -uid $uid")
                if (verbose) append(" -verbose")
                if (allowPermissive) append(" -allow-permissive")
                appendLine(" </dev/null >>'$logPath' 2>&1 &")
                appendLine("sleep 1")
                appendLine("chmod 644 '$logPath' 2>/dev/null")
                appendLine("echo __DONE__")
                appendLine("exit")
            }

            val output = try {
                runSu(script, log)
            } catch (t: Throwable) {
                return@withContext Report(
                    false, log,
                    "root shell unavailable: ${t.message ?: "su could not be started"}"
                )
            }

            val rootUid = output.firstOrNull { it.startsWith("UID=") }?.removePrefix("UID=")?.trim()
            val enforce = output.firstOrNull { it.startsWith("SE=") }
                ?.removePrefix("SE=")?.trim()
                ?.let { if (it == "1") 1 else if (it == "0") 0 else null }

            if (enforce != null) log += "SELinux is ${modeName(enforce)} before starting the helper"

            if (rootUid != "0") {
                return@withContext Report(
                    false, log, "root access was denied (id -u returned '$rootUid')", enforce)
            }
            if (output.none { it.contains("__DONE__") }) {
                return@withContext Report(
                    false, log, "the root shell exited before the helper was started", enforce)
            }

            log += readDaemonLog(ctx)
            Report(true, log, null, enforce)
        }

    /**
     * Kills the helper.  SIGKILL gives the helper's own guards no chance to
     * run, so its SELinux deadman child does that - and we make sure here too.
     */
    suspend fun stop(ctx: Context) = withContext(Dispatchers.IO) {
        val want = outstandingRestore(ctx)
        runCatching { runSu("pkill -9 -f $ASSET_NAME\nexit\n", mutableListOf()) }

        if (want != null) {
            // Give the deadman a moment, then check for ourselves.
            Thread.sleep(200)
            if (enforceState() != want) runCatching { setEnforce(want) }
            noteOutstandingRestore(ctx, null)
        }
        Unit
    }

    fun readDaemonLog(ctx: Context): List<String> =
        runCatching { logFile(ctx).readLines().takeLast(200) }.getOrDefault(emptyList())

    private fun runSu(script: String, log: MutableList<String>): List<String> {
        val proc = ProcessBuilder("su").redirectErrorStream(true).start()
        proc.outputStream.bufferedWriter().use { it.write(script); it.flush() }

        val lines = mutableListOf<String>()
        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lines += line
                log += "su: $line"
            }
        }
        proc.waitFor()
        return lines
    }
}
