package com.douai.module.feature;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Blocks the ad comment injected into the comment list.
 *
 * <p>Dyvv anchor {@code comment.content.inserted_ad.factory.v1}. The host builds the injected
 * {@code CommentStruct} in {@code LX/12GV;->LIZ(Aweme, String)}, so returning {@code null} keeps the
 * ad slot out of the list without touching the real comment feed.</p>
 */
public final class CommentAdFilterHook {

    public static final String RULE = "adv_comment_ad_factory";

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final AtomicLong lastTrace = new AtomicLong(0L);
    private final AtomicLong blocked = new AtomicLong(0L);
    private final Consumer<String> log;
    private volatile boolean enabled;
    private volatile ExtendedSettings settings;

    private CommentAdFilterHook(ExtendedSettings settings, Consumer<String> log) {
        this.enabled = settings != null && settings.commentAdFilter;
        this.settings = settings;
        this.log = log == null ? ignored -> { } : log;
    }

    public static CommentAdFilterHook install(XposedInterface framework, HostScanReport report,
                                              ClassLoader loader, ExtendedSettings settings,
                                              Consumer<String> log) throws Exception {
        CommentAdFilterHook hook = new CommentAdFilterHook(settings, log);
        Method factory = HostTargets.method(report, RULE, loader);
        factory.setAccessible(true);
        try {
            XposedInterface.HookHandle handle = framework.hook(factory)
                    .setId("DouAi.Comment.AdFilter")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::intercept);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            DouAiLog.event("comment_ad_filter", "install", "ok", factory.toString());
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings next) {
        this.settings = next;
        this.enabled = next != null && next.commentAdFilter;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getBlockedCount() {
        return blocked.get();
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        if (!enabled) return chain.proceed();
        long total = blocked.incrementAndGet();
        long now = System.currentTimeMillis();
        long previous = lastTrace.get();
        if (now - previous > 5000L && lastTrace.compareAndSet(previous, now)) {
            DouAiLog.event("comment_ad_filter", "block", "suppressed=" + total, RULE);
            log.accept("comment ad inserted slot blocked, total=" + total);
        }
        return null;
    }

    private void unhookAll(Throwable cause) {
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
                // best effort during teardown
            }
        }
        handles.clear();
        if (cause != null) DouAiLog.failure("comment_ad_filter", "install", cause);
    }
}
