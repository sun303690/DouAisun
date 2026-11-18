package com.douai.module.config;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable host-local snapshot for independently registered feature options. */
public final class LocalOptions {
    /** Reserves feed long presses for module actions and blocks all native long-press regions. */
    public static final String INTERCEPT_NATIVE_LONG_PRESS = "intercept_native_long_press";
    public static final String MODULE_ONLY_SPEED = "module_only_speed";
    public static final String LOCK_HIGHEST_VIDEO_QUALITY = "lock_highest_video_quality";
    public static final String DOWNLOAD_SAVE_TREE = "download_directory_uri";
    /** Sub-folder created under the album/DCIM tree for every saved media file. */
    public static final String DOWNLOAD_SUBDIRECTORY = "download_subdirectory";
    public static final String OFFICIAL_DOWNLOAD_DIRECT = "download_official_direct";
    public static final String REPLY_VOICE = "reply_voice";
    public static final String REPLY_RECOGNIZE_IMAGES = "reply_recognize_images";
    public static final String REPLY_RECOGNIZE_VIDEO = "reply_recognize_video";
    public static final String COMMENT_VOICE = "comment_voice";
    public static final String COMMENT_HIDE_DIGG = "comment_hide_digg";
    public static final String COMMENT_HIDE_BURY = "comment_hide_bury";
    public static final String COMMENT_HIDE_REPLY = "comment_hide_reply";
    public static final String COMMENT_HIDE_INPUT = "comment_hide_input";
    public static final String COMMENT_DISABLE_ITEM_CLICK = "comment_disable_item_click";
    /** Hides the avatar of every comment row inside the native comment panel. */
    public static final String COMMENT_HIDE_AVATAR = "comment_hide_avatar";
    /** Hides the nickname of every comment row inside the native comment panel. */
    public static final String COMMENT_HIDE_NICKNAME = "comment_hide_nickname";
    /** Hides the body text of every comment row inside the native comment panel. */
    public static final String COMMENT_HIDE_TEXT = "comment_hide_text";
    /** Hides the relative timestamp of every comment row inside the native comment panel. */
    public static final String COMMENT_HIDE_TIME = "comment_hide_time";
    /** Hides the IP location label of every comment row inside the native comment panel. */
    public static final String COMMENT_HIDE_IP = "comment_hide_ip";
    /** Hides the horizontal divider drawn between comment rows. */
    public static final String COMMENT_HIDE_DIVIDER = "comment_hide_divider";
    /** Hides the vertical separator drawn beside the comment panel title. */
    public static final String COMMENT_HIDE_TITLE_SEPARATOR = "comment_hide_title_separator";
    /** Opacity applied to the whole comment panel, expressed in percent. */
    public static final String COMMENT_PANEL_OPACITY = "comment_panel_opacity";
    /** Hides the decorative watermark drawn over the comment panel. */
    public static final String COMMENT_HIDE_WATERMARK = "comment_hide_watermark";
    /** Master switch for the native comment viewer filter. */
    public static final String COMMENT_FILTER_ENABLED = "comment_view_filter_enabled";
    /** Comma separated keywords that hide matching comments. */
    public static final String COMMENT_FILTER_KEYWORDS = "comment_view_filter_keywords";
    /** Comma separated user ids whose comments are hidden. */
    public static final String COMMENT_FILTER_AUTHORS = "comment_view_filter_authors";
    /** Comma separated user ids that are always kept even when the filter matches. */
    public static final String COMMENT_FILTER_ALLOW = "comment_view_filter_allow";
    /** Hides advertisement comments. */
    public static final String COMMENT_FILTER_ADS = "comment_view_filter_ads";
    /** Hides comments the host marks as hot or highlighted. */
    public static final String COMMENT_FILTER_HOT = "comment_view_filter_hot";
    /** Hides the digg count even on the author's own comments. */
    public static final String COMMENT_HIDE_DIGG_COUNT_EXCLUDE_SELF = "comment_hide_digg_count_exclude_self";
    /** Hides the external share bar below the comment panel. */
    public static final String COMMENT_HIDE_EXTERNAL_BAR = "comment_hide_external_bar";
    /** Forces the comment counter to stay visible. */
    public static final String COMMENT_SHOW_COMMENT_COUNT = "comment_show_comment_count";
    /** Skips the avatar verification step before opening a profile. */
    public static final String COMMENT_DISABLE_AVATAR_VERIFY = "comment_disable_avatar_verify";
    /** Hides the digg count shown next to every comment. */
    public static final String COMMENT_HIDE_DIGG_COUNT = "comment_hide_digg_count";
    /** Blocks long press handling on comment rows. */
    public static final String COMMENT_DISABLE_ITEM_LONG_CLICK = "comment_disable_item_long_click";
    /** Blocks avatar clicks that normally open the author profile. */
    public static final String COMMENT_DISABLE_AVATAR_CLICK = "comment_disable_avatar_click";
    /** Forces the comment panel title to stay visible. */
    public static final String COMMENT_FORCE_SHOW_TITLE = "comment_force_show_title";
    /** Shows the reply counter on comments that carry replies. */
    public static final String COMMENT_SHOW_REPLY_COUNT = "comment_show_reply_count";
    /** Keeps the comment input bar usable even when the panel hides it. */
    public static final String COMMENT_KEEP_INPUT_BAR = "comment_keep_input_bar";
    /** Hides the image list attached to a comment. */
    public static final String COMMENT_HIDE_IMAGE_LIST = "comment_hide_image_list";
    /** Hides the label badges attached to a comment. */
    public static final String COMMENT_HIDE_LABEL_LIST = "comment_hide_label_list";
    /** Hides the hot marker on comments the host marks as hot. */
    public static final String COMMENT_HIDE_HOT = "comment_hide_hot";
    /** Hides the highlight marker on comments the host marks as highlighted. */
    public static final String COMMENT_HIDE_HIGHLIGHT = "comment_hide_highlight";
    /** Hides the author badge on comments. */
    public static final String COMMENT_HIDE_AUTHOR_BADGE = "comment_hide_author_badge";
    /** Hides the comment type marker on comments. */
    public static final String COMMENT_HIDE_COMMENT_TYPE = "comment_hide_comment_type";
    public static final String DEBUG_LOGGING = "debug_logging";
    public static final String HASH_LONG_PRESS_VOICE = "hash_long_press_voice";
    /** Receives comment replies as a channel of native_auto_reply, like private/group chat reception. */
    public static final String COMMENT_REPLY_ENABLED = "native_comment_reply";
    /** Serves the original playback source through a loopback proxy. */
    public static final String VIDEO_PROXY_ENABLED = "video_proxy_enabled";
    /** Number of upstream connections the local video proxy may open at once. */
    public static final String VIDEO_PROXY_CONNECTIONS = "video_proxy_connections";
    /** Cache budget of the local video proxy in megabytes. */
    public static final String VIDEO_PROXY_CACHE_BUDGET_MB = "video_proxy_cache_budget_mb";
    /** Limits the local video proxy cache to unmetered networks. */
    public static final String VIDEO_PROXY_WIFI_ONLY = "video_proxy_wifi_only";
    /** Shows the current live room details in a dialog. */
    public static final String LIVE_ROOM_INFO = "live_room_info";
    /** Renders the exact live viewer count instead of the short form. */
    public static final String LIVE_ROOM_EXACT_COUNT = "live_room_exact_count";
    /** Live clean mode: hides the panels so only the video remains. */
    public static final String LIVE_CLEAN = "live_clean";
    public static final String LIVE_CLEAN_GIFT_TRAY = "live_clean_gift_tray";
    public static final String LIVE_CLEAN_GIFT_EFFECT = "live_clean_gift_effect";
    public static final String LIVE_CLEAN_PUBLIC_SCREEN = "live_clean_public_screen";
    public static final String LIVE_CLEAN_RANK = "live_clean_rank";
    public static final String LIVE_CLEAN_COMMERCE = "live_clean_commerce";
    public static final String LIVE_CLEAN_AD = "live_clean_ad";

