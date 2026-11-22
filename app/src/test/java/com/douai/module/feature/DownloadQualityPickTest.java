package com.douai.module.feature;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Ported from douyim's gear ordering: closest height first, then the most compatible codec. */
public final class DownloadQualityPickTest {
    private static final String H264 = "https://h264-720.mp4";
    private static final String HEVC = "https://hevc-720.mp4";
    private static final String H1080 = "https://hevc-1080.mp4";
    private static final String H540 = "https://h264-540.mp4";

    @Test public void exactHeightWins() {
        assertEquals(H264, DownloadActionHook.pickSource(720, H1080, 1080, 2, H264, 720, 0));
    }

    @Test public void closestHeightWinsOverCodec() {
        // 540 is nearer to 720 than 1080, even though 1080 is also available.
        assertEquals(H540, DownloadActionHook.pickSource(720, H1080, 1080, 2, H540, 540, 0));
    }

    @Test public void tieOnHeightPrefersH264() {
        assertEquals(H264, DownloadActionHook.pickSource(720, HEVC, 720, 2, H264, 720, 0));
    }

    @Test public void missingHeightsFallBackToCodec() {
        assertEquals(H264, DownloadActionHook.pickSource(720, HEVC, 0, 2, H264, 0, 0));
    }

    @Test public void targetAboveAllPicksTallest() {
        assertEquals(H1080, DownloadActionHook.pickSource(2160, H540, 540, 0, H1080, 1080, 2));
    }

    @Test public void nothingUsableReturnsNull() {
        assertEquals(null, DownloadActionHook.pickSource(0, H264, 720, 0));
    }
}
