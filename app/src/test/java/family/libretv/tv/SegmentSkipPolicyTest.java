package family.libretv.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SegmentSkipPolicyTest {
    @Test public void keysShareAcrossSourcesButKeepSeasonAndVersion() {
        assertEquals(SegmentSkipPolicy.titleKey("山海 之间"), SegmentSkipPolicy.titleKey("山海　之间"));
        assertNotEquals(SegmentSkipPolicy.titleKey("山海之间 第一季"), SegmentSkipPolicy.titleKey("山海之间 第二季"));
        assertNotEquals(SegmentSkipPolicy.titleKey("山海之间"), SegmentSkipPolicy.titleKey("山海之间 导演版"));
        assertEquals("", SegmentSkipPolicy.titleKey(null));
    }
    @Test public void introDoesNotReplaceLaterResumePosition() {
        assertEquals(90000, SegmentSkipPolicy.introTarget(0, 90000, 120000, 2700000));
        assertEquals(-1, SegmentSkipPolicy.introTarget(1200000, 90000, 120000, 2700000));
    }
    @Test public void tailUsesRemainingTimeForDifferentEpisodeLengths() {
        assertFalse(SegmentSkipPolicy.inOutro(2579999, 90000, 120000, 2700000));
        assertTrue(SegmentSkipPolicy.inOutro(2580000, 90000, 120000, 2700000));
        assertTrue(SegmentSkipPolicy.inOutro(2280000, 90000, 120000, 2400000));
        assertFalse(SegmentSkipPolicy.inOutro(2400000, 90000, 0, 2400000));
    }
    @Test public void unknownAndShortOrOverlappingEpisodesNeverSkip() {
        for (long duration : new long[]{-9223372036854775807L, 0, 90000, 200000, 210000}) {
            assertEquals(-1, SegmentSkipPolicy.introTarget(0, 90000, 120000, duration));
            assertFalse(SegmentSkipPolicy.inOutro(180000, 90000, 120000, duration));
        }
        assertFalse(SegmentSkipPolicy.valid(-1, 1000, 60000));
        assertFalse(SegmentSkipPolicy.valid(1000, Long.MAX_VALUE, 60000));
    }
}
