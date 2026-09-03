package dev.qcom.efs

import org.json.JSONObject

data class EfsEntry(
    val name: String,
    val type: String,
    val mode: Int,
    val size: Int,
    val atime: Int,
    val mtime: Int,
    val ctime: Int,
    val entryType: Int,
) {
    val isDir: Boolean get() = type == "dir"
    val isLink: Boolean get() = type == "link"
    val isItem: Boolean get() = type == "item"

    companion object {
        fun from(o: JSONObject) = EfsEntry(
            name = o.optString("name"),
            type = o.optString("type", "unknown"),
            mode = o.optInt("mode"),
            size = o.optInt("size"),
            atime = o.optInt("atime"),
            mtime = o.optInt("mtime"),
            ctime = o.optInt("ctime"),
            entryType = o.optInt("entry_type"),
        )
    }
}

data class EfsStat(
    val path: String,
    val type: String,
    val mode: Int,
    val size: Int,
    val nlink: Int,
    val atime: Int,
    val mtime: Int,
    val ctime: Int,
    val target: String?,
) {
    companion object {
        fun from(o: JSONObject) = EfsStat(
            path = o.optString("path"),
            type = o.optString("type", "unknown"),
            mode = o.optInt("mode"),
            size = o.optInt("size"),
            nlink = o.optInt("nlink"),
            atime = o.optInt("atime"),
            mtime = o.optInt("mtime"),
            ctime = o.optInt("ctime"),
            target = if (o.has("target")) o.optString("target") else null,
        )
    }
}

data class DaemonInfo(
    val version: String,
    val subsys: Int,
    /** How the helper reached the modem, e.g. "qrtr 0:25". */
    val transport: String,
    val readOnly: Boolean,
)

data class NvResult(val item: Int, val status: Int, val statusText: String, val hex: String)

data class PullSummary(
    val dirs: Int,
    val files: Int,
    val links: Int,
    val bytes: Long,
    val errors: Int,
    val errorList: List<String>,
)

object Paths {
    fun parent(path: String): String {
        if (path == "/" || path.isEmpty()) return "/"
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx <= 0) "/" else trimmed.substring(0, idx)
    }

    fun child(dir: String, name: String): String =
        if (dir == "/") "/$name" else "${dir.trimEnd('/')}/$name"

    /** Mirrors efs_is_item_path() in the helper: where item files normally live. */
    fun looksLikeItemPath(path: String): Boolean =
        path.startsWith("/nv/item_files/") ||
        path.startsWith("/nv/reg_files/") ||
        path.startsWith("/cgps/nv/item_files/") ||
        path.startsWith("/sd/")

    fun crumbs(path: String): List<Pair<String, String>> {
        val out = mutableListOf("/" to "/")
        var acc = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            acc = "$acc/$part"
            out += part to acc
        }
        return out
    }
}

fun modeOctal(mode: Int): String = "0" + (mode and 0xFFF).toString(8).padStart(4, '0')

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KiB", bytes / 1024.0)
    else -> String.format("%.1f MiB", bytes / (1024.0 * 1024))
}
