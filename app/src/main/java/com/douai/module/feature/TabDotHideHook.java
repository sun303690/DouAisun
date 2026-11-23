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
 * Hides the red-dot / badge views on the bottom tab bar.
 *
 * <p>Dyvv anchors: {@code navigation.icons.tab_dot.v1},
 * {@code navigation.icons.tab_count_dot.v1}, {@code navigation.icons.tab_dot_image.v1}.
 * All three are accessors on {@code BottomTab}; every hook lets the host build the view
 * normally and then forces it {@code GONE} before returning, so no layout logic breaks.
 */
public final class TabDotHideHook {

    static final String RULE_DOT = "adv_tab_dot_hide";
    static final String RULE_COUNT = "adv_tab_dot_count";
    static final String RULE_IMAGE = "adv_tab_dot_image";

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private volatile ExtendedSettings settings;
    private volatile boolean enabled;

    private TabDotHideHook(ExtendedSettings settings) {
        this.settings = settings;
    }

    public static TabDotHideHook install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, ExtendedSettings settings) {
        TabDotHideHook hook = new TabDotHideHook(settings);
        hook.enabled = settings != null && settings.tabDotHide;
        DouAiLog.event("tab_dot", "installed", "rules=3", "");
        hook.bind(framework, report, loader, RULE_DOT, "DouAi.TabDot.View", hook);
        hook.bind(framework, report, loader, RULE_COUNT, "DouAi.TabDot.Count", hook);
        hook.bind(framework, report, loader, RULE_IMAGE, "DouAi.TabDot.Image", hook);
        return hook;
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean value) { enabled = value; }

    public void setSettings(ExtendedSettings next) {
        this.settings = next;
        this.enabled = next != null && next.tabDotHide;
    }

    public void unbind() {
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); } catch (Throwable ignored) { }
        }
        handles.clear();
        enabled = false;
    }

    private void bind(XposedInterface framework, HostScanReport report, ClassLoader loader,
                      String rule, String id, TabDotHideHook hook) {
        try {
            Method target = HostTargets.method(report, rule, loader);
            if (target == null) {
                DouAiLog.event("tab_dot", "skip", rule, "rule not resolved");
                return;
            }
            target.setAccessible(true);
            XposedInterface.HookHandle handle = framework.hook(target)
                    .setId(id)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (hook.enabled && result instanceof View) {
                            ((View) result).setVisibility(View.GONE);
                        }
                        return result;
                    });
            hook.handles.add(handle);
            DouAiLog.event("tab_dot", "bind", id, target.toString());
        }
        catch (Throwable failure) {
            DouAiLog.event("tab_dot", "bind_failed", rule, String.valueOf(failure));
        }
    }
}
