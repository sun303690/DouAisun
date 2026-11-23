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
import java.util.regex.Pattern;

/**
 * dyguard 的两个 View 树能力合并实现，原类分别是 {@code U0}（5 方法）和 {@code X1}（16 方法）。
 *
 * <h2>U0 · 自动展开收起评论</h2>
 * 评论区把长评论折叠成「查看收起的评论 N 条」一类入口，原实现 hook 宿主
 * {@code a(Object)Object} / {@code b(Object,Z,BiFunction)Object}，在数据层把
 * {@code collapsed} 位强制置 false。DouAi 不挂宿主列表构造，改为在 View 树上找到该入口并点掉，
 * 效果一致且不依赖混淆名。
 *
 * <h2>X1 · 隐藏名单计数</h2>
 * 原实现统计「隐私名单」数量并在入口上隐藏计数徽标。这里按同样思路处理个人主页的
 * 关注/粉丝/获赞计数文本，把纯数字节点隐藏（保留条目与跳转能力）。
 */
public final class DyguardUiHook implements Application.ActivityLifecycleCallbacks, AutoCloseable {

    private static final long SCAN_INTERVAL_MS = 600L;
    private static final int MAX_TREE_DEPTH = 48;

    /** U0 的入口文案，取自原类常量「查看收起的评论」并补齐常见变体。 */
    private static final Pattern EXPAND_COMMENT = Pattern.compile(
            "查看收起的评论|收起(的)?评论|展开(更多)?(的)?评论|查看全部评论|更多评论");
    /** X1 的计数节点：纯数字或「1.2万」这类缩写。 */
    private static final Pattern COUNT_TEXT = Pattern.compile("^\\d+(\\.\\d+)?\\s*(万|w|W|亿)?$");
    /** 计数所在父行的语义，命中才隐藏，避免误伤时长/点赞数。 */
    private static final Pattern COUNT_LABEL = Pattern.compile("关注|粉丝|获赞|互关|访客|被赞");

    private final Consumer<String> log;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable scanTask = this::scanOnce;
    private final List<WeakReference<View>> clicked = new ArrayList<>();
    private final List<WeakReference<View>> hidden = new ArrayList<>();

    private Application application;
    private WeakReference<Activity> current = new WeakReference<>(null);
    private boolean expandComments;
    private boolean hideCount;
    private boolean closed;

    private DyguardUiHook(ExtendedSettings settings, Consumer<String> log) {
        this.log = log == null ? ignored -> { } : log;
        apply(settings);
    }

