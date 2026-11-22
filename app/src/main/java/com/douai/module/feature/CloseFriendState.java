package com.douai.module.feature;

import com.douai.module.config.LocalOptions;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared close-friend state between the alert engine, the session list filter and the settings screen.
 *
 * <p>Three questions are answered here so the filter, the title decorator and the settings screen
 * never disagree:
 *
 * <ul>
 *   <li>which peer uids are close friends ({@link #uids});</li>
 *   <li>should the session list hide them right now ({@link #isHidingActive});</li>
 *   <li>has the user asked for them to be visible ({@link #isRevealing}).</li>
 * </ul>
 *
 * <p>The reveal flag is sticky on purpose: the host adapter caches the list it was handed, so a
 * timed window of a few seconds would expire long before the next list rebuild. A sticky flag
 * keeps the rows visible across every later list submission until the user hides them again.
 */
public final class CloseFriendState {

    /** Hidden windows declared by the auto-hide setting; the list is ordered screen first. */
    public static final String SCOPE_BACKGROUND = "background";
    public static final String SCOPE_SCREEN = "screen";
    public static final String SCOPE_CHAT_LEAVE = "chat_leave";

    /** Explicit "hide now" request, set by the auto-hide scopes and by the hide action. */
    private static volatile boolean autoHidden;
    /** Sticky reveal flag; wins over {@link #autoHidden} until the user hides again. */
    private static volatile boolean revealed;
    /** Timed reveal window kept from the legacy long-press behaviour. */
    private static volatile long revealUntilMs;
    /** Set by an incoming message from a close friend, cleared when the chat page is opened. */
    private static volatile long unreadAtMs;
    private static volatile String cachedRaw = "";
    private static volatile Set<String> cachedUids = Collections.emptySet();
    /** Last session list adapter seen by the filter, used to force a redraw after a flag flip. */
    private static volatile WeakReference<Object> adapterRef = new WeakReference<>(null);

    /** Number of conversation rows suppressed since the module loaded, shown as the settings hint. */
    public static final AtomicInteger FILTERED_ROWS = new AtomicInteger();
    /** Number of notifications or banners suppressed since the module loaded. */
    public static final AtomicInteger FILTERED_PUSHES = new AtomicInteger();

    private CloseFriendState() {
    }

    public static Set<String> uids(LocalOptions options) {
        String raw = options == null ? "" : options.text(LocalOptions.CLOSE_FRIEND_UIDS, "");
        if (raw == null) raw = "";
        String normalized = raw.trim();
        if (normalized.equals(cachedRaw)) return cachedUids;
        Set<String> parsed = new HashSet<>();
        for (String part : normalized.split("[,;\\s]+")) {
            String value = part.trim();
            if (!value.isEmpty()) parsed.add(value);
        }
        cachedUids = Collections.unmodifiableSet(parsed);
        cachedRaw = normalized;
        return cachedUids;
    }

    public static boolean isCloseFriend(LocalOptions options, String uid) {
        if (uid == null || uid.isEmpty()) return false;
        return uids(options).contains(uid.trim());
    }

    /** Matches any uid segment of a conversation id, e.g. {@code 0:1:1234:5678}. */
    public static boolean conversationMatches(LocalOptions options, String conversationId) {
        Set<String> ids = uids(options);
        if (conversationId == null || ids.isEmpty()) return false;
        String value = conversationId.trim();
        int first = value.indexOf(':');
        int second = first < 0 ? -1 : value.indexOf(':', first + 1);
        if (second < 0) return ids.contains(value);
        boolean any = false;
        for (String segment : value.substring(second + 1).split(":")) {
            String uid = segment.trim();
            if (uid.isEmpty()) continue;
            any = true;
            if (ids.contains(uid)) return true;
        }
        return !any && ids.contains(value);
    }

    /** A close friend message arrived; the session title stays bold until the chat is opened. */
    public static void markUnread() {
        unreadAtMs = System.currentTimeMillis();
    }

    public static void clearUnread() {
        unreadAtMs = 0L;
    }

    public static boolean hasUnread() {
        return unreadAtMs != 0L;
    }

    public static void setAutoHidden(boolean hidden) {
        autoHidden = hidden;
    }

    public static boolean isAutoHidden() {
        return autoHidden;
    }

    /** Sticky reveal: rows stay visible until {@link #hideNow()} or a new auto-hide trigger. */
    public static void revealPersistent() {
        revealed = true;
        unreadAtMs = 0L;
    }

    /** Manual reveal window requested by long-pressing the chat title. */
    public static void revealFor(long milliseconds) {
        revealed = true;
        long target = System.currentTimeMillis() + Math.max(0L, milliseconds);
        if (target > revealUntilMs) revealUntilMs = target;
    }

    /** Hides the rows again and drops any reveal window. */
    public static void hideNow() {
        revealed = false;
        revealUntilMs = 0L;
        autoHidden = true;
    }

    public static void clearReveal() {
        revealed = false;
        revealUntilMs = 0L;
    }

    public static boolean isRevealing() {
        return revealed || revealUntilMs > System.currentTimeMillis();
    }

    /**
     * True when the session list must drop close friend rows.
     *
     * <p>The auto-hide switch counts even before any lifecycle event fired, otherwise the first
     * screen after a module load would still list the hidden conversations.
     */
    public static boolean isHidingActive(LocalOptions options) {
        if (isRevealing()) return false;
        if (autoHidden) return true;
        return options != null && options.enabled(LocalOptions.CLOSE_FRIEND_AUTO_HIDE, false);
    }

    /** Legacy no-argument form used by the title decorator when no options snapshot is at hand. */
    public static boolean isHidingActive() {
        return isHidingActive(null);
    }

    /** Remembers the session list adapter so a flag flip can force one redraw. */
    public static void rememberAdapter(Object adapter) {
        if (adapter == null) return;
        adapterRef = new WeakReference<>(adapter);
    }

    public static Object adapter() {
        return adapterRef.get();
    }

    public static String summary() {
        return String.format(Locale.ROOT, "rows=%d pushes=%d autoHidden=%s revealed=%s",
                FILTERED_ROWS.get(), FILTERED_PUSHES.get(), autoHidden, isRevealing());
    }
}
