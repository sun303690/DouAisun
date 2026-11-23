package com.douai.module.feature;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 屏蔽直播预览 —— 信息流里长按/滑动时不再弹出直播小窗预览。
 *
 * <p>锚点 {@code live_preview.gate.v1}：
 * {@code LX/13G6;->LIZ(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z}
 * 是直播预览的准入判定，返回 false 即不再进入预览流程。
 */
public final class LivePreviewBlockHook implements AutoCloseable {
    static final String RULE = "adv_live_preview_block";

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final AtomicLong blocked = new AtomicLong(0L);
    private final AtomicLong lastTrace = new AtomicLong(0L);
    private final Consumer<String> log;
    private volatile boolean enabled;

    private LivePreviewBlockHook(ExtendedSettings settings, Consumer<String> log) {
        this.enabled = settings != null && settings.livePreviewBlock;
        this.log = log;
    }

    public static LivePreviewBlockHook install(XposedInterface framework, HostScanReport report,
                                               ClassLoader loader, ExtendedSettings settings,
                                               Consumer<String> log) throws Exception {
        Consumer<String> sink = log == null ? ignored -> { } : log;
        Method gate = HostTargets.method(report, RULE, loader);
        if (gate.getReturnType() != boolean.class) {
            throw new IllegalStateException("Live preview gate signature changed: "
                    + HostTargets.descriptor(gate));
        }
        gate.setAccessible(true);

        LivePreviewBlockHook hook = new LivePreviewBlockHook(settings, sink);
        try {
            XposedInterface.HookHandle handle = framework.hook(gate)
                    .setId("DouAi.Feed.LivePreviewBlock")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::intercept);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            DouAiLog.event("live_preview_block", "installed", HostTargets.descriptor(gate),
                    "method=" + gate.getName());
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        enabled = settings != null && settings.livePreviewBlock;
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
        long last = lastTrace.get();
        if (now - last > 3000L && lastTrace.compareAndSet(last, now)) {
            DouAiLog.event("live_preview_block", "blocked", "live_preview", "total=" + total);
            if (log != null) log.accept("已拦截直播预览 " + total + " 次");
        }
        return Boolean.FALSE;
    }

    private void unhookAll(Throwable cause) {
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
                cause.addSuppressed(ignored);
            }
        }
        handles.clear();
    }

    @Override
    public void close() {
        unhookAll(new IllegalStateException("LivePreviewBlockHook closed"));
    }
}
