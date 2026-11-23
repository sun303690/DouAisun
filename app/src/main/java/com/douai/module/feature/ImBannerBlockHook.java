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
 * Blocks the in-app IM pull-down banner that announces a new private message.
 *
 * <p>Two Dyvv anchors cover both banner implementations shipped by the host:
 * {@code ui.im_banner.legacy_theme.v1} ({@code ImPushNotificationWidget.showBannerContent}) for the
 * legacy theme and {@code ui.im_banner.new_theme.v1} ({@code ImPushNotificationWidgetNB.bindBannerContent})
 * for the normative theme. Both only populate the banner view, so skipping the call leaves the banner
 * empty instead of filling it with the sender, avatar and message preview. Nothing else in the inner
 * push pipeline is touched — the notice still lands in the notification centre and the session list.</p>
 */
public final class ImBannerBlockHook {

    static final String RULE_LEGACY = "adv_im_banner_legacy";
    static final String RULE_THEME = "adv_im_banner_theme";

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private final AtomicLong suppressed = new AtomicLong();
    private volatile boolean enabled;

    private ImBannerBlockHook(ExtendedSettings settings, Consumer<String> log) {
        this.enabled = settings != null && settings.imBannerBlock;
        this.log = log == null ? ignored -> { } : log;
    }

    public static ImBannerBlockHook install(XposedInterface framework, HostScanReport report,
                                            ClassLoader loader, ExtendedSettings settings,
                                            Consumer<String> log) throws Exception {
        ImBannerBlockHook hook = new ImBannerBlockHook(settings, log);
        try {
            hook.bind(framework, HostTargets.method(report, RULE_LEGACY, loader), "DouAi.ImBanner.Legacy");
            hook.bind(framework, HostTargets.method(report, RULE_THEME, loader), "DouAi.ImBanner.Theme");
            DouAiLog.event("im_banner", "installed", "rules=2");
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
        DouAiLog.event("im_banner", "bind", id, target.toString());
    }

    public void setSettings(ExtendedSettings next) {
        this.enabled = next != null && next.imBannerBlock;
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
            DouAiLog.event("im_banner", "suppress", id);
            log.accept("im banner suppressed");
        }
        return null;
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
        if (cause != null) DouAiLog.failure("im_banner", "install", cause);
    }
}
