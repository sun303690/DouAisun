package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Keeps the host's own feed auto-advance out of the way while the module drives the feed.
 *
 * <p>The host ships a pad auto-next component that decides on its own timer to slide to the next
 * video ({@code checkCanMoveToNext()} / {@code tryMoveToNext()}). The module's {@link AutoPlayHook}
 * also turns pages, from the play-complete callback. With both live the two controllers race: a
 * page moves twice, or moves while the module is deliberately holding it (comments open, video not
 * finished). The reference module solves this by forcing the host switch on and routing everything
 * through the host; this module already owns the feed, so the opposite is correct.
 *
 * <p>So: while {@code auto_play} is on, the host gate answers "no" and never advances anything.
 * When {@code auto_play} is off the hooks stay transparent and the host behaves natively — turning
 * a module switch off must never change host behaviour that the module does not otherwise own.
 *
 * <p>Every anchor is resolved through {@link HostTargets}; a missing anchor degrades to a no-op
 * with a log line because the host is obfuscated and a version bump can drop any single class.
 */
public final class AutoNextTakeoverHook {
    public static final String OPTION = "auto_play";

    private static final String A_CAN_MOVE = "adv_player_can_auto_next";
    private static final String A_TRY_MOVE = "adv_player_try_next";

    private final XposedInterface framework;
    private final Consumer<String> log;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();

    /** Mirrors the module's auto-slide switch; read on the host's calling thread. */
    private volatile boolean enabled;
    /** True once {@link AutoPlayHook} is actually installed and can turn pages itself. */
    private volatile boolean moduleFeedAvailable;

    public AutoNextTakeoverHook(XposedInterface framework, LocalOptions options,
                                Consumer<String> log) {
        this.framework = framework;
        this.log = log;
        applyOptions(options);
    }

    /** Follows the module's auto-slide switch; safe to call from any thread. */
    public void applyOptions(LocalOptions options) {
        enabled = options != null && options.enabled(OPTION);
    }

    /**
     * The host gate is only held back while the module can advance the feed on its own. If
     * {@link AutoPlayHook} failed to install, killing the host gate would freeze the feed, so the
     * host keeps its native behaviour instead.
     */
    public void setModuleFeedAvailable(boolean available) {
        moduleFeedAvailable = available;
    }

    private boolean holdsHostBack() {
        return enabled && moduleFeedAvailable;
    }

    public void install(HostScanReport report, ClassLoader loader) {
        if (report == null || loader == null) return;
        hookBooleanGate(report, loader, A_CAN_MOVE);
        hookVoidGate(report, loader, A_TRY_MOVE);
        if (handles.isEmpty()) {
            log.accept("auto next: no host anchor reachable");
        } else {
            log.accept("auto next: host advance " + (enabled ? "held back" : "transparent")
                    + " (" + handles.size() + " anchors)");
        }
    }

    /** The host asks "may I slide?"; while the module drives the feed the answer is no. */
    private void hookBooleanGate(HostScanReport report, ClassLoader loader, String anchor) {
        Method method = resolve(report, loader, anchor);
        if (method == null) return;
        Class<?> type = method.getReturnType();
        if (type != boolean.class && type != Boolean.class) {
            log.accept("auto next: " + anchor + " is not boolean, skipped");
            return;
        }
        try {
            XposedInterface.HookHandle handle = framework.hook(method).intercept(
                    chain -> holdsHostBack() ? Boolean.FALSE : chain.proceed());
            if (handle != null) handles.add(handle);
        } catch (Throwable error) {
            log.accept("auto next: " + anchor + " hook failed: " + error);
        }
    }

    /** The host's "do it" call is swallowed: the signature never reaches the real method. */
    private void hookVoidGate(HostScanReport report, ClassLoader loader, String anchor) {
        Method method = resolve(report, loader, anchor);
        if (method == null) return;
        try {
            XposedInterface.HookHandle handle = framework.hook(method).intercept(
                    chain -> holdsHostBack() ? null : chain.proceed());
            if (handle != null) handles.add(handle);
        } catch (Throwable error) {
            log.accept("auto next: " + anchor + " hook failed: " + error);
        }
    }

    private Method resolve(HostScanReport report, ClassLoader loader, String anchor) {
        try {
            Method method = HostTargets.method(report, anchor, loader);
            if (method == null) log.accept("auto next: anchor missing: " + anchor);
            return method;
        } catch (Throwable error) {
            log.accept("auto next: anchor " + anchor + " failed: " + error);
            return null;
        }
    }

    public void uninstall() {
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
                // A handle may already be gone after a hot reload; nothing useful to do.
            }
        }
        handles.clear();
    }
}
