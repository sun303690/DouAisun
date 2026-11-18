package com.douai.module.feature;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Feed information bar ("post_bar"). Renders the configured template at the bottom of every feed
 * card; all reads go through the host holder + Aweme model only, never the shared User model.
 */
public final class PostBarHook implements AutoCloseable {
    private static final int BAR_TAG = 0x7f0d0a01;
    private final LocalOptions options;
    private final Consumer<String> log;
    private final List<BehaviorHookGroup> groups = new ArrayList<>();
    private volatile boolean closed;

    private PostBarHook(LocalOptions options, Consumer<String> log) {
        this.options = options;
        this.log = log;
    }

    public static PostBarHook install(XposedInterface framework, HostScanReport report,
                                      ClassLoader loader, LocalOptions options,
                                      Consumer<String> log) {
        PostBarHook hook = new PostBarHook(options, log == null ? ignored -> { } : log);
        hook.installHolder(framework, report, loader, "feed_holder", "feed_holder_main_root", "w", "Video");
        hook.installHolder(framework, report, loader, "image_holder", "image_holder_main_root", "f", "Image");
        return hook;
    }

    private void installHolder(XposedInterface framework, HostScanReport report, ClassLoader loader,
                               String rulePrefix, String rootRule, String rootFieldName, String label) {
        BehaviorHookGroup group = new BehaviorHookGroup("post_bar_" + rulePrefix, log);
        try {
            Method selected = resolveMethod(report, rulePrefix + "_page_selected", loader);
            HostCandidate rootCandidate = uniqueCandidate(report, rootRule);
            if (!rootCandidate.getClassDescriptor().equals(classDescriptor(selected.getDeclaringClass()))) {
                throw new IllegalStateException("Post bar root rule does not match callback owner: " + rootRule);
            }
            Field root = selected.getDeclaringClass().getField(rootFieldName);
            if (!View.class.isAssignableFrom(root.getType())) {
                throw new IllegalStateException("Post bar root field is not a View: " + rootRule);
            }
            root.setAccessible(true);
            Method aweme = selected.getDeclaringClass().getMethod("getAweme");
            aweme.setAccessible(true);
            group.add(framework, selected, chain -> {
                Object result = chain.proceed();
                if (!closed) render(chain.getThisObject(), root, aweme);
                return result;
            });
            groups.add(group);
            log.accept("Post bar holder " + label + " installed");
        } catch (Throwable failure) {
            group.close();
            try { log.accept("Post bar " + rulePrefix + " unavailable: " + failure.getClass().getSimpleName()); }
            catch (RuntimeException ignored) { }
        }
    }

    private void render(Object holder, Field rootField, Method awemeGetter) {
        if (!options.postBarEnabled()) return;
        try {
            View root = (View) rootField.get(holder);
            Object aweme = awemeGetter.invoke(holder);
            if (root == null || aweme == null) return;
            Context context = root.getContext();
            if (context == null) return;
            String time = formatTime(longField(aweme, "getCreateTime", "createTime"));
            String duration = formatDuration(longField(aweme, "getDuration", "duration"));
            String location = stringField(aweme, "getIpLocation", "ipLocation");
            if (location == null || location.isEmpty()) location = stringField(aweme, "getCity", "city");
            String progress = String.valueOf(intField(aweme, "getProgress", "progress"));
            PostBarTemplate.Values values = PostBarTemplate.Values.of(
                    time, location, duration, progress, "");
            String text = PostBarTemplate.renderCapped(options.postBarTemplate(), values).trim();
            if (text.isEmpty()) return;
            TextView bar = ensureBar(root, context);
            bar.setText(text);
            bar.setTextSize(options.postBarSize());
            bar.setTextColor(options.postBarColorful()
                    ? progressColor(intField(aweme, "getProgress", "progress"))
                    : parseColor(options.postBarColor()));
        } catch (Throwable ignored) { }
    }

    private static Method resolveMethod(HostScanReport report, String ruleId,
                                        ClassLoader loader) throws Exception {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        String className = candidate.getClassDescriptor().substring(1,
                candidate.getClassDescriptor().length() - 1).replace('/', '.');
        Class<?> owner = Class.forName(className, false, loader);
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(candidate.getMethodName())
                    && methodDescriptor(method).equals(candidate.getMethodDescriptor())) return method;
        }
        throw new NoSuchMethodException("Scanned host method is unavailable: " + ruleId);
    }

    private static HostCandidate uniqueCandidate(HostScanReport report, String ruleId) {
        RuleScanResult result = report == null ? null : report.getResults().get(ruleId);
        if (result == null || result.getStatus() != RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host rule is not unique: " + ruleId);
        }
        return result.getCandidates().get(0);
    }

    private static String classDescriptor(Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static String methodDescriptor(Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) descriptor.append(typeDescriptor(parameter));
        return descriptor.append(')').append(typeDescriptor(method.getReturnType())).toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private static String formatTime(long millis) {
        if (millis <= 0) return "";
        // createTime 单位可能为秒或毫秒，做自适应
        long ms = millis < 100000000000L ? millis * 1000 : millis;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getDefault());
        return f.format(new java.util.Date(ms));
    }

    private static String formatDuration(long durationMillis) {
        if (durationMillis <= 0) return "";
        long total = durationMillis / 1000;
        if (total <= 0) total = durationMillis; // 字段可能已经是秒
        long m = total / 60, s = total % 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
    }

    private static long longField(Object o, String getter, String field) {
        try {
            Method m = o.getClass().getMethod(getter);
            Object v = m.invoke(o);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) { }
        try {
            Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(o);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) { }
        return 0;
    }

    private static int intField(Object o, String getter, String field) {
        long v = longField(o, getter, field);
        return (int) v;
    }

    private static String stringField(Object o, String getter, String field) {
        try {
            Method m = o.getClass().getMethod(getter);
            Object v = m.invoke(o);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) { }
        try {
            Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(o);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) { }
        return "";
    }

    private static TextView ensureBar(View root, Context context) {
        if (root instanceof ViewGroup) {
            View existing = ((ViewGroup) root).findViewWithTag(BAR_TAG);
            if (existing instanceof TextView) return (TextView) existing;
        }
        TextView bar = new TextView(context);
        bar.setTag(BAR_TAG);
        bar.setSingleLine(true);
        bar.setPadding(dp(context, 12), 0, dp(context, 12), dp(context, 8));
        if (root instanceof FrameLayout) {
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
            ((FrameLayout) root).addView(bar, p);
        } else if (root instanceof ViewGroup) {
            ((ViewGroup) root).addView(bar);
        }
        return bar;
    }

    private static int parseColor(String hex) {
        try { return Color.parseColor(hex); } catch (Throwable ignored) { return Color.WHITE; }
    }

    /** Maps the watch progress to a warm-to-cool gradient so the bar reflects the playback state. */
    private static int progressColor(int progress) {
        int clamped = Math.max(0, Math.min(100, progress));
        float ratio = clamped / 100f;
        int red = (int) (255 * (1f - ratio) + 60 * ratio);
        int green = (int) (180 * (1f - ratio) + 200 * ratio);
        int blue = (int) (60 * (1f - ratio) + 255 * ratio);
        return Color.rgb(red, green, blue);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override public void close() {
        closed = true;
        for (BehaviorHookGroup group : groups) group.close();
        groups.clear();
    }
}
