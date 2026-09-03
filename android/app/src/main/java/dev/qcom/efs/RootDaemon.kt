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
 * The helper is the only part of the app that talks to the modem; the app
 * itself never runs with elevated privileges and only speaks to the helper
 * over an abstract unix socket that is bound to this app's uid.
 *
 * Nothing here changes the system in any way: no file outside the app's own
 * directories is written, and the SELinux mode is read once for the log and
 * otherwise left alone.
 */
object RootDaemon {

    const val SOCKET_NAME = "qcom_efsd"
    private const val ASSET_NAME = "qcom-efsd"

    class Report(
        val ok: Boolean,
        val lines: List<String>,
        val error: String? = null,
        /** enforce value as read by root, since an app uid usually cannot. */
        val enforce: Int? = null,
    )

    fun logFile(ctx: Context): File = File(ctx.cacheDir, "qcom-efsd.log")

    fun modeName(v: Int?): String = when (v) {
        1 -> "enforcing"
        0 -> "permissive"
        else -> "unknown"
    }

    // ---- lifecycle ------------------------------------------------------

    suspend fun start(ctx: Context, verbose: Boolean): Report =
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

            if (enforce != null) log += "SELinux is ${modeName(enforce)}, and stays that way"

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

    suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { runSu("pkill -9 -f $ASSET_NAME\nexit\n", mutableListOf()) }
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
