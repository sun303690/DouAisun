package com.douai.module.feature;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shows the exact like count instead of the abbreviated form (11万 -> 116382).
 *
 * <p>The host renders comment like counters through a single formatter,
 * {@code LX/11vs;->LIZIZ(J)Ljava/lang/CharSequence;}. Replacing that return value covers every
 * call site (bottom-right digg component, digg-bury view, new/old digg views) with one hook.
 */
public final class ExactLikeCountHook implements AutoCloseable {
    private static final String RULE = "adv_exact_like_formatter";

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private volatile boolean enabled;

    private ExactLikeCountHook(ExtendedSettings settings, Consumer<String> log) {
        this.enabled = settings != null && settings.exactLikeCount;
        this.log = log;
    }

    public static ExactLikeCountHook install(XposedInterface framework, HostScanReport report,
                                             ClassLoader loader, ExtendedSettings settings,
                                             Consumer<String> log) throws Exception {
        Consumer<String> sink = log == null ? ignored -> { } : log;
        Method formatter = HostTargets.method(report, RULE, loader);
        if (formatter.getParameterCount() != 1
                || formatter.getParameterTypes()[0] != long.class
                || !CharSequence.class.isAssignableFrom(formatter.getReturnType())) {
            throw new IllegalStateException("Like count formatter signature changed: "
                    + HostTargets.descriptor(formatter));
        }
        formatter.setAccessible(true);

        ExactLikeCountHook hook = new ExactLikeCountHook(settings, sink);
        try {
            XposedInterface.HookHandle handle = framework.hook(formatter)
                    .setId("DouAi.Comment.ExactLikeCount")
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
        enabled = settings != null && settings.exactLikeCount;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        if (!enabled) return chain.proceed();
        Object[] args = chain.getArgs().toArray();
        if (args.length != 1 || !(args[0] instanceof Long)) return chain.proceed();
        long value = (Long) args[0];
        if (value < 0) return chain.proceed();

        Object rendered = chain.proceed();
        CharSequence native0 = rendered instanceof CharSequence ? (CharSequence) rendered : null;
        String exact = Long.toString(value);
        if (native0 != null && exact.contentEquals(native0)) return rendered;
        if (native0 != null && !needsRewrite(native0)) return rendered;
        if (native0 == null) return rendered;

        DouAiLog.event("exact_like", "render", "exact",
                "value=" + value + " native=" + native0 + " exact=" + exact);
        if (log != null && value >= 10000L) {
            log.accept("精确点赞数: " + native0 + " -> " + exact);
        }
        return exact;
    }

    /** True when the host collapsed the number into a 万/亿/w/k suffix form. */
    private static boolean needsRewrite(CharSequence text) {
        int length = text.length();
        if (length == 0) return false;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '万' || c == '亿' || c == 'w' || c == 'W'
                    || c == 'k' || c == 'K' || c == 'M' || c == 'm') {
                return true;
            }
        }
        return false;
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
        unhookAll(new IllegalStateException("ExactLikeCountHook closed"));
    }
}
