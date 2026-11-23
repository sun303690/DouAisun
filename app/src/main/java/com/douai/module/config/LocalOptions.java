package com.douai.module.config;

import android.content.SharedPreferences;

import com.douai.module.feature.PostBarTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable host-local snapshot for independently registered feature options. */
public final class LocalOptions {
    /** Reserves feed long presses for module actions and blocks all native long-press regions. */
    public static final String INTERCEPT_NATIVE_LONG_PRESS = "intercept_native_long_press";
    public static final String MODULE_ONLY_SPEED = "module_only_speed";
    public static final String LOCK_HIGHEST_VIDEO_QUALITY = "lock_highest_video_quality";
    /** Fixed quality target ported from douyim: "" native, "max" highest, or a pixel height. */
    public static final String VIDEO_QUALITY_TARGET = "video_quality_target";
    public static final String DOWNLOAD_SAVE_TREE = "download_directory_uri";
    /** Sub-folder created under the album/DCIM tree for every saved media file. */
    public static final String DOWNLOAD_SUBDIRECTORY = "download_subdirectory";
    /** Restores the system album folders instead of the module album sub-folder. */
    public static final String DOWNLOAD_SYSTEM_ALBUM = "download_system_album";
    /** Relative folder template applied below the download root, without a file extension. */
    public static final String DOWNLOAD_PATH_TEMPLATE = "download_path_template";
    /** SimpleDateFormat pattern used by the path template for the date placeholder. */
    public static final String DOWNLOAD_DATE_FORMAT = "download_date_format";
    /** Wall-clock time until which the automatic swipe is paused, in milliseconds. */
    public static final String AUTO_PLAY_PAUSED_UNTIL = "auto_play_paused_until";

    /** The spoofed IP attribution shown next to the account name. */
    public static final String IP_LOCATION = "ip_location";
    /** Whether the spoofed attribution also replaces the province and city fields. */
    public static final String IP_LOCATION_SPOOF_CITY = "ip_location_spoof_city";
    /** The spoofed province. */
    public static final String IP_LOCATION_PROVINCE = "ip_location_province";
    /** The spoofed city. */
    public static final String IP_LOCATION_CITY = "ip_location_city";
    /** Master switch for the feed information bar. */
    public static final String POST_BAR_ENABLED = "post_bar";
    /** Template rendered by the feed information bar. */
    public static final String POST_BAR_TEMPLATE = "post_template";
    /** Text size of the feed information bar, in sp. */
    public static final String POST_BAR_SIZE = "post_size";
    /** Text colour of the feed information bar, as {@code #AARRGGBB}. */
    public static final String POST_BAR_COLOR = "post_color";
    /** Uses the colourful mode of the feed information bar. */
    public static final String POST_BAR_COLORFUL = "post_colorful";
    /** Default information bar template. */
    public static final String DEFAULT_POST_BAR_TEMPLATE = "${time} ${location} ${progress}";
    /** Default information bar date pattern. */
    public static final String DEFAULT_POST_BAR_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    /** Default information bar text size, in sp. */
    public static final String DEFAULT_POST_BAR_SIZE = "12";
    /** Default information bar text colour. */
    public static final String DEFAULT_POST_BAR_COLOR = "#FFFFFF";

    /** Tells whether the feed information bar is enabled. */
    public boolean postBarEnabled() { return enabled(POST_BAR_ENABLED); }

    /** The validated information bar template, empty when the stored value is unusable. */
    public String postBarTemplate() {
        return postBarTemplate(text(POST_BAR_TEMPLATE, DEFAULT_POST_BAR_TEMPLATE));
    }

