package com.douai.module.feature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Maps an upstream media URL onto a stable cache file name.
 *
 * <p>The mapping has to stay stable across process restarts, otherwise the proxy would
 * re-download the same video on every launch. Douyin signs the same asset with a fresh
 * signature on every request, so the volatile query parameters are removed before the
 * digest is taken.
 */
final class VideoCacheKey {
    private VideoCacheKey() { }

    /**
     * Computes the cache key for a media URL.
     *
     * @param url upstream media URL
     * @return a hex digest usable as a file name, or {@code null} for a blank URL
     */
    static String keyFor(String url) {
        String normalised = normalise(url);
        if (normalised.isEmpty()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalised.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(Character.forDigit((value >> 4) & 0xf, 16));
                builder.append(Character.forDigit(value & 0xf, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException failure) {
            // SHA-256 is guaranteed on every Android release, but stay defensive.
            return String.valueOf(normalised.hashCode());
        }
    }

    /**
     * Builds the cache file name for a key.
     *
     * @param key cache key, may be {@code null}
     * @return the file name, or {@code null} when the key is missing
     */
    static String fileNameFor(String key) {
        if (key == null || key.isEmpty()) return null;
        return key + ".mp4";
    }

    /**
     * Removes the volatile parts of a media URL so that the same asset maps to the same key.
     */
    static String normalise(String url) {
        if (url == null) return "";
        String value = url.trim();
        if (value.isEmpty()) return "";
        int schemeEnd = value.indexOf("://");
        if (schemeEnd < 0) return value.toLowerCase(Locale.ROOT);
        int pathStart = value.indexOf('/', schemeEnd + 3);
        String scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (pathStart < 0) return scheme + "://" + value.substring(schemeEnd + 3).toLowerCase(Locale.ROOT);
        String hostAndPort = value.substring(schemeEnd + 3, pathStart).toLowerCase(Locale.ROOT);
        String path = value.substring(pathStart);
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        return scheme + "://" + hostAndPort + path;
    }
}