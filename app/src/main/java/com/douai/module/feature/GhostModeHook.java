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
 * Keeps the account offline while the app is in use ("ghost" mode).
 *
 * <p>The IM layer publishes the local active status through a single reporter,
 * {@code UserActiveStatusReporter.LIZIZ(LX/1LiV;Lcom/ss/android/quota/BDNetworkTagContextProviderAdapter;)V}.
 * Skipping that method keeps the outbound online report from ever being enqueued, so peers keep
 * seeing the last state the server knows. Everything else in the reporter (typing indicator,
 * relation refresh) is untouched because only the publish entry is hooked.
 */
public final class GhostModeHook implements AutoCloseable {
    private static final String RULE = "adv_ghost_online_reporter";
    /** One trace line per minute is enough to prove the hook fires without flooding the log. */
    private static final long TRACE_INTERVAL_MS = 60_000L;

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private final AtomicLong lastTrace = new AtomicLong(0L);
    private final AtomicLong suppressed = new AtomicLong(0L);
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
        if (reporter.getParameterCount() != 2
                || reporter.getReturnType() != void.class) {
            throw new IllegalStateException("Active status reporter signature changed: "
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
        long previous = lastTrace.get();
        if (now - previous >= TRACE_INTERVAL_MS && lastTrace.compareAndSet(previous, now)) {
            DouAiLog.event("ghost_mode", "suppress", "online_report", "count=" + total);
            if (log != null) log.accept("幽灵模式: 已拦截在线状态上报 #" + total);
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
