package com.douai.module.feature;

import java.util.Locale;

/**
 * Parses a single HTTP Range request header into the byte window that has to be served.
 *
 * <p>The parser follows RFC 9110 section 14.1.2 and only accepts the single-range form.
 * Multi-range requests are rejected on purpose: the local proxy serves one contiguous
 * window per request so the upstream download stays predictable and cacheable.
 */
final class VideoRangeRequest {
    /** Sentinel returned when the request does not carry any usable range. */
    static final long UNSATISFIABLE = -1L;

    final long start;
    final long end;

    private VideoRangeRequest(long start, long end) {
        this.start = start;
        this.end = end;
    }

    /**
     * Parses a {@code Range} header value.
     *
     * @param header raw header value, may be {@code null}
     * @param contentLength size of the upstream resource in bytes
     * @return the requested window, or {@link #UNSATISFIABLE} when the range is missing or invalid
     */
    static VideoRangeRequest parse(String header, long contentLength) {
        if (header == null) return null;
        String value = header.trim();
        if (value.isEmpty()) return null;
        if (!value.regionMatches(true, 0, "bytes=", 0, 6)) return null;
        String spec = value.substring(6).trim();
        // Multiple ranges are not served; a comma means the client wants a multipart body.
        if (spec.indexOf(',') >= 0) return unsatisfiable();
        int dash = spec.indexOf('-');
        if (dash < 0) return unsatisfiable();
        String rawStart = spec.substring(0, dash).trim();
        String rawEnd = spec.substring(dash + 1).trim();
        if (rawStart.isEmpty()) {
            // Suffix form: the last N bytes are requested.
            if (rawEnd.isEmpty()) return unsatisfiable();
            long suffix;
            try {
                suffix = Long.parseLong(rawEnd);
            } catch (NumberFormatException ignored) {
                return unsatisfiable();
            }
            if (suffix <= 0L) return unsatisfiable();
            if (contentLength <= 0L) return unsatisfiable();
            long start = Math.max(0L, contentLength - suffix);
            return new VideoRangeRequest(start, contentLength - 1L);
        }
        long start;
        try {
            start = Long.parseLong(rawStart);
        } catch (NumberFormatException ignored) {
            return unsatisfiable();
        }
        if (start < 0L) return unsatisfiable();
        long end;
        if (rawEnd.isEmpty()) {
            end = contentLength - 1L;
        } else {
            try {
                end = Long.parseLong(rawEnd);
            } catch (NumberFormatException ignored) {
                return unsatisfiable();
            }
        }
        if (contentLength > 0L && start >= contentLength) return unsatisfiable();
        if (end < start) return unsatisfiable();
        if (contentLength > 0L && end > contentLength - 1L) end = contentLength - 1L;
        return new VideoRangeRequest(start, end);
    }

    private static VideoRangeRequest unsatisfiable() {
        return new VideoRangeRequest(UNSATISFIABLE, UNSATISFIABLE);
    }

    boolean isUnsatisfiable() {
        return start < 0L;
    }

    /** First byte offset of the requested window. */
    long start() {
        return start;
    }

    /** Last byte offset of the requested window, inclusive. */
    long end() {
        return end;
    }

    long length() {
        return end - start + 1L;
    }

    /** Builds the {@code Content-Range} response header for this window. */
    String contentRange(long contentLength) {
        String total = contentLength > 0L ? Long.toString(contentLength) : "*";
        return "bytes " + start + "-" + end + "/" + total;
    }

    /** Builds the {@code Accept-Ranges} response header advertised by the proxy. */
    static String acceptRanges() {
        return "bytes";
    }

    @Override
    public String toString() {
        return "VideoRangeRequest[" + start + ".." + end + "]";
    }

    static String normaliseHeaderName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
