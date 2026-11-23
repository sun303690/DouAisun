package com.douai.module.feature;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.HostTargets;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 幽灵模式 —— 拦截 IM 在线状态上报，让对方始终看到上一次的在线状态。
 *
 * <p>锚点 {@code ghost.online.reporter.v2}：
 * {@code Lcom/ss/android/ugc/aweme/im/sdk/relations/core/active/internal/UserActiveStatusReporter;->LIZIZ(LX/1LiV;Lcom/ss/android/ugc/quota/BDNetworkTagContextProviderAdapter;)V}
 * 是上报自身活跃状态的唯一出口，直接吞掉即不再上报。
 */
public final class GhostModeHook implements AutoCloseable {
    static final String RULE = "adv_ghost_online_reporter";

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final AtomicLong suppressed = new AtomicLong(0L);
    private final AtomicLong lastTrace = new AtomicLong(0L);
    private final Consumer<String> log;
    private volatile boolean enabled;

    private GhostModeHook(ExtendedSettings settings, Consumer<String> log) {
        this.enabled = settings != null && settings.ghostMode;
        this.log = log;
    }

    public static GhostModeHook install(XposedInterface framework, HostScanReport report,
                                        ClassLoader loader, ExtendedSettings settings,
                                        Consumer<String> log) throws Exception {
        Consumer<String> sink = log == null ? ignored -> { } : log;
        Method reporter = HostTargets.method(report, RULE, loader);
        if (reporter.getReturnType() != void.class) {
            throw new IllegalStateException("Ghost reporter signature changed: "
                    + HostTargets.descriptor(reporter));
        }
        reporter.setAccessible(true);

        GhostModeHook hook = new GhostModeHook(settings, sink);
        try {
            XposedInterface.HookHandle handle = framework.hook(reporter)
                    .setId("DouAi.Im.GhostMode")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::intercept);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            DouAiLog.event("ghost_mode", "installed", HostTargets.descriptor(reporter),
                    "method=" + reporter.getName());
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        enabled = settings != null && settings.ghostMode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getSuppressedCount() {
        return suppressed.get();
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        if (!enabled) return chain.proceed();
        long total = suppressed.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastTrace.get();
        if (now - last > 3000L && lastTrace.compareAndSet(last, now)) {
            DouAiLog.event("ghost_mode", "suppressed", "online", "total=" + total);
            if (log != null) log.accept("幽灵模式已拦截在线上报 " + total + " 次");
        }
        return null;
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
        unhookAll(new IllegalStateException("GhostModeHook closed"));
    }
}
