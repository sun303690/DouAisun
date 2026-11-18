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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Feed information bar ("post_bar"). Renders the configured template at the bottom of every feed
 * card.
 *
 * <p>The host model exposes its attributes as plain {@code @JvmField} members: the DEX carries the
 * fields plus Kotlin's synthetic {@code getXxx$annotations} methods, but no callable
 * {@code getXxx()}. Every read therefore prefers a plain getter when the host happens to declare
 * one and falls back to a direct field access across the whole superclass chain.</p>
 */
public final class PostBarHook implements AutoCloseable {
    private static final int BAR_TAG = 0x7f0d0a01;

    /* Host model field names, verified against the 41.0.0 DEX. */
    private static final String FIELD_CREATE_TIME = "createTime";
    private static final String FIELD_DURATION = "duration";
    private static final String FIELD_AUTHOR = "author";
    private static final String FIELD_PLAY_PROGRESS = "playProgress";
    private static final String FIELD_IP_LOCATION = "ipLocation";
    private static final String FIELD_PROVINCE = "province";
    private static final String FIELD_CITY = "city";
    private static final String FIELD_CITY_NAME = "cityName";

    /** Longest attribution still treated as province level, so a city may be appended. */
    private static final int PROVINCE_TEXT_LIMIT = 8;

    private volatile LocalOptions options;
    private final Consumer<String> log;
    private final List<BehaviorHookGroup> groups = new ArrayList<>();
    private volatile boolean closed;

    private PostBarHook(LocalOptions options, Consumer<String> log) {
        this.options = options == null ? new LocalOptions(java.util.Collections.emptyMap()) : options;
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

    /** Applies a freshly published configuration without reinstalling the hooks. */
    public void setOptions(LocalOptions options) {
        if (options != null) this.options = options;
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
            Field root = findField(selected.getDeclaringClass(), rootFieldName);
            if (root == null) throw new IllegalStateException("Post bar root field is missing: " + rootFieldName);
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
        LocalOptions config = options;
        if (!config.postBarEnabled()) return;
        try {
            View root = (View) rootField.get(holder);
            Object aweme = awemeGetter.invoke(holder);
            if (root == null || aweme == null) return;
            Context context = root.getContext();
            if (context == null) return;
            long durationSeconds = durationSeconds(aweme);
            long positionMillis = playProgressMillis(aweme);
            String time = formatTime(longField(aweme, FIELD_CREATE_TIME));
            String duration = formatSeconds(durationSeconds);
            String location = publishLocation(aweme, config);
            String progress = formatProgress(positionMillis, durationSeconds);
            PostBarTemplate.Values values = PostBarTemplate.Values.of(
                    time, location, duration, progress, "");
            String text = PostBarTemplate.renderCapped(config.postBarTemplate(), values).trim();
            if (text.isEmpty()) return;
            TextView bar = ensureBar(root, context);
            bar.setText(text);
            bar.setTextSize(config.postBarSize());
            bar.setTextColor(config.postBarColorful()
                    ? progressColor(positionMillis, durationSeconds)
                    : parseColor(config.postBarColor()));
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

    /**
     * The publish location: a configured spoof wins, otherwise the author's IP attribution is used,
     * refined with the city when the attribution only names the province, then the city alone and
     * finally the province. A missing attribution renders as an em dash.
     */
    private static String publishLocation(Object aweme, LocalOptions config) {
        String spoofed = config.ipLocation();
        if (!spoofed.isEmpty()) return spoofed;
        Object author = objectField(aweme, FIELD_AUTHOR);
        // Older hosts keep the attribution on the feed model itself.
        String ip = firstNonEmpty(stringField(author, FIELD_IP_LOCATION),
                stringField(aweme, FIELD_IP_LOCATION));
        String province = firstNonEmpty(stringField(author, FIELD_PROVINCE),
                stringField(aweme, FIELD_PROVINCE));
        String city = firstNonEmpty(stringField(author, FIELD_CITY_NAME),
                stringField(author, FIELD_CITY), stringField(aweme, FIELD_CITY));
        if (!ip.isEmpty()) {
            if (!city.isEmpty() && ip.length() <= PROVINCE_TEXT_LIMIT && !ip.contains(city)) {
                return ip + " " + city;
            }
            return ip;
        }
        if (!city.isEmpty()) {
            if (!province.isEmpty() && !city.contains(province)) return province + " " + city;
            return city;
        }
        if (!province.isEmpty()) return province;
        return "—";
    }

    /** Total video length in seconds. The host stores seconds on the feed model. */
    private static long durationSeconds(Object aweme) {
        long seconds = longField(aweme, FIELD_DURATION);
        return seconds < 0 ? 0 : seconds;
    }

    /** Watched position in milliseconds, read from {@code PlayProgressStruct.playProgress}. */
    private static long playProgressMillis(Object aweme) {
        Object progress = objectField(aweme, FIELD_PLAY_PROGRESS);
        if (progress == null) return 0;
        long millis = longField(progress, FIELD_PLAY_PROGRESS);
        return millis < 0 ? 0 : millis;
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

    /** Renders a second count as {@code mm:ss}, switching to {@code h:mm:ss} past one hour. */
    private static String formatSeconds(long totalSeconds) {
        if (totalSeconds <= 0) return "";
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    /** Renders {@code 当前播放位置|总时长}; empty when either side is unknown. */
    private static String formatProgress(long positionMillis, long durationSeconds) {
        if (positionMillis <= 0 || durationSeconds <= 0) return "";
        long position = positionMillis / 1000;
        if (position > durationSeconds) position = durationSeconds;
        return formatSeconds(position) + "|" + formatSeconds(durationSeconds);
    }

    private static String firstNonEmpty(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) return candidate;
        }
        return "";
    }

    /** Resolves a declared field across the whole superclass chain. */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // Keep walking: the host splits its models across base classes.
            }
        }
        return null;
    }

    private static Object fieldValue(Object target, String name) {
        if (target == null) return null;
        try {
            Field field = findField(target.getClass(), name);
            return field == null ? null : field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object objectField(Object target, String name) {
        return fieldValue(target, name);
    }

    private static long longField(Object target, String name) {
        Object value = fieldValue(target, name);
        if (value instanceof Number) return ((Number) value).longValue();
        // Some hosts wrap the value in a boxed accessor pair.
        if (value instanceof String) {
            try { return Long.parseLong(((String) value).trim()); } catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    private static String stringField(Object target, String name) {
        Object value = fieldValue(target, name);
        return value instanceof String ? (String) value : "";
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
    private static int progressColor(long positionMillis, long durationSeconds) {
        float ratio = durationSeconds <= 0 ? 0f
                : Math.max(0f, Math.min(1f, (positionMillis / 1000f) / durationSeconds));
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
