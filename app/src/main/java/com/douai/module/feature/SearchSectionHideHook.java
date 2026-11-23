package com.douai.module.feature;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import android.view.View;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Hides the native cards that the search intermediate page injects between the
 * history list and the results: history, guess-search and the native tab card.
 *
 * <p>Anchors (Dyvv {@code search.sections.v1.*}):
 * {@code SearchMidHistoryCard},{@code SearchMidGuessSearchCard},
 * {@code SearchMiddleNaTabCard} -> {@code onBindData(Object, JSONObject)}.
 *
 * <p>The hook proceeds normally, then flips the card root view to
 * {@link View#GONE} so the section neither renders nor occupies
 * vertical space.
 */
public final class SearchSectionHideHook {

    private static final String[] RULES = {
            "adv_search_section_history",
            "adv_search_section_guess",
            "adv_search_section_tab"
    };

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private final AtomicLong hidden = new AtomicLong();
    private volatile boolean enabled;

    private SearchSectionHideHook(ExtendedSettings settings, Consumer<String> log) {
        this.log = log;
        this.enabled = settings != null && settings.searchSectionsHide;
    }

    public static SearchSectionHideHook install(XposedInterface framework, HostScanReport report,
                                                ClassLoader loader, ExtendedSettings settings,
                                                Consumer<String> log) throws Exception {
        Consumer<String> sink = log == null ? ignored -> { } : log;
        SearchSectionHideHook hook = new SearchSectionHideHook(settings, sink);
        int bound = 0;
        try {
            for (String rule : RULES) {
                Method bind = HostTargets.method(report, rule, loader);
                if (bind == null) {
                    DouAiLog.event("search_sections", "skip", rule, "rule not resolved");
                    continue;
                }
                XposedInterface.HookHandle handle = framework.hook(bind)
                        .setId("DouAi.SearchSections." + rule)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(hook::intercept);
                if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
                hook.handles.add(handle);
                bound++;
            }
            DouAiLog.event("search_sections", "installed", "rules=" + bound,
                    "total=" + RULES.length);
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setSettings(ExtendedSettings settings) {
        this.enabled = settings != null && settings.searchSectionsHide;
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (!enabled) return result;
        try {
            Object self = chain.getThisObject();
            if (self == null) return result;
            Object view = HostTargets.invoke(self, "getView");
            if (view instanceof android.view.View) {
                android.view.View card = (android.view.View) view;
                if (card.getVisibility() != View.GONE) {
                    card.setVisibility(View.GONE);
                    long total = hidden.incrementAndGet();
                    if (total == 1 || total % 50 == 0) {
                        DouAiLog.event("search_sections", "hide",
                                self.getClass().getName(), "total=" + total);
                    }
                }
            }
        } catch (Throwable failure) {
            DouAiLog.event("search_sections", "hide_failed",
                    "unknown", String.valueOf(failure));
        }
        return result;
    }

    private void unhookAll(Throwable failure) {
        for (XposedInterface.HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
                // best effort
            }
        }
        handles.clear();
        if (log != null) log.accept("Search sections hook failed: " + failure);
    }
}
