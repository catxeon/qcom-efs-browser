package dev.qcom.efs.features

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureCheckerTest {

    /** In-memory item store; absent key = item absent. */
    private class FakeAccess(
        vararg initial: Pair<String, List<Int>>,
    ) : FeatureItemAccess {
        val items = initial.toMap().toMutableMap()
        val writes = mutableListOf<Pair<String, List<Int>>>()
        val deletes = mutableListOf<String>()
        var failWriteOn: String? = null
        var failReadOn: String? = null
        var failDeleteOn: String? = null

        override suspend fun read(path: String): List<Int>? {
            if (path == failReadOn) throw RuntimeException("read boom")
            return items[path]
        }

        override suspend fun write(path: String, bytes: List<Int>) {
            if (path == failWriteOn) throw RuntimeException("write boom")
            writes += path to bytes
            items[path] = bytes
        }

        override suspend fun delete(path: String) {
            if (path == failDeleteOn) throw RuntimeException("delete boom")
            deletes += path
            items.remove(path)
        }
    }

    private val fdd = ALL_FEATURES.first { it.id == "fdd_ul_mimo" }
    private val dl = ALL_FEATURES.first { it.id == "dl_nrca" }
    private val path = "/nv/item_files/modem/nr5g/RRC/cap_control_fdd_ul_mimo"
    private val dlPath = "/nv/item_files/modem/nr5g/RRC/cap_nrca_downgrade_1cc"
    private val nrBase = "/nv/item_files/modem/nr5g/RRC/"

    @Test
    fun `absent item means feature can be disabled`() = runBlocking {
        val c = FeatureChecker(FakeAccess())
        val r = c.check(listOf(fdd), slot = 0)
        assertEquals(FeatureStatus.CanDisable, r.statuses[fdd.id])
        assertEquals(listOf<List<Int>?>(null), r.originals[fdd.id])
    }

    @Test
    fun `present enabled item means can disable and originals captured`() = runBlocking {
        val c = FeatureChecker(FakeAccess(path to listOf(1, 1)))
        val r = c.check(listOf(fdd), slot = 0)
        assertEquals(FeatureStatus.CanDisable, r.statuses[fdd.id])
        assertEquals(listOf<List<Int>?>(listOf(1, 1)), r.originals[fdd.id])
    }

    @Test
    fun `present disabled item means already disabled`() = runBlocking {
        val c = FeatureChecker(FakeAccess(path to listOf(0, 0)))
        val r = c.check(listOf(fdd), slot = 0)
        assertEquals(FeatureStatus.AlreadyDisabled, r.statuses[fdd.id])
    }

    @Test
    fun `partially absent multi-read feature means can disable`() = runBlocking {
        val c = FeatureChecker(FakeAccess(dlPath to listOf(0)))
        val r = c.check(listOf(dl), slot = 0)
        assertEquals(FeatureStatus.CanDisable, r.statuses[dl.id])
    }

    @Test
    fun `read error maps to ReadError and check continues with other features`() = runBlocking {
        val c = FeatureChecker(FakeAccess().apply { failReadOn = path })
        val r = c.check(listOf(fdd, dl), slot = 0)
        assertTrue(r.statuses[fdd.id] is FeatureStatus.ReadError)
        assertEquals(FeatureStatus.CanDisable, r.statuses[dl.id])
    }

    @Test
    fun `slot 1 reads the suffixed path`() = runBlocking {
        val access = FakeAccess(path + "_Subscription01" to listOf(0, 0))
        val c = FeatureChecker(access)
        val r = c.check(listOf(fdd), slot = 1)
        assertEquals(FeatureStatus.AlreadyDisabled, r.statuses[fdd.id])
    }

    @Test
    fun `capture returns per-path values with null for absent`() = runBlocking {
        val nsa = ALL_FEATURES.first { it.id == "nsa_tf_nrca" }
        val c = FeatureChecker(FakeAccess(nrBase + "cap_control_mrdc_f_plus_t_band_combos" to listOf(0)))
        val originals = c.check(listOf(nsa), slot = 0).originals[nsa.id]!!
        assertEquals(listOf<List<Int>?>(listOf(0), null), originals)
    }

    @Test
    fun `disable writes every write entry to the slot-mapped path`() = runBlocking {
        val access = FakeAccess()
        val c = FeatureChecker(access)
        assertNull(c.disable(fdd, slot = 1))
        assertEquals(listOf(path + "_Subscription01" to listOf(0, 0)), access.writes)
    }

    @Test
    fun `disable stops at first failure with the mtbtool message`() = runBlocking {
        val access = FakeAccess().apply { failWriteOn = path }
        val c = FeatureChecker(access)
        assertEquals("Write failed for cap_control_fdd_ul_mimo", c.disable(fdd, slot = 0))
    }

    @Test
    fun `disable of a multi-write feature stops at the first failure`() = runBlocking {
        val r16 = ALL_FEATURES.first { it.id == "r16_2t1t" }
        val access = FakeAccess().apply { failWriteOn = nrBase + "cap_swul_type_control" }
        val c = FeatureChecker(access)
        assertEquals("Write failed for cap_swul_type_control", c.disable(r16, slot = 0))
        assertEquals(1, access.writes.size)
        assertEquals(nrBase + "cap_control_nrca_xf_plus_yt_swul_band_combos_v2", access.writes.single().first)
    }

    @Test
    fun `restore writes originals back`() = runBlocking {
        val access = FakeAccess(path to listOf(0, 0))
        val c = FeatureChecker(access)
        assertNull(c.restore(fdd, listOf(listOf(1, 1)), slot = 0))
        assertEquals(listOf(path to listOf(1, 1)), access.writes)
        assertEquals(listOf(1, 1), access.items[path])
    }

    @Test
    fun `restore deletes items that were absent and verifies deletion`() = runBlocking {
        val access = FakeAccess(path to listOf(0, 0))
        val c = FeatureChecker(access)
        assertNull(c.restore(fdd, listOf(null), slot = 0))
        assertEquals(listOf(path), access.deletes)
        assertNull(access.items[path])
    }

    @Test
    fun `restore fails when deletion did not stick`() = runBlocking {
        // delete "succeeds" but the item reappears on read
        val access = object : FeatureItemAccess by FakeAccess(path to listOf(0, 0)) {
            override suspend fun delete(path: String) { /* no-op */ }
        }
        val c = FeatureChecker(access)
        assertEquals(
            "Delete succeeded but item still exists: cap_control_fdd_ul_mimo",
            c.restore(fdd, listOf(null), slot = 0),
        )
    }

    @Test
    fun `restore reports failure with the filename`() = runBlocking {
        val access = FakeAccess().apply { failWriteOn = path }
        val c = FeatureChecker(access)
        assertEquals("Restore failed for cap_control_fdd_ul_mimo", c.restore(fdd, listOf(listOf(1, 1)), slot = 0))
    }

    @Test
    fun `preserved originals use the previous entry for already disabled features`() = runBlocking {
        // The item holds the disabled payload now, so the fresh capture is not
        // an original; the disable-time provenance (item absent) must win.
        val c = FeatureChecker(FakeAccess(path to listOf(0, 0)))
        val fresh = c.check(listOf(fdd), slot = 0)
        assertEquals(FeatureStatus.AlreadyDisabled, fresh.statuses[fdd.id])
        val result = preservedOriginals(fresh, mapOf(fdd.id to listOf<List<Int>?>(null)))
        assertEquals(listOf<List<Int>?>(null), result[fdd.id])
    }

    @Test
    fun `preserved originals keep the fresh capture when the feature can be disabled`() = runBlocking {
        // Provenance is stale here (the item was deleted externally after the
        // disable), so the fresh capture wins.
        val c = FeatureChecker(FakeAccess())
        val fresh = c.check(listOf(fdd), slot = 0)
        assertEquals(FeatureStatus.CanDisable, fresh.statuses[fdd.id])
        val result = preservedOriginals(fresh, mapOf(fdd.id to listOf<List<Int>?>(listOf(1, 1))))
        assertEquals(listOf<List<Int>?>(null), result[fdd.id])
    }

    @Test
    fun `preserved originals keep the fresh capture without previous provenance`() = runBlocking {
        val c = FeatureChecker(FakeAccess(path to listOf(0, 0)))
        val fresh = c.check(listOf(fdd), slot = 0)
        assertEquals(FeatureStatus.AlreadyDisabled, fresh.statuses[fdd.id])
        val result = preservedOriginals(fresh, emptyMap())
        assertEquals(fresh.originals[fdd.id], result[fdd.id])
    }

    @Test
    fun `preserved originals merge per feature and drop unrelated ids`() = runBlocking {
        val c = FeatureChecker(FakeAccess(path to listOf(0, 0), dlPath to listOf(0)))
        val fresh = c.check(listOf(fdd, dl), slot = 0)
        assertEquals(FeatureStatus.AlreadyDisabled, fresh.statuses[fdd.id])
        assertEquals(FeatureStatus.CanDisable, fresh.statuses[dl.id])
        val previous = mapOf(
            fdd.id to listOf<List<Int>?>(null),
            "not_a_feature" to listOf<List<Int>?>(listOf(7)),
        )
        val result = preservedOriginals(fresh, previous)
        assertEquals(listOf<List<Int>?>(null), result[fdd.id])
        assertEquals(listOf<List<Int>?>(listOf(0)), result[dl.id])
        assertEquals(setOf(fdd.id, dl.id), result.keys)
    }
}
