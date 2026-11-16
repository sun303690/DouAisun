package com.douai.module.feature;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the range parsing used by the local video proxy.
 */
public class VideoRangeRequestTest {

    @Test
    public void emptyRangeMeansWholeResponse() {
        assertNull(VideoRangeRequest.parse(null, 1000L));
        assertNull(VideoRangeRequest.parse("", 1000L));
        assertNull(VideoRangeRequest.parse("   ", 1000L));
    }

    @Test
    public void openEndedRangeStartsAtZero() {
        VideoRangeRequest request = VideoRangeRequest.parse("bytes=0-", 1000L);
        assertNotNull(request);
        assertEquals(0L, request.start());
        assertEquals(1000L, request.length());
    }

    @Test
    public void closedRangeIsInclusive() {
        VideoRangeRequest request = VideoRangeRequest.parse("bytes=100-199", 1000L);
        assertNotNull(request);
        assertEquals(100L, request.start());
        assertEquals(100L, request.length());
    }

    @Test
    public void suffixRangeKeepsTheTail() {
        VideoRangeRequest request = VideoRangeRequest.parse("bytes=-500", 1000L);
        assertNotNull(request);
        assertEquals(500L, request.start());
        assertEquals(500L, request.length());
    }

    @Test
    public void suffixRangeLargerThanContentIsClamped() {
        VideoRangeRequest request = VideoRangeRequest.parse("bytes=-5000", 1000L);
        assertNotNull(request);
        assertEquals(0L, request.start());
        assertEquals(1000L, request.length());
    }

    @Test
    public void rangeBeyondContentIsRejected() {
        VideoRangeRequest request = VideoRangeRequest.parse("bytes=5000-6000", 1000L);
        assertNotNull(request);
        assertTrue(request.isUnsatisfiable());
    }

    @Test
    public void rangeBeyondKnownLengthIsClampedToTheEnd() {
        VideoRangeRequest request = VideoRangeRequest.parse("bytes=900-6000", 1000L);
        assertNotNull(request);
        assertFalse(request.isUnsatisfiable());
        assertEquals(900L, request.start());
        assertEquals(100L, request.length());
    }

    @Test
    public void unknownLengthFallsBackToTheRequestedWindow() {
        VideoRangeRequest request = VideoRangeRequest.parse("bytes=0-199", -1L);
        assertNotNull(request);
        assertEquals(200L, request.length());
    }

    @Test
    public void malformedRangeIsRejected() {
        assertNull(VideoRangeRequest.parse("items=0-10", 1000L));
        assertEquals(VideoRangeRequest.UNSATISFIABLE,
                VideoRangeRequest.parse("bytes=abc-def", 1000L).start());
        assertEquals(VideoRangeRequest.UNSATISFIABLE,
                VideoRangeRequest.parse("bytes=", 1000L).start());
    }

    @Test
    public void contentRangeUsesTheTotalLengthWhenKnown() {
        VideoRangeRequest request = VideoRangeRequest.parse("bytes=100-199", 1000L);
        assertNotNull(request);
        assertEquals("bytes 100-199/1000", request.contentRange(1000L));
    }

    @Test
    public void contentRangeOmitsTheTotalWhenUnknown() {
        VideoRangeRequest request = VideoRangeRequest.parse("bytes=100-199", -1L);
        assertNotNull(request);
        assertEquals("bytes 100-199/*", request.contentRange(-1L));
    }

    @Test
    public void headerNamesAreNormalisedToLowerCase() {
        assertEquals("range", VideoRangeRequest.normaliseHeaderName("Range"));
        assertEquals("range", VideoRangeRequest.normaliseHeaderName("RANGE"));
        assertEquals("accept", VideoRangeRequest.normaliseHeaderName("Accept"));
    }
}