    public static DyguardUiHook install(ClassLoader loader, Context context,
                                        ExtendedSettings settings, Consumer<String> log) {
        DyguardUiHook hook = new DyguardUiHook(settings, log);
        try {
            if (context != null && context.getApplicationContext() instanceof Application) {
                hook.application = (Application) context.getApplicationContext();
                hook.application.registerActivityLifecycleCallbacks(hook);
            }
            if (context instanceof Activity) hook.current = new WeakReference<>((Activity) context);
            if (hook.anyEnabled()) hook.scheduleScan(SCAN_INTERVAL_MS);
            log.accept("dyguard ui hook installed: expand=" + hook.expandComments
                    + " hideCount=" + hook.hideCount);
            return hook;
        } catch (Throwable failure) {
            hook.close();
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        if (closed) return;
        boolean previous = anyEnabled();
        apply(settings);
        if (previous == anyEnabled()) return;
        runOnMain(() -> {
            if (closed) return;
            if (anyEnabled()) scheduleScan(0L);
            else mainHandler.removeCallbacks(scanTask);
        });
    }

    public boolean isEnabled() {
        return anyEnabled();
    }

    private void apply(ExtendedSettings settings) {
        expandComments = settings != null && settings.expandHiddenComments;
        hideCount = settings != null && settings.hideListCount;
    }

    private boolean anyEnabled() {
        return !closed && (expandComments || hideCount);
    }

    // ---------------------------------------------------------------- 扫描

    private void scanOnce() {
        if (!anyEnabled()) return;
        Activity activity = current.get();
        if (activity == null || activity.isFinishing()) {
            mainHandler.postDelayed(scanTask, SCAN_INTERVAL_MS);
            return;
        }
        try {
            sweep(activity);
        } catch (Throwable failure) {
            DouAiLog.failure("dyguard_ui", "sweep", failure);
        }
        if (anyEnabled()) mainHandler.postDelayed(scanTask, SCAN_INTERVAL_MS);
    }

    private void sweep(Activity activity) {
        if (activity.getWindow() == null) return;
        View decor = activity.getWindow().peekDecorView();
        if (decor == null) return;
        prune(clicked);
        prune(hidden);
        if (expandComments) expand(decor);
        if (hideCount) hideCounts(decor);
    }

    /** U0：找到「查看收起的评论」入口并点一次，同一节点只点一次。 */
    private void expand(View decor) {
        List<View> hits = new ArrayList<>();
        collectByText(decor, EXPAND_COMMENT, hits, 0);
        for (View view : hits) {
            if (isKnown(clicked, view)) continue;
            View target = clickableAncestor(view);
            if (target == null) continue;
            clicked.add(new WeakReference<>(view));
            try {
                if (target.performClick()) {
                    DouAiLog.event("dyguard_ui", "expand_comments", textOf(view), "");
                }
            } catch (Throwable failure) {
                DouAiLog.failure("dyguard_ui", "expand_click", failure);
            }
            return; // 一次只点一个，等列表刷新
        }
    }

    /** X1：把父行语义是关注/粉丝/获赞的纯数字节点隐藏。 */
    private void hideCounts(View decor) {
        List<View> numbers = new ArrayList<>();
        collectByText(decor, COUNT_TEXT, numbers, 0);
        for (View view : numbers) {
            if (!view.isShown() || isKnown(hidden, view)) continue;
            if (!hasCountLabel(view)) continue;
            try {
                view.setVisibility(View.INVISIBLE);
                hidden.add(new WeakReference<>(view));
                DouAiLog.event("dyguard_ui", "hide_count", textOf(view), "");
            } catch (Throwable failure) {
                DouAiLog.failure("dyguard_ui", "hide_count", failure);
            }
        }
    }

    private static boolean hasCountLabel(View node) {
        View cursor = node;
        for (int level = 0; level < 3 && cursor != null; level++) {
            if (cursor instanceof ViewGroup && containsLabel((ViewGroup) cursor, 2)) return true;
            ViewParent parent = cursor.getParent();
            cursor = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static boolean containsLabel(ViewGroup group, int depth) {
        if (depth < 0) return false;
        int count = group.getChildCount();
        for (int index = 0; index < count; index++) {
            View child = group.getChildAt(index);
            if (child == null) continue;
            if (COUNT_LABEL.matcher(textOf(child)).find()) return true;
            if (child instanceof ViewGroup && containsLabel((ViewGroup) child, depth - 1)) return true;
        }
        return false;
    }

    private static void collectByText(View node, Pattern pattern, List<View> out, int depth) {
        if (depth > MAX_TREE_DEPTH) return;
        String text = textOf(node);
        if (!text.isEmpty() && pattern.matcher(text).find()) out.add(node);
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
            if (child != null) collectByText(child, pattern, out, depth + 1);
        }
    }

    private static View clickableAncestor(View node) {
        View cursor = node;
        for (int level = 0; level < 4 && cursor != null; level++) {
            if (cursor.isShown() && cursor.isEnabled() && cursor.isClickable()) return cursor;
            ViewParent parent = cursor.getParent();
            cursor = parent instanceof View ? (View) parent : null;
        }
        return node.isShown() ? node : null;
    }

    private static String textOf(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text == null ? "" : text.toString().trim();
        }
        CharSequence description = view.getContentDescription();
        return description == null ? "" : description.toString().trim();
    }

    private static boolean isKnown(List<WeakReference<View>> bucket, View view) {
        for (WeakReference<View> reference : bucket) {
            View known = reference.get();
            if (known != null && known == view) return true;
        }
        return false;
    }

    private static void prune(List<WeakReference<View>> bucket) {
        bucket.removeIf(reference -> reference.get() == null);
        while (bucket.size() > 128) bucket.remove(0);
    }

    private void scheduleScan(long delay) {
        mainHandler.removeCallbacks(scanTask);
        if (anyEnabled()) mainHandler.postDelayed(scanTask, delay);
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override public void onActivityResumed(Activity activity) {
        current = new WeakReference<>(activity);
        if (anyEnabled()) scheduleScan(0L);
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
            hidden.clear();
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
        hidden.clear();
    }
}
