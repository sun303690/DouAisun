package com.douai.module.feature;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.scan.HostScanReport;
import com.douai.module.diagnostics.DouAiLog;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Host-side guard rails ported from Dyvv.
 *
 * <p>Two independent switches share this installer because both simply intercept a single
 * boolean/void guest method:</p>
 * <ul>
 *   <li>{@code adv_top_tab_swipe} - swallows the touch stream on the home top tab strip so the
 *       strip no longer pages left/right by horizontal gesture.</li>
 *   <li>{@code adv_hotfix_patch} - refuses the Reparo remote patch task, so a server pushed
 *       patch cannot replace the classes this module hooks at runtime.</li>
 * </ul>
 */
public final class HostGuardHook {

    static final String RULE_TOP_TAB_SWIPE = "adv_top_tab_swipe";
    static final String RULE_HOTFIX_PATCH = "adv_hotfix_patch";

    private final XposedInterface framework;
    private final HostScanReport report;
    private final ClassLoader loader;
    private final Consumer<String> logger;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();

    private volatile ExtendedSettings settings;
    private volatile boolean enabled;

    private HostGuardHook(XposedInterface framework, HostScanReport report, ClassLoader loader,
                          ExtendedSettings settings, Consumer<String> logger) {
        this.framework = framework;
        this.report = report;
        this.loader = loader;
        this.logger = logger;
        applySettings(settings);
    }

    public static HostGuardHook install(XposedInterface framework, HostScanReport report, ClassLoader loader,
                                        ExtendedSettings settings, Consumer<String> logger) {
        HostGuardHook hook = new HostGuardHook(framework, report, loader, settings, logger);
        hook.installAll();
        return hook;
    }

    public void setSettings(ExtendedSettings settings) {
        applySettings(settings);
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void applySettings(ExtendedSettings source) {
        this.settings = source;
        this.enabled = source != null && (source.topTabSwipeBlock || source.hotfixPatchBlock);
    }

    private void installAll() {
        if (!enabled) {
            note("Host guard hook idle: both switches are off");
            return;
        }
        bindOptional(RULE_TOP_TAB_SWIPE, "DouAi.HostGuard.TopTabSwipe", true);
        bindOptional(RULE_HOTFIX_PATCH, "DouAi.HostGuard.HotfixPatch", false);
        DouAiLog.event("host_guard", "installed", "rules=2", "");
    }

    /**
     * @param reportTrue when the blocked call returns a boolean true (touch consumed); otherwise the
     *                   void method is simply skipped so the remote patch never runs.
     */
    private void bindOptional(String rule, String id, boolean reportTrue) {
        try {
            Method target = HostTargets.method(report, rule, loader);
            if (target == null) {
                DouAiLog.event("host_guard", "skip", rule, "rule not resolved");
                return;
            }
            XposedInterface.HookHandle handle = framework.hook(target)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept((XposedInterface.Chain chain) -> {
                        if (!isRuleOn(rule)) {
                            return chain.proceed();
                        }
                        if (reportTrue) {
                            return Boolean.TRUE;
                        }
                        DouAiLog.event("host_guard", "blocked", rule, "skipped");
                        return null;
                    });
            if (handle == null) {
                throw new IllegalStateException("Framework returned an empty hook handle");
            }
            handles.add(handle);
            DouAiLog.event("host_guard", "bind", id, target.toString());
        } catch (Throwable failure) {
            DouAiLog.event("host_guard", "bind_failed", rule, String.valueOf(failure));
            note("Host guard bind failed for " + rule + ": " + failure);
        }
    }

    private boolean isRuleOn(String rule) {
        ExtendedSettings current = settings;
        if (current == null) {
            return false;
        }
        if (RULE_TOP_TAB_SWIPE.equals(rule)) {
            return current.topTabSwipeBlock;
        }
        if (RULE_HOTFIX_PATCH.equals(rule)) {
            return current.hotfixPatchBlock;
        }
        return false;
    }

    private void note(String message) {
        Consumer<String> sink = logger;
        if (sink != null) {
            sink.accept(message);
        }
    }
}
