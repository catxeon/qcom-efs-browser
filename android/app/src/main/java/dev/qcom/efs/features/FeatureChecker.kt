package dev.qcom.efs.features

import dev.qcom.efs.EfsException
import dev.qcom.efs.EfsRepository
import kotlin.coroutines.cancellation.CancellationException

/** Per-feature status, mirroring mtbtool's FeatureStatus. */
sealed interface FeatureStatus {
    data object AlreadyDisabled : FeatureStatus
    data object CanDisable : FeatureStatus
    data object Writing : FeatureStatus
    data object Restoring : FeatureStatus
    data class WriteError(val message: String) : FeatureStatus
    data class ReadError(val message: String) : FeatureStatus
}

/**
 * Result of checking all features: status + captured originals per feature id.
 * Features with a [FeatureStatus.ReadError] status get an all-null originals
 * entry (`List(reads.size) { null }`); callers must not attempt to restore
 * from those entries.
 */
data class FeatureCheck(
    val statuses: Map<String, FeatureStatus>,
    val originals: Map<String, List<List<Int>?>>,
)

/**
 * Storage access the checker needs. [read] returns null when the item file is
 * absent (the modem default: feature enabled). Injected so tests can fake it.
 */
interface FeatureItemAccess {
    suspend fun read(path: String): List<Int>?
    suspend fun write(path: String, bytes: List<Int>)
    suspend fun delete(path: String)
}

/** Real [FeatureItemAccess] backed by the daemon repository. */
class EfsFeatureAccess(private val repo: EfsRepository) : FeatureItemAccess {

    override suspend fun read(path: String): List<Int>? = try {
        repo.readInline(path).bytesAsIntList()
    } catch (e: EfsException) {
        if (e.efsErrno == 2) null else throw e // ENOENT = absent
    }

    override suspend fun write(path: String, bytes: List<Int>) {
        repo.writeFile(path, bytes.asByteArray(), item = null)
    }

    override suspend fun delete(path: String) {
        repo.unlink(path)
    }
}

private fun ByteArray.bytesAsIntList(): List<Int> = map { it.toInt() and 0xFF }

private fun List<Int>.asByteArray(): ByteArray = map { it.toByte() }.toByteArray()

/**
 * Checks feature state and applies disable/restore, following mtbtool's
 * FeaturesChecker semantics:
 * - any read path absent -> CanDisable (modem default),
 * - all present + isDisabled -> AlreadyDisabled, else CanDisable,
 * - disable writes every entry, stopping at the first failure,
 * - restore writes captured originals back, or deletes items that were
 *   absent and verifies they are gone afterwards.
 */
class FeatureChecker(private val access: FeatureItemAccess) {

    suspend fun check(features: List<FeatureDef>, slot: Int): FeatureCheck {
        val statuses = mutableMapOf<String, FeatureStatus>()
        val originals = mutableMapOf<String, List<List<Int>?>>()
        for (feature in features) {
            val perPath = mutableListOf<List<Int>?>()
            var readError: String? = null
            for (r in feature.reads) {
                try {
                    perPath += access.read(slotPath(r, slot))
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    readError = t.message ?: "read failed"
                    break
                }
            }
            if (readError != null) {
                statuses[feature.id] = FeatureStatus.ReadError(readError)
                originals[feature.id] = List(feature.reads.size) { null }
                continue
            }
            originals[feature.id] = perPath
            val existing = perPath.filterNotNull()
            statuses[feature.id] = if (existing.size < feature.reads.size) {
                FeatureStatus.CanDisable
            } else if (feature.isDisabled(existing)) {
                FeatureStatus.AlreadyDisabled
            } else {
                FeatureStatus.CanDisable
            }
        }
        return FeatureCheck(statuses, originals)
    }

    /** Writes the disabling bytes. Returns null on success, else an error message. */
    suspend fun disable(feature: FeatureDef, slot: Int): String? {
        for (w in feature.writes) {
            try {
                access.write(slotPath(w.path, slot), w.bytes)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return "Write failed for ${w.path.substringAfterLast('/')}"
            }
        }
        return null
    }

    /**
     * Restores captured originals. A null entry means the item was absent, so
     * it is deleted and the deletion is verified by re-reading. Returns null
     * on success, else an error message.
     */
    suspend fun restore(feature: FeatureDef, originals: List<List<Int>?>, slot: Int): String? {
        for ((i, r) in feature.reads.withIndex()) {
            val path = slotPath(r, slot)
            try {
                val original = originals.getOrNull(i)
                if (original == null) {
                    access.delete(path)
                    if (access.read(path) != null) {
                        return "Delete succeeded but item still exists: ${path.substringAfterLast('/')}"
                    }
                } else {
                    access.write(path, original)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return "Restore failed for ${path.substringAfterLast('/')}"
            }
        }
        return null
    }
}

/**
 * Merges [previous] disable-time provenance into a freshly re-checked [fresh]
 * FeatureCheck.  WHY: a re-check of an already-disabled feature reads the
 * disabled payload as "current bytes", but those bytes are what WE wrote, not
 * the pre-disable original - restoring them would write disabled bytes over
 * disabled bytes and the feature could never come back.  For features that
 * are AlreadyDisabled and have a saved entry from when they were actually
 * disabled, that saved entry is the true original and must win; everything
 * else keeps the fresh capture.  Provenance lives only in the app process
 * (lost on process death, like mtbtool) - a fresh capture is the best we can
 * do without it.
 */
fun preservedOriginals(
    fresh: FeatureCheck,
    previous: Map<String, List<List<Int>?>>,
): Map<String, List<List<Int>?>> = fresh.originals.mapValues { (id, captured) ->
    val disabled = fresh.statuses[id] is FeatureStatus.AlreadyDisabled
    if (disabled && previous.containsKey(id)) previous.getValue(id) else captured
}
