package dev.qcom.efs

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream

class EfsException(message: String, val efsErrno: Int? = null) : Exception(message)

/**
 * One request per line, one JSON object per answer.  Every exchange is
 * serialised: the daemon keeps a single DIAG session and cannot interleave.
 */
class EfsClient : Closeable {

    private val mutex = Mutex()
    private var socket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    suspend fun connect(timeoutMs: Long = 5_000) = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            val deadline = System.currentTimeMillis() + timeoutMs
            var last: Throwable? = null
            while (System.currentTimeMillis() < deadline) {
                try {
                    val s = LocalSocket()
                    s.connect(LocalSocketAddress(RootDaemon.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                    s.soTimeout = 120_000
                    socket = s
                    reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8), 1 shl 16)
                    writer = s.outputStream
                    return@withLock
                } catch (t: Throwable) {
                    last = t
                    Thread.sleep(150)
                }
            }
            throw EfsException(
                "cannot reach the helper on @${RootDaemon.SOCKET_NAME}: ${last?.message ?: "timed out"}"
            )
        }
    }

    /** Sends a request and returns the parsed answer; throws on `"ok":false`. */
    suspend fun request(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val json = raw(payload)
        if (!json.optBoolean("ok", false)) {
            val errno = if (json.has("efs_errno")) json.optInt("efs_errno") else null
            throw EfsException(json.optString("error", "the helper rejected the request"), errno)
        }
        json
    }

    /** Same, but returns failures instead of throwing. */
    suspend fun raw(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            val out = writer ?: throw EfsException("not connected to the helper")
            val rd = reader ?: throw EfsException("not connected to the helper")

            out.write((payload.toString() + "\n").toByteArray(Charsets.UTF_8))
            out.flush()

            val line = rd.readLine() ?: throw EfsException("the helper closed the connection")
            try {
                JSONObject(line)
            } catch (t: Throwable) {
                throw EfsException("malformed answer from the helper: ${line.take(200)}")
            }
        }
    }

    suspend fun cmd(name: String, build: JSONObject.() -> Unit = {}): JSONObject =
        request(JSONObject().put("cmd", name).apply(build))

    override fun close() {
        runCatching { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }
}
