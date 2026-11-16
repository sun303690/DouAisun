package com.douai.module.feature;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies the cache rules of the local video proxy.
 */
public class VideoProxyCachePolicyTest {

    @Test public void budgetOfZeroDisablesCaching() {
        VideoProxyCachePolicy policy = new VideoProxyCachePolicy(true, 0, false);
        assertFalse(policy.isEnabled());
        assertFalse(policy.allowsCaching(true));
        assertEquals(0L, policy.budgetBytes());
    }

    @Test public void disabledPolicyNeverCaches() {
        VideoProxyCachePolicy policy = new VideoProxyCachePolicy(false, 512, false);
        assertFalse(policy.isEnabled());
        assertFalse(policy.allowsCaching(true));
        assertFalse(policy.allowsCaching(false));
    }

    @Test public void enabledPolicyCachesOnAnyNetwork() {
        VideoProxyCachePolicy policy = new VideoProxyCachePolicy(true, 512, false);
        assertTrue(policy.isEnabled());
        assertTrue(policy.allowsCaching(true));
        assertTrue(policy.allowsCaching(false));
        assertEquals(512L * 1024L * 1024L, policy.budgetBytes());
    }

    @Test public void wifiOnlyPolicySkipsMeteredNetworks() {
        VideoProxyCachePolicy policy = new VideoProxyCachePolicy(true, 256, true);
        assertTrue(policy.isWifiOnly());
        assertTrue(policy.allowsCaching(true));
        assertFalse(policy.allowsCaching(false));
    }

    @Test public void budgetParsingFallsBackToDisabled() {
        assertEquals(0, VideoProxyCachePolicy.parseBudgetMb(null));
        assertEquals(0, VideoProxyCachePolicy.parseBudgetMb(""));
        assertEquals(0, VideoProxyCachePolicy.parseBudgetMb("not a number"));
        assertEquals(0, VideoProxyCachePolicy.parseBudgetMb("-8"));
    }

    @Test public void budgetParsingKeepsValidValues() {
        assertEquals(128, VideoProxyCachePolicy.parseBudgetMb("128"));
        assertEquals(512, VideoProxyCachePolicy.parseBudgetMb(" 512 "));
    }

    @Test public void budgetDescriptionLabelsDisabledBudget() {
        assertEquals("不缓存", VideoProxyCachePolicy.describe(0));
        assertEquals("512 MB", VideoProxyCachePolicy.describe(512));
    }

    @Test public void connectionParsingFallsBackToFour() {
        assertEquals(4, VideoProxyCachePolicy.parseConnections(null));
        assertEquals(4, VideoProxyCachePolicy.parseConnections(""));
        assertEquals(4, VideoProxyCachePolicy.parseConnections("many"));
    }

    @Test public void connectionParsingClampsToTheAllowedRange() {
        assertEquals(1, VideoProxyCachePolicy.parseConnections("0"));
        assertEquals(1, VideoProxyCachePolicy.parseConnections("-3"));
        assertEquals(8, VideoProxyCachePolicy.parseConnections("99"));
        assertEquals(2, VideoProxyCachePolicy.parseConnections("2"));
    }

    @Test public void connectionDescriptionCountsConnections() {
        assertEquals("4 个连接", VideoProxyCachePolicy.describeConnections(4));
    }

    @Test public void onlyDirectVideoFilesAreCacheable() {
        assertTrue(VideoProxyCachePolicy.isCacheableMedia("https://cdn.example.com/a.mp4"));
        assertTrue(VideoProxyCachePolicy.isCacheableMedia("https://cdn.example.com/a.mp4?token=1"));
        assertTrue(VideoProxyCachePolicy.isCacheableMedia("https://cdn.example.com/A.MP4"));
        assertTrue(VideoProxyCachePolicy.isCacheableMedia("https://cdn.example.com/a.m4v"));
        assertFalse(VideoProxyCachePolicy.isCacheableMedia("https://cdn.example.com/a.m3u8"));
        assertFalse(VideoProxyCachePolicy.isCacheableMedia("https://cdn.example.com/a.jpg"));
        assertFalse(VideoProxyCachePolicy.isCacheableMedia(null));
    }
}
