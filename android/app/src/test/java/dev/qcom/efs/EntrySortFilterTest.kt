package dev.qcom.efs

import org.junit.Assert.assertEquals
import org.junit.Test

class EntrySortFilterTest {

    private fun e(name: String, size: Int = 0, mtime: Int = 0, type: String = "file") =
        EfsEntry(name, type, 0, size, 0, mtime, 0, 0)

    private val sample = listOf(
        e("b.txt", size = 30, mtime = 200),
        e("dir2", size = 0, mtime = 100, type = "dir"),
        e("a.txt", size = 100, mtime = 300),
        e("DIR", size = 0, mtime = 50, type = "dir"),
        e("c.txt", size = 10, mtime = 0),
    )

    // ---- filterByName ----

    @Test fun `blank query keeps every entry`() {
        assertEquals(sample, sample.filterByName(""))
        assertEquals(sample, sample.filterByName("   "))
    }

    @Test fun `query matches case-insensitively`() {
        assertEquals(listOf("dir2", "DIR"), sample.filterByName("dir").map { it.name })
    }

    @Test fun `query that matches nothing returns an empty list`() {
        assertEquals(emptyList<EfsEntry>(), sample.filterByName("zzz"))
    }

    // ---- sortedBy: NAME ----

    @Test fun `name sort is dirs-first then case-insensitive`() {
        assertEquals(
            listOf("DIR", "dir2", "a.txt", "b.txt", "c.txt"),
            sample.sortedBy(SortKey.NAME, descending = false).map { it.name },
        )
    }

    @Test fun `name sort descending keeps dirs first`() {
        assertEquals(
            listOf("dir2", "DIR", "c.txt", "b.txt", "a.txt"),
            sample.sortedBy(SortKey.NAME, descending = true).map { it.name },
        )
    }

    // ---- sortedBy: SIZE ----

    @Test fun `size sort puts the biggest files first, dirs still first`() {
        assertEquals(
            listOf("DIR", "dir2", "a.txt", "b.txt", "c.txt"),
            sample.sortedBy(SortKey.SIZE, descending = true).map { it.name },
        )
    }

    @Test fun `size sort ascending puts the smallest files first`() {
        assertEquals(
            listOf("DIR", "dir2", "c.txt", "b.txt", "a.txt"),
            sample.sortedBy(SortKey.SIZE, descending = false).map { it.name },
        )
    }

    // ---- sortedBy: DATE ----

    @Test fun `date sort newest first, zero timestamps count as oldest`() {
        assertEquals(
            listOf("dir2", "DIR", "a.txt", "b.txt", "c.txt"),
            sample.sortedBy(SortKey.DATE, descending = true).map { it.name },
        )
    }

    @Test fun `date sort oldest first puts zero timestamps at the top of the files`() {
        assertEquals(
            listOf("DIR", "dir2", "c.txt", "b.txt", "a.txt"),
            sample.sortedBy(SortKey.DATE, descending = false).map { it.name },
        )
    }

    // ---- combined and determinism ----

    @Test fun `filter then sort composes`() {
        val out = sample.filterByName("txt").sortedBy(SortKey.SIZE, descending = true)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), out.map { it.name })
    }

    @Test fun `ties fall back to name order`() {
        val ties = listOf(e("z", size = 5), e("a", size = 5), e("m", size = 5))
        assertEquals(listOf("a", "m", "z"), ties.sortedBy(SortKey.SIZE, false).map { it.name })
    }
}
