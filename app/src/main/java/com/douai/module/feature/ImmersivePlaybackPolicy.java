package com.douai.module.feature;

import android.view.View;

import java.util.Locale;

/**
 * Pure decision helpers for immersive playback cleanup.
 *
 * <p>Ported from douyim {@code ImmersiveTransitionPolicy} and {@code DanmakuViewClassifier}.
 * Kept free of hook and host dependencies so it stays unit-testable.
 */
final class ImmersivePlaybackPolicy {
    /** Cadence of the overlay sweep while a playback page is in front. */
    static final long SCAN_INTERVAL_MS = 100L;
    /** Slower cadence used when no media layer is visible yet. */
    static final long FALLBACK_CONTENT_CHECK_INTERVAL_MS = 900L;
    /** Transitions keep already hidden overlays hidden instead of flashing them back. */
    static final long UI_TRANSITION_HOLD_MS = 3_000L;

    private static final String ULTRA_PACKAGE = "com.bytedance.common.ultra.danmaku";
    private static final String CONTAINER =
            "com.bytedance.common.ultra.danmaku.view.DanmakuAsyncRenderParent";

    private ImmersivePlaybackPolicy() { }

    static boolean shouldHoldHiddenUi(boolean hasHiddenViews, long now, long transitionUntil) {
        return hasHiddenViews && now < transitionUntil;
    }

    /**
     * Danmaku views must survive the overlay sweep, otherwise bullets disappear together with the
     * chrome. Several renderers live under one container (compose, Skity, legacy canvas), so the
     * check cannot be narrowed to a single class name.
     */
    static boolean isDanmakuLayer(View view) {
        if (view == null) return false;
        String name;
        try {
            name = view.getClass().getName();
        } catch (Throwable ignored) {
            return false;
        }
        return isDanmakuLayerName(name);
    }

    static boolean isDanmakuContainer(View view) {
        if (view == null) return false;
        try {
            return CONTAINER.equals(view.getClass().getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isDanmakuLayerName(String className) {
        if (className == null || !className.startsWith(ULTRA_PACKAGE)) return false;
        String simpleName = className.substring(ULTRA_PACKAGE.length()).toLowerCase(Locale.ROOT);
        return (simpleName.endsWith("view") || simpleName.endsWith("parent"))
                && !simpleName.contains("permission")
                && !simpleName.contains("setting")
                && !simpleName.contains("input")
                && !simpleName.contains("panel");
    }
}
