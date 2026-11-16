package com.douai.module.feature;

import java.util.Locale;

/**
 * Decides which media files may be stored on disk and how much space they may use.
 *
 * <p>The rules are kept separate from the proxy server so they can be verified without
 * starting a socket.
 */
final class VideoProxyCachePolicy {

    /** Bytes in one megabyte. */
    private static final long MEGABYTE = 1024L * 1024L;

    private final long budgetBytes;
    private final boolean wifiOnly;
    private final boolean enabled;

    /**
     * Creates a policy.
     *
     * @param enabled whether caching is enabled at all
     * @param budgetMb the cache budget in megabytes, {@code 0} disables caching
     * @param wifiOnly whether caching is limited to unmetered networks
     */
    VideoProxyCachePolicy(boolean enabled, int budgetMb, boolean wifiOnly) {
        this.enabled = enabled && budgetMb > 0;
        this.budgetBytes = Math.max(0L, budgetMb) * MEGABYTE;
        this.wifiOnly = wifiOnly;
    }

    /** True when files may be written to disk right now. */
    boolean allowsCaching(boolean unmetered) {
        if (!enabled) return false;
        if (wifiOnly && !unmetered) return false;
        return true;
    }

    /** The maximum number of bytes the cache may use. */
    long budgetBytes() {
        return budgetBytes;
    }

    /** True when the policy stores anything at all. */
    boolean isEnabled() {
        return enabled;
    }

    /** True when the policy only caches on unmetered networks. */
    boolean isWifiOnly() {
        return wifiOnly;
    }

    /**
     * Renders the budget for the settings screen.
     *
     * @param budgetMb the configured budget in megabytes
     * @return a human readable label
     */
    static String describe(int budgetMb) {
        if (budgetMb <= 0) return "不缓存";
        return budgetMb + " MB";
    }

    /**
     * Parses a configured budget.
     *
     * @param raw the stored value, may be {@code null}
     * @return the budget in megabytes, {@code 0} when caching is disabled
     */
    static int parseBudgetMb(String raw) {
        if (raw == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Parses a configured concurrency limit.
     *
     * @param raw the stored value, may be {@code null}
     * @return the number of upstream connections to allow
     */
    static int parseConnections(String raw) {
        if (raw == null) return 4;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) return 1;
            return Math.min(value, 8);
        } catch (NumberFormatException ignored) {
            return 4;
        }
    }

    /** Renders a connection count for the settings screen. */
    static String describeConnections(int connections) {
        return connections + " 个连接";
    }

    /** True when the given URL points at a media file that may be cached. */
    static boolean isCacheableMedia(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        return lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".mkv");
    }
}
