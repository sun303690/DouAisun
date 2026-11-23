package com.douai.module.feature;

import android.content.Context;
import android.content.res.Configuration;

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
 * Keeps the host's dark theme in sync with the system night mode.
 *
 * <p>Anchor (dyvv {@code ui.night_mode.v2}): {@code TiktokSkinHelper;->isNightMode()Z}. Views the
 * host builds while the system is already in {@code UI_MODE_NIGHT_YES} still come out light because
 * this flag is cached early; reporting the real system state fixes those without touching the day
 * palette.</p>
 */
public final class NightModeForceHook {

    private static final String RULE = "adv_night_mode_force";

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private final AtomicLong forced = new AtomicLong();
    private volatile ExtendedSettings settings;
    private volatile Context context;

    private NightModeForceHook(ExtendedSettings settings, Context context, Consumer<String> log) {
        this.settings = settings;
        this.context = context;
        this.log = log == null ? ignored -> { } : log;
    }

    public static NightModeForceHook install(XposedInterface framework, HostScanReport report,
                                             ClassLoader loader, ExtendedSettings settings,
                                             Context context, Consumer<String> log) throws Exception {
        NightModeForceHook hook = new NightModeForceHook(settings, context, log);
        try {
            Method target = HostTargets.method(report, RULE, loader);
            target.setAccessible(true);
            XposedInterface.HookHandle handle = framework.hook(target)
                    .setId("DouAi.System.NightMode")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::intercept);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            DouAiLog.event("night_mode", "installed", HostTargets.descriptor(target),
                    "method=" + target.getName());
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        this.settings = settings;
    }

    public boolean isEnabled() {
        ExtendedSettings current = settings;
        return current != null && current.nightModeForce;
    }

    public long getForcedCount() {
        return forced.get();
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (!isEnabled()) return result;
        if (Boolean.TRUE.equals(result)) return result;
        if (!systemIsDark()) return result;
        if (forced.incrementAndGet() == 1L) {
            DouAiLog.event("night_mode", "force", "overridden", "result=false");
            log.accept("night mode aligned with the system configuration");
        }
        return Boolean.TRUE;
    }

    private boolean systemIsDark() {
        Context current = context;
        if (current == null) return false;
        try {
            Configuration config = current.getResources().getConfiguration();
            return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void unhookAll(Throwable cause) {
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
        handles.clear();
        if (cause != null) DouAiLog.failure("night_mode", "unhook", cause);
    }
}
