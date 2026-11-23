package com.douai.module.feature;

import android.view.View;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Hides two extra elements of the home bottom bar.
 *
 * <ul>
 *   <li>{@code adv_tab_title_hide} - forces {@code BottomTab.getTabTitle()} invisible so the bar
 *       keeps only icons.</li>
 *   <li>{@code adv_publish_btn_hide} - forces {@code PublishButton.getPublishView()} invisible so
 *       the centre publish button disappears.</li>
 * </ul>
 *
 * <p>Both hosts build the view first and the hook only mutates visibility afterwards, so layout
 * measurement stays intact.</p>
 */
public final class TabBarExtraHideHook {

    static final String RULE_TITLE = "adv_tab_title_hide";
    static final String RULE_PUBLISH = "adv_publish_btn_hide";

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private volatile ExtendedSettings settings;

    private TabBarExtraHideHook(ExtendedSettings settings) {
        this.settings = settings;
    }

    public static TabBarExtraHideHook install(XposedInterface framework, HostScanReport report,
                                              ClassLoader loader, ExtendedSettings settings) {
        TabBarExtraHideHook hook = new TabBarExtraHideHook(settings);
        hook.bind(framework, report, loader, RULE_TITLE, "DouAi.TabBar.Title");
        hook.bind(framework, report, loader, RULE_PUBLISH, "DouAi.TabBar.Publish");
        DouAiLog.event("tab_bar", "installed", "rules=2", "");
        return hook;
    }

    public void setSettings(ExtendedSettings next) {
        this.settings = next;
    }

    public boolean isEnabled() {
        ExtendedSettings current = settings;
        return current != null && (current.tabTitleHide || current.publishButtonHide);
    }

    public void unbind() {
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); } catch (Throwable ignored) { }
        }
        handles.clear();
    }

    private void bind(XposedInterface framework, HostScanReport report, ClassLoader loader,
                      String rule, String id) {
        try {
            Method target = HostTargets.method(report, rule, loader);
            if (target == null) {
                DouAiLog.event("tab_bar", "skip", rule, "rule not resolved");
                return;
            }
            target.setAccessible(true);
            XposedInterface.HookHandle handle = framework.hook(target)
                    .setId(id)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept((XposedInterface.Chain chain) -> {
                        Object result = chain.proceed();
                        if (isRuleOn(rule) && result instanceof View) {
                            ((View) result).setVisibility(View.GONE);
                        }
                        return result;
                    });
            if (handle == null) {
                throw new IllegalStateException("Framework returned an empty hook handle");
            }
            handles.add(handle);
            DouAiLog.event("tab_bar", "bind", id, target.toString());
        }
        catch (Throwable failure) {
            DouAiLog.event("tab_bar", "bind_failed", rule, String.valueOf(failure));
        }
    }

    private boolean isRuleOn(String rule) {
        ExtendedSettings current = settings;
        if (current == null) return false;
        if (RULE_TITLE.equals(rule)) return current.tabTitleHide;
        if (RULE_PUBLISH.equals(rule)) return current.publishButtonHide;
        return false;
    }
}
