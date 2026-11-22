package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Hides every overlay of a short-video playback page except the media layer and the danmaku
 * renderers.
 *
 * <p>Ported from douyim {@code ImmersiveUi} (1970 lines). The original keeps a full playback state
 * machine; this port deliberately uses a narrower trigger - a resumed activity that shows a
 * horizontally centred, sufficiently large media layer - because the host playback state is not
 * observable from here. Hiding is reversible: every touched view keeps its original alpha,
 * visibility and focusability and gets them back on pause, close or when the setting is turned off.
 */
public final class ImmersivePlaybackUi
        implements Application.ActivityLifecycleCallbacks, AutoCloseable {

    private static final int MAX_TREE_DEPTH = 64;
    /** Media layers below this fraction of the decor width are preload cells, not the playing one. */
    private static final float MIN_MEDIA_WIDTH_RATIO = 0.5f;

    private final Consumer<String> log;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<View, ViewState> hidden = new WeakHashMap<>();
    private final Runnable scanTask = this::scanOnce;

    private Application application;
    private ImmersiveTouchGuard touchGuard;
    private WeakReference<Activity> current = new WeakReference<>(null);
    private boolean enabled;
    private boolean closed;
    private boolean warnedNoTouchGuard;
    private long transitionUntil;
    private long hiddenTotal;

    private ImmersivePlaybackUi(ExtendedSettings settings, Consumer<String> log) {
        this.log = log == null ? ignored -> { } : log;
        this.enabled = settings != null && settings.immersivePlayback;
    }

    public static ImmersivePlaybackUi install(io.github.libxposed.api.XposedInterface framework,
                                              com.douai.module.scan.HostScanReport report,
                                              ClassLoader loader, Context context,
                                              ExtendedSettings settings, Consumer<String> log) {
        ImmersivePlaybackUi ui = new ImmersivePlaybackUi(settings, log);
        try {
            if (context != null && context.getApplicationContext() instanceof Application) {
                ui.application = (Application) context.getApplicationContext();
                ui.application.registerActivityLifecycleCallbacks(ui);
            }
            if (context instanceof Activity) ui.current = new WeakReference<>((Activity) context);
            try {
                ui.touchGuard = ImmersiveTouchGuard.install(framework, report, loader,
                        ui::isHidden, ui.log);
            } catch (Throwable failure) {
                // The overlay still works without the touch guard; only taps on hidden nodes suffer.
                ui.log.accept("Touch guard unavailable: " + describe(failure));
                DouAiLog.failure("immersive_playback", "touch_guard", failure);
            }
            if (ui.enabled) ui.scheduleScan(ImmersivePlaybackPolicy.SCAN_INTERVAL_MS);
            return ui;
        } catch (Throwable failure) {
            ui.close();
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        if (closed) return;
        boolean next = settings != null && settings.immersivePlayback;
        boolean changed = next != enabled;
        enabled = next;
        if (!changed) return;
        runOnMain(() -> {
            if (closed) return;
            if (enabled) {
                scheduleScan(0L);
            } else {
                restoreAll("disabled");
            }
        });
    }

    public boolean isEnabled() { return enabled && !closed; }

    /** Used by {@link ImmersiveTouchGuard}: a view this module has suppressed must not eat taps. */
    public boolean isHidden(View view) {
        return view != null && hidden.containsKey(view);
    }

    /** Diagnostics hook: how many views are currently suppressed. */
    public int hiddenCount() { return hidden.size(); }

    private void scanOnce() {
        if (closed) return;
        if (!enabled) return;
        Activity activity = current.get();
        if (activity == null || activity.isFinishing()) {
            mainHandler.postDelayed(scanTask, ImmersivePlaybackPolicy.FALLBACK_CONTENT_CHECK_INTERVAL_MS);
            return;
        }
        long delay;
        try {
            delay = sweep(activity);
        } catch (Throwable failure) {
            DouAiLog.failure("immersive_playback", "sweep", failure);
            delay = ImmersivePlaybackPolicy.FALLBACK_CONTENT_CHECK_INTERVAL_MS;
        }
        if (!closed) mainHandler.postDelayed(scanTask, delay);
    }

    /** One sweep; returns the delay until the next one. */
    private long sweep(Activity activity) {
        View decor = activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
        if (decor == null) return ImmersivePlaybackPolicy.FALLBACK_CONTENT_CHECK_INTERVAL_MS;
        if (activity.getWindow().getDecorView() != decor) return ImmersivePlaybackPolicy.SCAN_INTERVAL_MS;

        long now = SystemClock.uptimeMillis();
        List<View> media = findVisibleMediaLayers(decor);
        if (media.isEmpty()) {
            // A short transition can hide the media layer for a frame or two; restoring the whole
            // overlay tree there would flash the chrome back, so hold on until the window expires.
            if (!hidden.isEmpty()
                    && !ImmersivePlaybackPolicy.shouldHoldHiddenUi(true, now, transitionUntil)) {
                restoreAll("no_media");
            }
            return ImmersivePlaybackPolicy.FALLBACK_CONTENT_CHECK_INTERVAL_MS;
        }

        // Keep the media layer and every danmaku renderer alive, plus the whole ancestor path of
        // each, otherwise the container that holds them would be hidden as ordinary chrome.
        Set<View> preserved = Collections.newSetFromMap(new IdentityHashMap<>());
        for (View layer : media) collectPathToRoot(layer, decor, preserved);
        List<View> danmaku = new ArrayList<>();
        collectDanmaku(decor, danmaku, 0);
        for (View layer : danmaku) collectPathToRoot(layer, decor, preserved);

        transitionUntil = now + ImmersivePlaybackPolicy.UI_TRANSITION_HOLD_MS;
        int added = sweepChildren(decor, preserved, 0);
        if (added > 0) {
            hiddenTotal += added;
            log.accept("Immersive sweep hid " + added + " overlay(s); total=" + hidden.size());
        }
        return ImmersivePlaybackPolicy.SCAN_INTERVAL_MS;
    }

    private int sweepChildren(View node, Set<View> preserved, int depth) {
        if (depth > MAX_TREE_DEPTH || !(node instanceof ViewGroup)) return 0;
        int count = 0;
        ViewGroup group = (ViewGroup) node;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child;
            try {
                child = group.getChildAt(index);
            } catch (Throwable ignored) {
                continue;
            }
            if (child == null) continue;
            if (preserved.contains(child)) continue;
            if (isAncestorOfPreserved(child, preserved)) {
                count += sweepChildren(child, preserved, depth + 1);
            } else {
                if (hide(child)) count++;
            }
        }
        return count;
    }

    /** True when any preserved view lives below {@code node}. */
    private boolean isAncestorOfPreserved(View node, Set<View> preserved) {
        if (!(node instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) node;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child == null) continue;
            if (preserved.contains(child)) return true;
            if (isAncestorOfPreserved(child, preserved)) return true;
        }
        return false;
    }

    private void collectPathToRoot(View view, View decor, Set<View> out) {
        View node = view;
        int guard = 0;
        while (node != null && guard++ < MAX_TREE_DEPTH) {
            out.add(node);
            if (node == decor) break;
            Object parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
        }
    }

    private void collectDanmaku(View node, List<View> out, int depth) {
        if (depth > MAX_TREE_DEPTH) return;
        if (ImmersivePlaybackPolicy.isDanmakuLayer(node) && node.getVisibility() == View.VISIBLE) {
            out.add(node);
            return;
        }
        if (!(node instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) node;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child != null) collectDanmaku(child, out, depth + 1);
        }
    }

    private List<View> findVisibleMediaLayers(View decor) {
        List<View> candidates = new ArrayList<>();
        Rect decorBounds = new Rect();
        decor.getGlobalVisibleRect(decorBounds);
        collectMedia(decor, candidates, 0);
        if (candidates.isEmpty()) return candidates;
        int decorWidth = decor.getWidth() > 0 ? decor.getWidth() : decorBounds.width();
        int minWidth = Math.max(1, (int) (decorWidth * MIN_MEDIA_WIDTH_RATIO));
        List<View> accepted = new ArrayList<>();
        int bestArea = 0;
        for (View candidate : candidates) {
            if (candidate.getWidth() < minWidth) continue;
            accepted.add(candidate);
            bestArea = Math.max(bestArea, candidate.getWidth() * candidate.getHeight());
        }
        if (accepted.isEmpty()) return accepted;
        // Feed pages keep the next cell pre-attached; only the largest layer is the playing one.
        List<View> largest = new ArrayList<>();
        for (View candidate : accepted) {
            if (candidate.getWidth() * candidate.getHeight() >= bestArea) largest.add(candidate);
        }
        return largest;
    }

    private void collectMedia(View node, List<View> out, int depth) {
        if (depth > MAX_TREE_DEPTH) return;
        if (isMediaLayer(node) && node.isShown() && node.getWidth() > 0 && node.getHeight() > 0) {
            out.add(node);
            return;
        }
        if (!(node instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) node;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child != null) collectMedia(child, out, depth + 1);
        }
    }

    private static boolean isMediaLayer(View view) {
        return view instanceof SurfaceView || view instanceof TextureView;
    }

    private boolean hide(View view) {
        boolean tracked = hidden.containsKey(view);
        if (!tracked && view.getVisibility() != View.VISIBLE && !view.isShown()) return false;
        if (!tracked) hidden.put(view, new ViewState(view));
        try {
            // Re-apply every sweep: the host rewrites visibility/alpha of its own chrome whenever a
            // page rebinds, so a one-shot hide would flash back on the next update.
            view.setAlpha(0f);
            view.setVisibility(View.INVISIBLE);
            view.setClickable(false);
            view.setFocusable(false);
        } catch (Throwable failure) {
            if (!tracked) hidden.remove(view);
            return false;
        }
        return !tracked;
    }

    private void restoreAll(String reason) {
        if (hidden.isEmpty()) return;
        int restored = 0;
        for (Map.Entry<View, ViewState> entry : new ArrayList<>(hidden.entrySet())) {
            View view = entry.getKey();
            if (view == null) continue;
            try {
                entry.getValue().apply(view);
                restored++;
            } catch (Throwable ignored) { }
        }
        hidden.clear();
        if (restored > 0) log.accept("Immersive restore(" + reason + ") applied to " + restored + " view(s)");
    }

    private void scheduleScan(long delay) {
        mainHandler.removeCallbacks(scanTask);
        if (!closed && enabled) mainHandler.postDelayed(scanTask, delay);
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    @Override public void onActivityResumed(Activity activity) {
        current = new WeakReference<>(activity);
        if (!enabled) return;
        transitionUntil = SystemClock.uptimeMillis() + ImmersivePlaybackPolicy.UI_TRANSITION_HOLD_MS;
        scheduleScan(0L);
        if (!warnedNoTouchGuard) {
            warnedNoTouchGuard = true;
            log.accept("Immersive playback active; hidden=" + hiddenTotal + " cumulative");
        }
    }

    @Override public void onActivityPaused(Activity activity) {
        Activity resumed = current.get();
        if (resumed != null && resumed != activity) return;
        runOnMain(() -> {
            restoreAll("paused");
            current = new WeakReference<>(null);
            mainHandler.removeCallbacks(scanTask);
        });
    }

    @Override public void onActivityDestroyed(Activity activity) {
        Activity resumed = current.get();
        if (resumed == activity) {
            restoreAll("destroyed");
            current = new WeakReference<>(null);
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }

    @Override public void onActivityStarted(Activity activity) { }

    @Override public void onActivityStopped(Activity activity) { }

    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    @Override public void close() {
        if (closed) return;
        closed = true;
        mainHandler.removeCallbacks(scanTask);
        restoreAll("close");
        if (touchGuard != null) {
            touchGuard.close();
            touchGuard = null;
        }
        if (application != null) {
            try {
                application.unregisterActivityLifecycleCallbacks(this);
            } catch (Throwable ignored) { }
            application = null;
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private static final class ViewState {
        private final int visibility;
        private final float alpha;
        private final boolean clickable;
        private final boolean focusable;

        ViewState(View view) {
            visibility = view.getVisibility();
            alpha = view.getAlpha();
            clickable = view.isClickable();
            focusable = view.isFocusable();
        }

        void apply(View view) {
            view.setAlpha(alpha);
            view.setVisibility(visibility);
            view.setClickable(clickable);
            view.setFocusable(focusable);
        }
    }
}
