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
 * Hides the feed danmaku layer by forcing every danmaku UI module to report
 * {@link android.view.View#GONE}.
 *
 * <p>Anchor (Dyvv {@code controls.role.factory.v3.video_danmaku.0}):
 * {@code FeedDanmakuModule;->visibility(Lcom/ss/android/ugc/aweme/kiwi/model/QModel;)I}.
 */
public final class DanmakuHideHook {

    private static final String RULE = "adv_danmaku_visibility";
    private static final int GONE = 8;

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private final AtomicLong hidden = new AtomicLong();
    private volatile boolean enabled;

    private DanmakuHideHook(ExtendedSettings settings, Consumer<String> log) {
        this.log = log;
        this.enabled = settings != null && settings.hideDanmaku;
    }

    public static DanmakuHideHook install(XposedInterface framework, HostScanReport report,
                                          ClassLoader loader, ExtendedSettings settings,
                                          Consumer<String> log) throws Exception {
        Consumer<String> sink = log == null ? ignored -> { } : log;
        Method visibility = HostTargets.method(report, RULE, loader);
        visibility.setAccessible(true);

        DanmakuHideHook hook = new DanmakuHideHook(settings, sink);
        try {
            XposedInterface.HookHandle handle = framework.hook(visibility)
                    .setId("DouAi.Feed.DanmakuHide")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::intercept);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            DouAiLog.event("hide_danmaku", "installed", HostTargets.descriptor(visibility),
                    "method=" + visibility.getName());
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        enabled = settings != null && settings.hideDanmaku;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getHiddenCount() {
        return hidden.get();
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        if (!enabled) return chain.proceed();
        hidden.incrementAndGet();
        return GONE;
    }

    private void unhookAll(Throwable cause) {
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
        handles.clear();
        DouAiLog.failure("hide_danmaku", "unhook", cause);
    }
}
