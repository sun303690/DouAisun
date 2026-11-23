package com.douai.module.feature;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Dyvv {@code other.mistouch.double_click_like.v2} 的移植：关闭「双击视频误点赞」。
 *
 * <p>宿主在 {@code VideoViewHolder#handleDoubleClick(Aweme)} 与
 * {@code FeedImageViewHolder#handleDoubleClick(Aweme)} 中处理双击手势，双击默认触发点赞。
 * 本 Hook 在开关打开时直接跳过原方法，从而阻止误触点赞。
 */
public final class MistouchDoubleClickHook {

    static final String RULE = "adv_mistouch_double_click";

    private final Consumer<String> logger;
    private final AtomicLong blocked = new AtomicLong(0L);
    private volatile boolean enabled;

    private MistouchDoubleClickHook(Consumer<String> logger) {
        this.logger = logger;
    }

    public static MistouchDoubleClickHook install(XposedInterface framework, HostScanReport report,
                                                  ClassLoader loader, ExtendedSettings settings,
                                                  Consumer<String> logger) {
        MistouchDoubleClickHook hook = new MistouchDoubleClickHook(logger);
        hook.setSettings(settings);
        if (framework == null) {
            note(logger, "Mistouch double-click hook skipped: no Xposed framework");
            return hook;
        }
        try {
            Method target = HostTargets.method(report, RULE, loader);
            if (target == null) {
                note(logger, "Mistouch double-click hook skipped: rule " + RULE + " not resolved");
                return hook;
            }
            framework.hook(target)
                    .setId("douai." + RULE)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> hook.intercept(chain));
            note(logger, "Mistouch double-click hook attached to " + target.getDeclaringClass().getName());
        } catch (Throwable failure) {
            DouAiLog.failure("mistouch_double_click", "installation", failure);
            note(logger, "Mistouch double-click hook failed: " + failure);
        }
        return hook;
    }

    public void setSettings(ExtendedSettings settings) {
        enabled = settings != null && settings.mistouchDoubleClick;
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
        if (total <= 3L || total % 50L == 0L) {
            note(logger, "Mistouch double-click like suppressed (total=" + total + ")");
        }
        return null;
    }

    private static void note(Consumer<String> logger, String message) {
        if (logger != null) {
            try {
                logger.accept(message);
            } catch (Throwable ignored) {
                // Logging must never break the host.
            }
        }
    }
}
