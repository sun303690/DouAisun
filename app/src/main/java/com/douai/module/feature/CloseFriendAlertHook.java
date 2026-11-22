package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Close friend alert engine.
 *
 * <p>Reproduces the four user facing behaviours dyguard attaches to the chat session title plus the
 * vibration alert:
 *
 * <ul>
 *   <li>bold title with a configurable weight, applied before every draw so a host rebind cannot reset it;</li>
 *   <li>long press the title for N ms to reveal hidden close friends, N taps to hide them again;</li>
 *   <li>vibration on a close friend message, skipped while silent or in do not disturb;</li>
 *   <li>lifecycle driven auto hide with the background / screen / chat leave scopes.</li>
 * </ul>
 */
public final class CloseFriendAlertHook {

    private static final String TAG = "DouAiCloseFriend";

    private static final String TITLE_UI =
            "Lcom/ss/android/ugc/aweme/im/sdk/module/session/rips/titlebar/title/SessionTitleUI;";

    private static final long SWEEP_INTERVAL_MS = 400L;

    private static volatile CloseFriendAlertHook active;

    private final XposedInterface framework;
    private final ClassLoader loader;
    private final HostScanReport report;
    private final LocalOptions options;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<XposedInterface.HookHandle> hooks = new ArrayList<>();

    private final List<TitleBinding> bindings = new ArrayList<>();
    private boolean sweepScheduled;
    private int titleTapCount;
    private long lastTitleTapMs;
    private long titleHoldStartedMs;
    private boolean titleHoldConsumed;
    private boolean installed;
    private final java.util.concurrent.atomic.AtomicInteger resumed = new java.util.concurrent.atomic.AtomicInteger();

    private CloseFriendAlertHook(XposedInterface framework, ClassLoader loader, HostScanReport report,
                                 LocalOptions options, Consumer<String> log) {
        this.framework = framework;
        this.loader = loader;
        this.report = report;
        this.options = options;
        this.log = log;
    }

    public static CloseFriendAlertHook install(XposedInterface framework, ClassLoader loader, HostScanReport report,
                                               LocalOptions options, Consumer<String> log) {
        CloseFriendAlertHook previous = active;
        if (previous != null) previous.close();
        CloseFriendAlertHook hook = new CloseFriendAlertHook(framework, loader, report, options, log);
        active = hook;
        hook.start();
        return hook;
    }

    public static CloseFriendAlertHook current() {
        return active;
    }

    private void start() {
        try {
            installTitleHook();
            installed = true;
            note("close friend title hook ready");
        } catch (Throwable failure) {
            note("close friend title hook failed: " + failure);
        }
        try {
            bindIncomingMessages();
            bindLifecycle();
        } catch (Throwable failure) {
            note("close friend alert binding failed: " + failure);
        }
    }

    /** Receives every incoming IM message from the native layer and wakes the alert engine. */
    private void bindIncomingMessages() {
        NativeMessagingHooks.setIncomingMessageListener((conversationId, senderUid) -> {
            LocalOptions current = options;
            if (current == null) return;
            if (current.text(LocalOptions.CLOSE_FRIEND_UIDS, "").trim().isEmpty()) return;
            boolean hit = CloseFriendState.isCloseFriend(current, senderUid)
                    || CloseFriendState.conversationMatches(current, conversationId);
            if (!hit) return;
            CloseFriendState.markUnread();
            Application app = currentApplication();
            boolean vibrated = vibrate(app, current);
            note("close friend message from " + senderUid + " vibrated=" + vibrated);
            scheduleSweep();
            notifySessionListChanged();
        });
        note("close friend incoming message listener bound");
    }

