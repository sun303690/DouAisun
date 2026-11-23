package com.douai.module.feature;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/**
 * Strips the advertising entries out of the comment action menu.
 *
 * <p>Two Dyvv anchors gate those entries:
 * {@code comment.menu.header.ad_related_search.v1} ({@code LX/12Ne;->LIZ(Aweme)Z}) decides whether the
 * ad-related search entry shows up, and {@code comment.menu.header.ad_card.v1}
 * ({@code LX/12Nd;->LIZLLL(Aweme)Z}) decides whether the ad card entry shows up. Both are pure
 * visibility predicates, so answering {@code false} removes the entries and leaves every other menu
 * row untouched.</p>
 */
public final class CommentMenuAdHook {

    static final String RULE_SEARCH = "adv_comment_menu_ad_search";
    static final String RULE_CARD = "adv_comment_menu_ad_card";

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private final AtomicLong suppressed = new AtomicLong();
    private volatile boolean enabled;

    private CommentMenuAdHook(ExtendedSettings settings, Consumer<String> log) {
        this.enabled = settings != null && settings.commentAdFilter;
        this.log = log == null ? ignored -> { } : log;
    }

    public static CommentMenuAdHook install(XposedInterface framework, HostScanReport report,
                                            ClassLoader loader, ExtendedSettings settings,
                                            Consumer<String> log) throws Exception {
        CommentMenuAdHook hook = new CommentMenuAdHook(settings, log);
        try {
            hook.bind(framework, HostTargets.method(report, RULE_SEARCH, loader), "DouAi.Comment.MenuAdSearch");
            hook.bind(framework, HostTargets.method(report, RULE_CARD, loader), "DouAi.Comment.MenuAdCard");
            DouAiLog.event("comment_menu_ad", "installed", "rules=2");
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    private void bind(XposedInterface framework, Method target, String id) throws Exception {
        target.setAccessible(true);
        XposedInterface.HookHandle handle = framework.hook(target)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> intercept(chain, id));
        if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
        handles.add(handle);
        DouAiLog.event("comment_menu_ad", "bind", id, target.toString());
    }

    public void setSettings(ExtendedSettings next) {
        this.enabled = next != null && next.commentAdFilter;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getSuppressedCount() {
        return suppressed.get();
    }

    private Object intercept(XposedInterface.Chain chain, String id) throws Throwable {
        if (!enabled) return chain.proceed();
        long total = suppressed.incrementAndGet();
        if (total == 1L) {
            DouAiLog.event("comment_menu_ad", "suppress", id);
            log.accept("comment menu ad entry hidden");
        }
        return Boolean.FALSE;
    }

    private void unhookAll(Throwable cause) {
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
                // best effort during teardown
            }
        }
        handles.clear();
        if (cause != null) DouAiLog.failure("comment_menu_ad", "install", cause);
    }
}
