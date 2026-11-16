package com.douai.module.feature;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the playback URL inside a Douyin video model.
 *
 * <p>The host keeps the media addresses in {@code url_list} collections that are exposed through
 * generated getters. Writing the replacement back therefore means locating the collection that
 * belongs to the address model and replacing its contents in place, because the surrounding
 * objects are shared with the already created players.
 */
final class VideoProxyRewriter {

    private VideoProxyRewriter() {
    }

    /**
     * Replaces every occurrence of {@code upstream} inside {@code aweme} with {@code replacement}.
     *
     * <p>The rewrite walks the video address models of the work, so the download addresses and
     * the cover addresses stay untouched.
     *
     * @param aweme the host work model
     * @param upstream the URL that was selected as the original source
     * @param replacement the loopback URL that serves the same asset
     * @return the number of replaced entries
     */
    static int rewrite(Object aweme, String upstream, String replacement) {
        if (aweme == null || upstream == null || replacement == null) return 0;
        Object video = DownloadActionHook.jsonValue(aweme, "video");
        if (video == null) return 0;
        int replaced = 0;
        replaced += rewriteAddresses(video, upstream, replacement);
        Object bitRates = DownloadActionHook.jsonValue(video, "bit_rate");
        if (bitRates instanceof Iterable<?>) {
            for (Object bitRate : (Iterable<?>) bitRates) {
                if (bitRate != null) replaced += rewriteAddresses(bitRate, upstream, replacement);
            }
        }
        return replaced;
    }

    private static int rewriteAddresses(Object owner, String upstream, String replacement) {
        int replaced = 0;
        for (String key : new String[]{"play_addr", "play_addr_265", "play_addr_bytevc1", "play_addr_h264"}) {
            Object address = DownloadActionHook.jsonValue(owner, key);
            replaced += rewriteUrlList(address, upstream, replacement);
        }
        return replaced;
    }

    private static int rewriteUrlList(Object address, String upstream, String replacement) {
        if (address == null) return 0;
        int replaced = 0;
        Object uri = DownloadActionHook.jsonValue(address, "uri");
        if (upstream.equals(uri)) {
            replaced += writeBackScalar(address, "uri", replacement);
        }
        Object urls = DownloadActionHook.jsonValue(address, "url_list");
        if (!(urls instanceof Iterable<?>)) return replaced;
        List<Object> replacementList = new ArrayList<>();
        int listHits = 0;
        for (Object item : (Iterable<?>) urls) {
            if (upstream.equals(item)) {
                replacementList.add(replacement);
                listHits++;
            } else {
                replacementList.add(item);
            }
        }
        if (listHits == 0) return replaced;
        return replaced + writeBack(address, "url_list", replacementList);
    }

    /** Writes a scalar {@code String} field, returning 1 when the write succeeded. */
    private static int writeBackScalar(Object owner, String jsonName, String value) {
        Field field = DownloadActionHook.findFieldByJsonName(owner.getClass(), jsonName);
        if (field == null) return 0;
        try {
            field.setAccessible(true);
            if (field.getType() != String.class) return 0;
            field.set(owner, value);
            return 1;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return 0;
        }
    }

    /**
     * Writes {@code value} back into the {@code jsonName} field of {@code owner}.
     *
     * <p>Collection fields are replaced by their contents whenever the field accepts a
     * {@link List}, which is how the host models store the address lists.
     */
    private static int writeBack(Object owner, String jsonName, List<Object> value) {
        Field field = DownloadActionHook.findFieldByJsonName(owner.getClass(), jsonName);
        if (field == null) return 0;
        try {
            field.setAccessible(true);
            Object current = field.get(owner);
            if (current instanceof List<?>) {
                List<Object> list = castList(current);
                list.clear();
                list.addAll(value);
                return value.size();
            }
            field.set(owner, value);
            return value.size();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }
}