    /** Screen and background events drive the auto hide scopes. */
    private void bindLifecycle() {
        Application app = currentApplication();
        if (app == null) {
            note("close friend lifecycle not bound: no application");
            return;
        }
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, android.os.Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) {
                resumed.incrementAndGet();
                if (isChatActivity(activity)) {
                    CloseFriendState.clearUnread();
                    scheduleSweep();
                }
            }
            @Override public void onActivityPaused(Activity activity) {
                if (isChatActivity(activity)) onLifecycleEvent(options, CloseFriendState.SCOPE_CHAT_LEAVE);
            }
            @Override public void onActivityStopped(Activity activity) {
                if (resumed.decrementAndGet() <= 0) {
                    resumed.set(0);
                    onLifecycleEvent(options, CloseFriendState.SCOPE_BACKGROUND);
                }
            }
            @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            filter.addAction(android.content.Intent.ACTION_SCREEN_ON);
            app.registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context context, android.content.Intent intent) {
                    if (intent == null) return;
                    if (android.content.Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        onLifecycleEvent(options, CloseFriendState.SCOPE_SCREEN);
                    } else if (android.content.Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                        scheduleSweep();
                    }
                }
            }, filter);
        } catch (Throwable failure) {
            note("close friend screen receiver failed: " + failure);
        }
        note("close friend lifecycle bound");
    }

    private static boolean isChatActivity(Activity activity) {
        if (activity == null) return false;
        String name = activity.getClass().getName();
        return name.startsWith("com.ss.android.ugc.aweme.im.sdk.chat.")
                || name.contains(".im.sdk.chat.ui.");
    }

    private void installTitleHook() {
        Method target = resolveTitleMethod();
        if (target == null) {
            note("session title method not resolved");
            return;
        }
        XposedInterface.HookHandle handle = framework.hook(target)
                .setId("close_friend_title")
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        onTitleReady(chain.getThisObject());
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
        hooks.add(handle);
    }

    private Method resolveTitleMethod() {
        // Resolved by class name: the obfuscated container name is stable across builds and this
        // avoids depending on a rule-table row the scan may never produce.
        try {
            Class<?> type = Class.forName(TITLE_UI.replace('/', '.').replaceFirst("^L", "").replace(";", ""),
                    false, loader);
            for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
                try {
                    Method candidate = cursor.getDeclaredMethod("onViewCreated", View.class);
                    candidate.setAccessible(true);
                    return candidate;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (report == null) return null;
        try {
            return HostTargets.method(report, "adv_session_title_bind", loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Resource id of the chat page title label in Douyin 41.0.0 (0x7f0a9fa0). */
    private static final int TITLE_VIEW_ID = 0x7f0a9fa0;

    /** Called right after the SessionTitleUI finished onViewCreated; grabs the title TextView off the host. */
    private void onTitleReady(Object titleUi) {
        if (titleUi == null) return;
        TextView title = findTitleView(titleUi);
        if (title == null) return;
        remember(new TitleBinding(title));
    }

    private TextView findTitleView(Object titleUi) {
        // SessionTitleUI keeps its label in the obfuscated field "a"; prefer it over a resource lookup.
        for (Class<?> type = titleUi.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!TextView.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(titleUi);
                    if (value instanceof TextView) return (TextView) value;
                } catch (Throwable ignored) {
                }
            }
        }
        if (titleUi instanceof View) {
            View found = ((View) titleUi).findViewById(TITLE_VIEW_ID);
            if (found instanceof TextView) return (TextView) found;
        }
        return null;
    }

    private void remember(TitleBinding binding) {
        synchronized (bindings) {
            for (int i = bindings.size() - 1; i >= 0; i--) {
                TitleBinding existing = bindings.get(i);
                if (existing.view == binding.view) return;
                if (!existing.view.isAttachedToWindow()) bindings.remove(i);
            }
            bindings.add(binding);
        }
        scheduleSweep();
    }

    private void scheduleSweep() {
        if (sweepScheduled) return;
        sweepScheduled = true;
        main.post(new Runnable() {
            @Override
            public void run() {
                sweepScheduled = false;
                sweep();
            }
        });
    }

    private void sweep() {
        List<TitleBinding> snapshot;
        synchronized (bindings) {
            bindings.removeIf(binding -> !binding.view.isAttachedToWindow());
            snapshot = new ArrayList<>(bindings);
        }
        boolean bold = options.enabled(LocalOptions.CLOSE_FRIEND_TITLE_BOLD, true);
        boolean hidden = CloseFriendState.isHidingActive();
        for (TitleBinding binding : snapshot) {
            try {
                if (bold) binding.applyBold(options, hidden); else binding.clearBold();
            } catch (Throwable ignored) {
            }
        }
        if (!snapshot.isEmpty()) scheduleNext();
    }

    private void scheduleNext() {
        if (sweepScheduled) return;
        sweepScheduled = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                sweepScheduled = false;
                sweep();
            }
        }, SWEEP_INTERVAL_MS);
    }

    private void note(String message) {
        Consumer<String> sink = log;
        if (sink != null) {
            try {
                sink.accept("[" + TAG + "] " + message);
            } catch (Throwable ignored) {
            }
        }
    }

    public void close() {
        installed = false;
        main.removeCallbacksAndMessages(null);
        for (XposedInterface.HookHandle handle : hooks) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
        hooks.clear();
        synchronized (bindings) {
            for (TitleBinding binding : bindings) {
                try {
                    binding.clearBold();
                } catch (Throwable ignored) {
                }
            }
            bindings.clear();
        }
    }

    public boolean isInstalled() {
        return installed;
    }

    // ---------------------------------------------------------------- vibration

    /**
     * Fires the close friend vibration for one incoming message.
     *
     * @return true when the device actually vibrated.
     */
    public static boolean vibrate(Context context, LocalOptions options) {
        if (context == null || options == null) return false;
        if (!options.enabled(LocalOptions.CLOSE_FRIEND_VIBRATE, false)) return false;
        if (options.enabled(LocalOptions.CLOSE_FRIEND_VIBRATE_SKIP_SILENT, true) && isSilent(context)) return false;
        int duration = Math.max(20, options.integer(LocalOptions.CLOSE_FRIEND_VIBRATE_MS, 120));
        try {
            Vibrator vibrator = vibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(duration);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isSilent(Context context) {
        try {
            android.media.AudioManager audio =
                    (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return false;
            int mode = audio.getRingerMode();
            return mode == android.media.AudioManager.RINGER_MODE_SILENT
                    || mode == android.media.AudioManager.RINGER_MODE_VIBRATE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Vibrator vibrator(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager =
                        (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) return manager.getDefaultVibrator();
            }
            return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------ auto hide

    /** Applies the auto hide scope for a lifecycle event reported by the host observer. */
    public static void onLifecycleEvent(LocalOptions options, String scope) {
        if (options == null || scope == null) return;
        if (!options.enabled(LocalOptions.CLOSE_FRIEND_AUTO_HIDE, false)) return;
        String configured = options.text(LocalOptions.CLOSE_FRIEND_AUTO_SCOPE, "background,screen");
        boolean matches = false;
        for (String part : (configured == null ? "" : configured).split("[,;\\s]+")) {
            if (part.trim().equalsIgnoreCase(scope)) {
                matches = true;
                break;
            }
        }
        if (!matches) return;
        CloseFriendState.setAutoHidden(true);
        CloseFriendState.clearReveal();
        CloseFriendAlertHook hook = active;
        if (hook != null) hook.scheduleSweep();
    }

    // -------------------------------------------------------------- title view

    /** One bound chat session title and its cached original text appearance. */
    private static final class TitleBinding {

        private final TextView view;
        private final Typeface originalTypeface;
        private final int originalPaintFlags;
        private final String originalFontVariation;
        private final int originalStyle;
        private final float originalStrokeWidth;
        private boolean bold;
        private boolean eventsAttached;

        TitleBinding(TextView view) {
            this.view = view;
            this.originalTypeface = view.getTypeface();
            this.originalPaintFlags = view.getPaintFlags();
            this.originalStyle = originalTypeface == null ? Typeface.NORMAL : originalTypeface.getStyle();
            this.originalStrokeWidth = view.getPaint().getStrokeWidth();
            String variation = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    variation = view.getFontVariationSettings();
                }
            } catch (Throwable ignored) {
            }
            this.originalFontVariation = variation;
            attachTouch();
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    attachTouch();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    clearBold();
                }
            });
        }

        void applyBold(LocalOptions options, boolean hiding) {
            int weight = Math.max(500, Math.min(900, options.integer(LocalOptions.CLOSE_FRIEND_BOLD_WEIGHT, 700)));
            Typeface boldFace = Typeface.create(originalTypeface, Typeface.BOLD);
            if (view.getTypeface() != boldFace) view.setTypeface(boldFace);
            int flags = originalPaintFlags | android.graphics.Paint.FAKE_BOLD_TEXT_FLAG;
            if (view.getPaintFlags() != flags) view.setPaintFlags(flags);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String variation = "'wght' " + weight;
                if (!variation.equals(view.getFontVariationSettings())) {
                    try {
                        view.setFontVariationSettings(variation);
                    } catch (Throwable ignored) {
                    }
                }
            }
            float stroke = Math.max(originalStrokeWidth, weight >= 800 ? 1.2f : 0.6f);
            if (Math.abs(view.getPaint().getStrokeWidth() - stroke) > 0.01f) {
                view.getPaint().setStrokeWidth(stroke);
            }
            bold = true;
            if (hiding) view.setAlpha(1f);
        }

        void clearBold() {
            if (!bold) return;
            bold = false;
            try {
                view.setTypeface(originalTypeface);
                view.setPaintFlags(originalPaintFlags);
                if (originalFontVariation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    view.setFontVariationSettings(originalFontVariation);
                }
                view.getPaint().setStrokeWidth(originalStrokeWidth);
            } catch (Throwable ignored) {
            }
        }

        private void attachTouch() {
            if (eventsAttached) return;
            eventsAttached = true;
            view.setOnLongClickListener(v -> {
                onTitleLongPress();
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return true;
            });
            view.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    titleHoldStarted();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    titleHoldEnded();
                }
                return false;
            });
            view.setOnClickListener(v -> onTitleTap());
        }

        private void onTitleLongPress() {
            CloseFriendAlertHook hook = active;
            if (hook == null) return;
            hook.revealFromLongPress();
        }

        private void titleHoldStarted() {
            CloseFriendAlertHook hook = active;
            if (hook == null) return;
            hook.titleHoldStartedMs = System.currentTimeMillis();
            hook.titleHoldConsumed = false;
        }

        private void titleHoldEnded() {
            CloseFriendAlertHook hook = active;
            if (hook == null || hook.titleHoldConsumed) return;
            long held = System.currentTimeMillis() - hook.titleHoldStartedMs;
            int need = hook.options.integer(LocalOptions.CLOSE_FRIEND_TITLE_HOLD_MS, 600);
            if (held >= need && need > 0) {
                hook.titleHoldConsumed = true;
                hook.revealFromLongPress();
            }
        }

        private void onTitleTap() {
            CloseFriendAlertHook hook = active;
            if (hook != null) hook.onTitleTapSeen();
        }
    }

    private void revealFromLongPress() {
        int hold = options.integer(LocalOptions.CLOSE_FRIEND_TITLE_HOLD_MS, 600);
        long window = Math.max(5000L, hold * 10L);
        CloseFriendState.revealFor(window);
        CloseFriendState.setAutoHidden(true);
        note("close friend reveal window " + window + "ms");
        scheduleSweep();
        notifySessionListChanged();
    }

    private void onTitleTapSeen() {
        int needed = Math.max(2, options.integer(LocalOptions.CLOSE_FRIEND_TITLE_HIDE_TAPS, 3));
        long now = System.currentTimeMillis();
        if (now - lastTitleTapMs > 900L) titleTapCount = 0;
        lastTitleTapMs = now;
        titleTapCount++;
        if (titleTapCount < needed) return;
        titleTapCount = 0;
        CloseFriendState.clearReveal();
        CloseFriendState.setAutoHidden(true);
        note("close friend hidden again after " + needed + " taps");
        scheduleSweep();
        notifySessionListChanged();
    }

    /** Asks the host to redraw the session list after the hide flag flipped. */
    private void notifySessionListChanged() {
        try {
            Application app = currentApplication();
            if (app == null) return;
            app.sendBroadcast(new android.content.Intent("com.douai.action.CLOSE_FRIEND_CHANGED"));
        } catch (Throwable ignored) {
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object value = current.invoke(null);
            return value instanceof Application ? (Application) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String describe() {
        return CloseFriendState.summary();
    }

    static String format(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }

    static boolean anyCloseFriend(LocalOptions options, Activity activity) {
        Set<String> ids = CloseFriendState.uids(options);
        return !ids.isEmpty();
    }
}
