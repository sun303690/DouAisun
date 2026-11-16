package com.douai.module.feature;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks the original (highest bitrate) playback source of a Douyin work.
 *
 * <p>Only plain, unencrypted HTTP(S) video sources are eligible. Douyin also serves encrypted and
 * DRM protected variants; those are skipped instead of being proxied, because the proxy cannot
 * decrypt them.
 */
final class VideoProxySource {

    private VideoProxySource() {
    }

    /**
     * A single playback candidate together with the bitrate it was announced with.
     */
    static final class Candidate {
        /** The remote media URL. */
        final String url;
        /** The announced bitrate in bits per second, {@code 0} when unknown. */
        final long bitrate;

        Candidate(String url, long bitrate) {
            this.url = url;
            this.bitrate = bitrate;
        }
    }

    /**
     * Returns the highest bitrate plain video URL of {@code aweme}, or {@code null}.
     *
     * @param aweme the host work model
     * @return the remote URL of the best candidate, or {@code null} when the work has no
     *         proxyable video source
     */
    static String originalUrl(Object aweme) {
        List<Candidate> candidates = candidates(aweme);
        if (candidates.isEmpty()) return null;
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (best == null || candidate.bitrate > best.bitrate) best = candidate;
        }
        // A candidate without a known bitrate only wins when every candidate lacks one.
        if (best.bitrate <= 0L) {
            for (Candidate candidate : candidates) {
                if (candidate.bitrate > 0L) return best.url;
            }
        }
        return best.url;
    }

    /**
     * Collects every proxyable playback candidate of {@code aweme}.
     *
     * @param aweme the host work model
     * @return the candidates, ordered as the host model exposes them
     */
    static List<Candidate> candidates(Object aweme) {
        List<Candidate> result = new ArrayList<>();
        if (aweme == null) return result;
        Object video = DownloadActionHook.jsonValue(aweme, "video");
        if (video == null) return result;
        collectBitRates(video, result);
        collectDirectSources(video, result);
        return result;
    }

    private static void collectBitRates(Object video, List<Candidate> out) {
        Object bitRates = DownloadActionHook.jsonValue(video, "bit_rate");
        if (!(bitRates instanceof Iterable<?>)) return;
        for (Object bitRate : (Iterable<?>) bitRates) {
            if (bitRate == null) continue;
            Object bitrateValue = DownloadActionHook.jsonValue(bitRate, "bit_rate");
            long bitrate = bitrateValue instanceof Number ? ((Number) bitrateValue).longValue() : 0L;
            for (String key : new String[]{"play_addr", "play_addr_265", "play_addr_bytevc1"}) {
                String url = firstUrl(DownloadActionHook.jsonValue(bitRate, key));
                if (url != null && isPlain(url)) out.add(new Candidate(url, bitrate));
            }
        }
    }

    private static void collectDirectSources(Object video, List<Candidate> out) {
        for (String key : new String[]{"play_addr", "play_addr_h264", "play_addr_265",
                "play_addr_bytevc1", "play_addr_lowbr"}) {
            String url = firstUrl(DownloadActionHook.jsonValue(video, key));
            if (url != null && isPlain(url)) out.add(new Candidate(url, 0L));
        }
    }

    private static String firstUrl(Object value) {
        if (value == null) return null;
        Object raw = value instanceof Iterable<?> ? value : DownloadActionHook.jsonValue(value, "url_list");
        if (!(raw instanceof Iterable<?>)) return null;
        for (Object item : (Iterable<?>) raw) {
            if (item instanceof String && isPlain((String) item)) return (String) item;
        }
        return null;
    }

    /** True when the URL is a plain HTTP(S) video source that the proxy can relay. */
    static boolean isPlain(String url) {        if (url == null || url.isEmpty()) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("encrypt") || lower.contains("drm")) return false;
        return !lower.contains(".m3u8");
    }
}
