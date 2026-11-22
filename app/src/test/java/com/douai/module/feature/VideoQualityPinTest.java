package com.douai.module.feature;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Ported from douyim's pinning order; runs on the plain JVM so no Robolectric is involved. */
public final class VideoQualityPinTest {

    @Test public void exactHeightWins() {
        assertEquals(1, NativeVideoQuality.pickIndex(new int[]{540, 720, 1080}, 3, 720));
    }

    @Test public void fallsBackToTallestBelowTarget() {
        assertEquals(1, NativeVideoQuality.pickIndex(new int[]{540, 720, 1080}, 3, 900));
    }

    @Test public void fallsBackToShortestAboveTargetWhenNothingIsLower() {
        // Nothing at or below 480 → the shortest level above it (540) is the honest fallback.
        assertEquals(0, NativeVideoQuality.pickIndex(new int[]{540, 1080, 2160}, 3, 480));
    }

    @Test public void ignoresAutoLevelsReportedAsZero() {
        assertEquals(1, NativeVideoQuality.pickIndex(new int[]{0, 720, 0}, 3, 720));
    }

    @Test public void unusableInputsStayNative() {
        assertEquals(-1, NativeVideoQuality.pickIndex(new int[]{0, 0}, 2, 720));
        assertEquals(-1, NativeVideoQuality.pickIndex(null, 0, 720));
        assertEquals(-1, NativeVideoQuality.pickIndex(new int[]{720}, 1, 0));
    }

    @Test public void constantNameMappingCoversEveryPublishedLevel() {
        assertEquals(2160, NativeVideoQuality.heightByName("Manual_Extremly_High"));
        assertEquals(1440, NativeVideoQuality.heightByName("Manual_Super_High"));
        assertEquals(1080, NativeVideoQuality.heightByName("Manual_High"));
        assertEquals(720, NativeVideoQuality.heightByName("Manual_Sub_High"));
        assertEquals(540, NativeVideoQuality.heightByName("Manual_Standard"));
        assertEquals(480, NativeVideoQuality.heightByName("Manual_SubStandard"));
        assertEquals(360, NativeVideoQuality.heightByName("Manual_Low"));
        assertEquals(0, NativeVideoQuality.heightByName("Manual_Auto"));
    }
}