    public String downloadSaveTree() { return text(DOWNLOAD_SAVE_TREE, "").trim(); }
    /** Album sub-folder, sanitised and falling back to the historical default {@code DouAi}. */
    public String downloadSubdirectory() { return sanitiseSubdirectory(text(DOWNLOAD_SUBDIRECTORY, "")); }
    /**
     * Pure sanitiser for the album sub-folder name: strips characters that MediaStore rejects and
     * drops path separators so the value can never escape the album it is appended to.
     */
    public static String sanitiseSubdirectory(String raw) {
        if (raw == null) return DEFAULT_DOWNLOAD_SUBDIRECTORY;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return DEFAULT_DOWNLOAD_SUBDIRECTORY;
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length() && out.length() < 40; i++) {
            char c = trimmed.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < ' ') continue;
            out.append(c);
        }
        String cleaned = out.toString().trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return DEFAULT_DOWNLOAD_SUBDIRECTORY;
        }
        return cleaned;
    }
    /** Default album sub-folder used when the user leaves the field empty. */
    public static final String DEFAULT_DOWNLOAD_SUBDIRECTORY = "DouAi";
    public boolean officialDownloadDirect() { return enabled(OFFICIAL_DOWNLOAD_DIRECT); }

    public boolean samePrefix(LocalOptions other, String prefix) {
        Map<String, Object> left = new HashMap<>();
        Map<String, Object> right = new HashMap<>();
        values.forEach((key, value) -> { if (key.startsWith(prefix)) left.put(key, value); });
        other.values.forEach((key, value) -> { if (key.startsWith(prefix)) right.put(key, value); });
        return left.equals(right);
    }

    private final Map<String, ?> values;
    private volatile String diagnosticRevision;

    public LocalOptions(Map<String, ?> values) {
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    /** Revision of this immutable snapshot, independent of later preference publications. */
    public String diagnosticRevision() {
        String cached = diagnosticRevision;
        if (cached != null) return cached;
        synchronized (this) {
            if (diagnosticRevision == null) {
                diagnosticRevision = com.douai.module.diagnostics.DouAiLog.configurationRevision(values);
            }
            return diagnosticRevision;
        }
    }

    public static LocalOptions readFrom(SharedPreferences preferences) {
        return new LocalOptions(preferences.getAll());
    }

    public LocalOptions withDefault(String key, Object value) {
        if (values.containsKey(key)) return this;
        Map<String, Object> copy = new HashMap<>(values);
        copy.put(key, value);
        return new LocalOptions(copy);
    }

    public LocalOptions withOverrides(Map<String, ?> overrides) {
        Map<String, Object> copy = new HashMap<>(values);
        copy.putAll(overrides);
        return new LocalOptions(copy);
    }

    public boolean enabled(String key) { return enabled(key, false); }

    public boolean enabled(String key, boolean fallback) {
        if ((INTERCEPT_NATIVE_LONG_PRESS.equals(key) || "block_long_press_speed".equals(key))
                && moduleOnlySpeedControl()) return true;
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public boolean moduleOnlySpeedControl() {
        return Boolean.TRUE.equals(values.get(MODULE_ONLY_SPEED));
    }

    public boolean interceptNativeLongPress() {
        return enabled(INTERCEPT_NATIVE_LONG_PRESS);
    }

    public String text(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    public int integer(String key, int fallback) {
        Object value = values.get(key);
        try {
            return value instanceof Number ? ((Number) value).intValue()
                    : value instanceof String ? Integer.parseInt(((String) value).trim()) : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    public long longValue(String key, long fallback) {
        Object value = values.get(key);
        try {
            return value instanceof Number ? ((Number) value).longValue()
                    : value instanceof String ? Long.parseLong(((String) value).trim()) : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    public float decimal(String key, float fallback) {
        Object value = values.get(key);
        try {
            float parsed = value instanceof Number ? ((Number) value).floatValue()
                    : value instanceof String ? Float.parseFloat(((String) value).trim()) : fallback;
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }
}
