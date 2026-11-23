package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

import io.github.libxposed.api.XposedInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广告跳过：轮询当前页面上的可点击节点，命中「跳过/跳过广告」或倒计时秒数时点掉。
 *
 * <p>移植自 dyguard 的 {@code b}（9 方法，正则匹配 + {@code performClick}）。原实现挂在
 * AccessibilityService 上，DouAi 没有无障碍服务，因此改为在 Activity 的 View 树上轮询：
 * 每 {@link #SCAN_INTERVAL_MS} 毫秒遍历一次 decorView，文本命中即调用 {@code performClick}。
 *
 * <p>判定顺序与原实现一致：
 * <ol>
 *   <li>文本含「跳过」关键字 → 直接点。</li>
 *   <li>文本是纯数字（倒计时剩余秒数）且父链里同时出现「广告」或可点击 → 点。</li>
 * </ol>
 * 已经点过的 view 记在 {@link #clicked} 里，避免同一节点反复点击。
 */
public final class SkipAdHook implements Application.ActivityLifecycleCallbacks, AutoCloseable {

    /** 一轮扫描间隔，与原实现的无障碍事件回调频率接近。 */
    private static final long SCAN_INTERVAL_MS = 400L;
    /** View 树最大深度，防止异常布局无限递归。 */
    private static final int MAX_TREE_DEPTH = 48;
    /** 倒计时秒数上限，超过这个值的纯数字大概率是热度/点赞数而非倒计时。 */
    private static final int MAX_COUNTDOWN_SECONDS = 120;

    /** 「跳过」类文案，原实现的两条常量合并成一条正则。 */
    private static final Pattern SKIP_TEXT = Pattern.compile("跳过|跳过广告|关闭广告");
    /** 倒计时文案，原实现为 {@code (\d{1,3})\s*(?:秒|s)}。 */
    private static final Pattern COUNTDOWN_TEXT = Pattern.compile("(\\d{1,3})\\s*(?:秒|s)");
    /** 纯数字，原实现的 {@code \d{1,3}} 分支。 */
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\d{1,3}$");
    /** 广告标识，用于给纯数字节点做上下文确认。 */
    private static final Pattern AD_MARK = Pattern.compile("广告|AD|ad");

    private final Consumer<String> log;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable scanTask = this::scanOnce;
    /** 已点击节点，用 identity 比较，弱引用避免持有已销毁 view。 */
    private final List<WeakReference<View>> clicked = new ArrayList<>();

    private Application application;
    private WeakReference<Activity> current = new WeakReference<>(null);
    private boolean enabled;
    private boolean closed;
    private long clickedTotal;

    private SkipAdHook(ExtendedSettings settings, Consumer<String> log) {
        this.log = log == null ? ignored -> { } : log;
        this.enabled = settings != null && settings.skipAd;
    }

    public static SkipAdHook install(ClassLoader loader, Context context,
                                     ExtendedSettings settings, Consumer<String> log) {
        SkipAdHook hook = new SkipAdHook(settings, log);
        try {
            if (context != null && context.getApplicationContext() instanceof Application) {
                hook.application = (Application) context.getApplicationContext();
                hook.application.registerActivityLifecycleCallbacks(hook);
            }
            if (context instanceof Activity) hook.current = new WeakReference<>((Activity) context);
            if (hook.enabled) hook.scheduleScan(SCAN_INTERVAL_MS);
            log.accept("skip ad hook installed: enabled=" + hook.enabled);
            return hook;
        } catch (Throwable failure) {
            hook.close();
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        if (closed) return;
        boolean next = settings != null && settings.skipAd;
        boolean changed = next != enabled;
        enabled = next;
        if (!changed) return;
        runOnMain(() -> {
            if (closed) return;
            if (enabled) scheduleScan(0L);
            else mainHandler.removeCallbacks(scanTask);
        });
    }

    public boolean isEnabled() {
        return enabled && !closed;
    }

    /** 诊断用：累计点击次数。 */
    public long clickedCount() {
        return clickedTotal;
    }

    // ---------------------------------------------------------------- 扫描

    private void scanOnce() {
        if (closed || !enabled) return;
        Activity activity = current.get();
        if (activity == null || activity.isFinishing()) {
            mainHandler.postDelayed(scanTask, SCAN_INTERVAL_MS);
            return;
        }
        try {
            sweep(activity);
        } catch (Throwable failure) {
            DouAiLog.failure("skip_ad", "sweep", failure);
        }
        if (!closed && enabled) mainHandler.postDelayed(scanTask, SCAN_INTERVAL_MS);
    }

    private void sweep(Activity activity) {
        if (activity.getWindow() == null) return;
        View decor = activity.getWindow().peekDecorView();
        if (decor == null) return;
        pruneClicked();
        List<View> hits = new ArrayList<>();
        collect(decor, hits, 0);
        for (View view : hits) {
            if (isClicked(view)) continue;
            if (!performSkip(view)) continue;
            clicked.add(new WeakReference<>(view));
            clickedTotal++;
            DouAiLog.event("skip_ad", "clicked", textOf(view), "total=" + clickedTotal);
            break; // 一轮只点一个，避免误触同屏多个元素
        }
    }

    private void collect(View node, List<View> out, int depth) {
        if (depth > MAX_TREE_DEPTH) return;
        if (matchesSkip(node)) out.add(node);
        if (!(node instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) node;
        int count = group.getChildCount();
        for (int index = 0; index < count; index++) {
            View child;
            try {
                child = group.getChildAt(index);
            } catch (Throwable ignored) {
                continue;
            }
            if (child != null) collect(child, out, depth + 1);
        }
    }

    /** 文本判定，与原实现的正则顺序一致。 */
    private static boolean matchesSkip(View node) {
        String text = textOf(node);
        if (text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (SKIP_TEXT.matcher(lower).find()) return true;
        Matcher countdown = COUNTDOWN_TEXT.matcher(lower);
        if (countdown.find()) {
            int seconds = parseSeconds(countdown.group(1));
            return seconds >= 0 && seconds <= MAX_COUNTDOWN_SECONDS || hasAdContext(node);
        }
        if (BARE_NUMBER.matcher(lower).matches()) {
            int seconds = parseSeconds(lower);
            if (seconds < 0 || seconds > MAX_COUNTDOWN_SECONDS) return false;
            // 纯数字单独出现太容易误判（点赞数、时长），必须有广告上下文或自身可点击。
            return hasAdContext(node) || isClickableSelf(node);
        }
        return false;
    }

    private static int parseSeconds(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    /** 向上找若干层，看兄弟/父节点里有没有「广告」标识。 */
    private static boolean hasAdContext(View node) {
        View cursor = node;
        for (int level = 0; level < 4 && cursor != null; level++) {
            if (AD_MARK.matcher(textOf(cursor).toLowerCase(Locale.ROOT)).find()) return true;
            if (cursor instanceof ViewGroup && containsAdMark((ViewGroup) cursor, 2)) return true;
            ViewParent parent = cursor.getParent();
            cursor = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static boolean containsAdMark(ViewGroup group, int depth) {
        if (depth < 0) return false;
        int count = group.getChildCount();
        for (int index = 0; index < count; index++) {
            View child = group.getChildAt(index);
            if (child == null) continue;
            if (AD_MARK.matcher(textOf(child).toLowerCase(Locale.ROOT)).find()) return true;
            if (child instanceof ViewGroup && containsAdMark((ViewGroup) child, depth - 1)) return true;
        }
        return false;
    }

    private static boolean isClickableSelf(View node) {
        return node.isClickable() || node.isFocusable();
    }

    /** 找到真正处理点击的节点再点：自身不可点就向上找最近的 clickable 祖先。 */
    private static boolean performSkip(View node) {
        View target = node;
        for (int level = 0; level < 4 && target != null; level++) {
            if (target.isShown() && target.isEnabled()) {
                try {
                    if (target.performClick()) return true;
                } catch (Throwable ignored) {
                    // 继续向上试
                }
            }
            ViewParent parent = target.getParent();
            target = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static String textOf(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text == null ? "" : text.toString().trim();
        }
        CharSequence description = view.getContentDescription();
        return description == null ? "" : description.toString().trim();
    }

    private boolean isClicked(View view) {
        for (WeakReference<View> reference : clicked) {
            View known = reference.get();
            if (known != null && known == view) return true;
        }
        return false;
    }

    private void pruneClicked() {
        clicked.removeIf(reference -> reference.get() == null);
        // 长会话下避免列表无限增长，只保留最近 64 个。
        while (clicked.size() > 64) clicked.remove(0);
    }

    private void scheduleScan(long delay) {
        mainHandler.removeCallbacks(scanTask);
        if (!closed && enabled) mainHandler.postDelayed(scanTask, delay);
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override public void onActivityResumed(Activity activity) {
        current = new WeakReference<>(activity);
        if (enabled) scheduleScan(0L);
    }

    @Override public void onActivityPaused(Activity activity) {
        Activity resumed = current.get();
        if (resumed != null && resumed != activity) return;
        current = new WeakReference<>(null);
        mainHandler.removeCallbacks(scanTask);
    }

    @Override public void onActivityDestroyed(Activity activity) {
        if (current.get() == activity) {
            current = new WeakReference<>(null);
            clicked.clear();
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }

    @Override public void onActivityStarted(Activity activity) { }

    @Override public void onActivityStopped(Activity activity) { }

    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    @Override public void close() {
        if (closed) return;
        closed = true;
        mainHandler.removeCallbacks(scanTask);
        if (application != null) {
            try {
                application.unregisterActivityLifecycleCallbacks(this);
            } catch (Throwable ignored) { }
            application = null;
        }
        clicked.clear();
    }
}