    /**
     * Pure sanitiser for the information bar template: only the supported placeholders survive and
     * the result is capped at the maximum rendered length.
     */
    public static String postBarTemplate(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.length() > 256) trimmed = trimmed.substring(0, 256);
        return PostBarTemplate.isValid(trimmed) ? trimmed : "";
    }

    /** The information bar text size, in sp. */
    public int postBarSize() {
        int size = integer(POST_BAR_SIZE, 12);
        return size < 6 ? 6 : (size > 32 ? 32 : size);
    }

    /** The information bar text colour, as {@code #AARRGGBB}. */
    public String postBarColor() {
        String raw = text(POST_BAR_COLOR, DEFAULT_POST_BAR_COLOR).trim();
        if (!raw.startsWith("#")) raw = "#" + raw;
        if (raw.length() != 7 && raw.length() != 9) return DEFAULT_POST_BAR_COLOR;
        for (int i = 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return DEFAULT_POST_BAR_COLOR;
        }
        return raw.toUpperCase(java.util.Locale.ROOT);
    }

    /** Tells whether the information bar uses the colourful mode. */
    public boolean postBarColorful() { return enabled(POST_BAR_COLORFUL); }

    /** The spoofed IP attribution shown next to the account name. */
    public String ipLocation() {
        String value = text(IP_LOCATION, "").trim();
        return value.isEmpty() ? "" : value;
    }

    /** Whether the spoofed attribution also replaces the province and city fields. */
    public boolean ipLocationSpoofCity() { return enabled(IP_LOCATION_SPOOF_CITY); }

    /** The spoofed province, empty when the province should stay untouched. */
    public String ipLocationProvince() {
        String value = text(IP_LOCATION_PROVINCE, "").trim();
        return value.isEmpty() ? "" : value;
    }

    /** The spoofed city, empty when the city should stay untouched. */
    public String ipLocationCity() {
        String value = text(IP_LOCATION_CITY, "").trim();
        return value.isEmpty() ? "" : value;
    }

    /**
     * Returns the wall-clock time until which the automatic swipe stays paused.
     *
     * @return the deadline in milliseconds, or {@code 0} when the automatic swipe is not paused
     */
    public long autoPlayPausedUntil() {
        Object raw = values.get(AUTO_PLAY_PAUSED_UNTIL);
        if (raw instanceof Number) return ((Number) raw).longValue();
        if (raw instanceof String) {
            try { return Long.parseLong(((String) raw).trim()); }
            catch (NumberFormatException ignored) { return 0L; }
        }
        return 0L;
    }

    /**
     * Tells whether the automatic swipe is currently paused.
     *
     * @param nowMillis the current wall-clock time
     * @return {@code true} while the pause deadline is in the future
     */
    public boolean autoPlayPaused(long nowMillis) {
        return autoPlayPausedUntil() > nowMillis;
    }
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
    /** Master switch for hiding close friends (by peer uid) from the chat session list. */
    public static final String CLOSE_FRIEND_FILTER = "close_friend_filter";
    /** Comma or newline separated peer uids hidden from the chat session list. */
    public static final String CLOSE_FRIEND_UIDS = "close_friend_uids";
    /** Bold the session title and raise its font weight when a close friend sends a new message. */
    public static final String CLOSE_FRIEND_TITLE_BOLD = "close_friend_title_bold";
    /** Vibrate once when a close friend message arrives. */
    public static final String CLOSE_FRIEND_VIBRATE = "close_friend_vibrate";
    /** Vibration duration in milliseconds for close friend alerts. */
    public static final String CLOSE_FRIEND_VIBRATE_MS = "close_friend_vibrate_ms";
    /** Suppress banner style in-app push for close friend messages. */
    public static final String CLOSE_FRIEND_HIDE_BANNER = "close_friend_hide_banner";
    /** Cancel system notifications that belong to a close friend conversation. */
    public static final String CLOSE_FRIEND_HIDE_NOTIFICATION = "close_friend_hide_notification";
    /** Hide incoming close friend in-app calls. */
    public static final String CLOSE_FRIEND_HIDE_CALL = "close_friend_hide_call";
    /** Mute ringing and vibration for incoming close friend calls. */
    public static final String CLOSE_FRIEND_CALL_SILENT = "close_friend_call_silent";
    /** Hide close friend conversations from the top of the list only after they are read. */
    public static final String CLOSE_FRIEND_HIDE_READ = "close_friend_hide_read";
    /** Font weight used when a close friend message bolds the chat title, 500 to 900. */
    public static final String CLOSE_FRIEND_BOLD_WEIGHT = "close_friend_bold_weight";
    /** Hold duration in milliseconds on the chat title that reveals hidden close friends. */
    public static final String CLOSE_FRIEND_TITLE_HOLD_MS = "close_friend_title_hold_ms";
    /** Consecutive taps on the chat title that hides close friends again. */
    public static final String CLOSE_FRIEND_TITLE_HIDE_TAPS = "close_friend_title_hide_taps";
    /** Skip the vibration while the ringer is silent or the device is in do not disturb. */
    public static final String CLOSE_FRIEND_VIBRATE_SKIP_SILENT = "close_friend_vibrate_skip_silent";
    /** Master switch for hiding close friends automatically on a lifecycle event. */
    public static final String CLOSE_FRIEND_AUTO_HIDE = "close_friend_auto_hide";
    /** Comma separated lifecycle triggers that hide close friends, e.g. background,screen,chat_leave. */
    public static final String CLOSE_FRIEND_AUTO_SCOPE = "close_friend_auto_scope";
    /** Hides close friend entries from the message page interaction digest. */
    public static final String CLOSE_FRIEND_HIDE_INTERACTION = "close_friend_hide_interaction";
    /** Hides close friends from the in-app message search results. */
    public static final String CLOSE_FRIEND_HIDE_SEARCH = "close_friend_hide_search";
    /** Hides close friends from the forward/share contact picker. */
    public static final String CLOSE_FRIEND_HIDE_FORWARD = "close_friend_hide_forward";
    /** Hides close friends from the bottom tab unread badge count. */
    public static final String CLOSE_FRIEND_HIDE_BADGE = "close_friend_hide_badge";
    /** Hides the incoming call banner and the missed call banner. */
    public static final String CLOSE_FRIEND_HIDE_CALL_BANNER = "close_friend_hide_call_banner";
    /** Hides close friends from the message page top avatar row (skylight). */
    public static final String CLOSE_FRIEND_HIDE_SKYLIGHT = "close_friend_hide_skylight";
    /** Skips the ad page by tapping the skip/countdown node on the current screen. */
    public static final String SKIP_AD = "skip_ad";
    /** Auto taps the collapsed comment entry so long comments are always expanded. */
    public static final String EXPAND_HIDDEN_COMMENTS = "expand_hidden_comments";
    /** Hides the follower/following/like counters on profile and list pages. */
    public static final String HIDE_LIST_COUNT = "hide_list_count";
    /** Shows the exact digg count on comments instead of the 11万 abbreviated form. */
    public static final String EXACT_LIKE_COUNT = "exact_like_count";
    /** Keeps the account offline: suppresses the outbound IM active-status report. */
    public static final String GHOST_MODE = "ghost_mode";
    /** Suppresses the outbound IM read receipt so the peer never sees 已读. */
    public static final String READ_RECEIPT_SUPPRESS = "read_receipt_suppress";
    /** Removes the commercial comment injected into the comment list. */
    public static final String COMMENT_AD_FILTER = "comment_ad_filter";
    /** Hides the feed danmaku layer entirely. */
    public static final String HIDE_DANMAKU = "hide_danmaku";
    /** Ignore double-tap gestures on the feed so an accidental tap never likes. */
    public static final String MISTOUCH_DOUBLE_CLICK = "mistouch_double_click";
    public static final String LIVE_PREVIEW_BLOCK = "live_preview_block";
    /** Skips the in-app IM pull-down banner that previews an incoming private message. */
    public static final String IM_BANNER_BLOCK = "im_banner_block";
    /** Vibrates once when a double tap actually likes the current feed item. */
    public static final String LIKE_HAPTIC = "like_haptic";
    /** Hides the red-dot badges on the bottom tab bar. */
    public static final String TAB_DOT_HIDE = "tab_dot_hide";

    /** Blocks the horizontal swipe gesture on the home top tab strip. */
    public static final String TOP_TAB_SWIPE_BLOCK = "top_tab_swipe_block";

    /** Refuses the Reparo remote patch task so pushed patches cannot replace hooked classes. */
    public static final String HOTFIX_PATCH_BLOCK = "hotfix_patch_block";

    /** Hides the injected cards on the search intermediate page. */
    public static final String SEARCH_SECTIONS_HIDE = "search_sections_hide";

    /** Aligns the host dark theme with the system night mode. */
    public static final String NIGHT_MODE_FORCE = "night_mode_force";
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
    /** Default folder template below the download root; the date placeholder is replaced at save time. */
    public static final String DEFAULT_DOWNLOAD_PATH_TEMPLATE = "";
    /** Default date pattern used inside the path template. */
    public static final String DEFAULT_DOWNLOAD_DATE_FORMAT = "yyyy-MM-dd_HH-mm-ss";
    /** Keeps the host album folders untouched instead of writing into the module sub-folder. */
    public boolean downloadSystemAlbum() { return enabled(DOWNLOAD_SYSTEM_ALBUM); }
    /** Folder template below the download root; the sanitised form never escapes the root. */
    public String downloadPathTemplate() { return sanitisePathTemplate(text(DOWNLOAD_PATH_TEMPLATE, "")); }
    /** Date pattern used by the path template; falls back to the default when it is not a valid pattern. */
    public String downloadDateFormat() { return sanitiseDateFormat(text(DOWNLOAD_DATE_FORMAT, "")); }
    /**
     * Pure sanitiser for the relative folder template: every segment is reduced to a name MediaStore
     * accepts and traversal segments are dropped, so the result can never leave the download root.
     */
    public static String sanitisePathTemplate(String raw) {
        if (raw == null) return DEFAULT_DOWNLOAD_PATH_TEMPLATE;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return DEFAULT_DOWNLOAD_PATH_TEMPLATE;
        String[] parts = trimmed.split("/");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String segment = part.trim();
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) continue;
            StringBuilder clean = new StringBuilder(segment.length());
            for (int i = 0; i < segment.length() && clean.length() < 40; i++) {
                char c = segment.charAt(i);
                if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                        || c == '<' || c == '>' || c == '|' || c < ' ') continue;
                clean.append(c);
            }
            String name = clean.toString().trim();
            if (name.isEmpty()) continue;
            if (out.length() > 0) out.append('/');
            out.append(name);
        }
        return out.toString();
    }
    /**
     * Pure sanitiser for the date pattern: only SimpleDateFormat letters survive, and an empty or
     * letter-free pattern falls back to the default so the template always renders a usable stamp.
     */
    public static String sanitiseDateFormat(String raw) {
        if (raw == null) return DEFAULT_DOWNLOAD_DATE_FORMAT;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return DEFAULT_DOWNLOAD_DATE_FORMAT;
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length() && out.length() < 40; i++) {
            char c = trimmed.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) out.append(c);
        }
        String pattern = out.toString();
        if (pattern.isEmpty()) return DEFAULT_DOWNLOAD_DATE_FORMAT;
        try {
            new java.text.SimpleDateFormat(pattern, java.util.Locale.ROOT);
            return pattern;
        } catch (IllegalArgumentException invalid) {
            return DEFAULT_DOWNLOAD_DATE_FORMAT;
        }
    }
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
