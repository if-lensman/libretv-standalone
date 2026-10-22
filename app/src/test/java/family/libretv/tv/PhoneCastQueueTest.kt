package family.libretv.tv

import org.junit.Assert.*
import org.junit.Test

class PhoneCastQueueTest {
    @Test fun navigationHasBoundariesAndDoesNotMutateOnAttempt() {
        val first = PhoneCastQueue("A", listOf("one", "two", "three"), 0)
        assertNull(first.adjacent(-1))
        assertEquals(1, first.adjacent(1))
        val second = first.at(first.adjacent(1)!!)
        assertEquals(0, first.index)
        assertEquals(0, second.adjacent(-1))
        assertNull(second.at(2).adjacent(1))
        // Browsing another queue cannot change the cast snapshot.
        PhoneCastQueue("B", listOf("other"), 0)
        assertEquals("A · 第 2 集", second.label)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidRestoredIndex() {
        PhoneCastQueue("A", listOf("one"), 4)
    }
}
